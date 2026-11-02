package org.recto.reader.flow;

import org.recto.reader.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Plain text, Markdown and single-file HTML documents. */
public final class TextBooks {
    private TextBooks() {}

    private static final int CHAPTER_CHARS = 60_000;
    private static final String TXT_CSS = "p{margin:0 0 .35em;text-indent:1.2em}h2{text-align:center;margin:1.2em 0 .8em;font-size:1.25em}"
            + "pre{white-space:pre-wrap;word-wrap:break-word;font-size:.9em}";
    private static final Pattern HEADING = Pattern.compile(
            "(?iu)^(chapter|rozdzia[łl]|glava|глава|часть|part|cz[ęe][śs][ćc]|book|ksi[ęe]ga|tom|volume|prolog(ue)?|epilog(ue)?|kapitel|chapitre|cap[íi]tulo|capitolo|hoofdstuk)\\b.{0,60}$"
                    + "|^[IVXLC]{1,7}\\.?$|^\\d{1,3}\\.?$|^\\*\\s*\\*\\s*\\*$");

    /** Plain text: paragraphs from blank lines or single lines, chapters from heading-like lines. */
    public static MemBook txt(byte[] data, String name) {
        String s = Text.decode(data).replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = s.split("\n", -1);
        int blank = 0, nonBlank = 0, indented = 0;
        long len = 0;
        for (String l : lines) {
            if (l.trim().isEmpty()) blank++;
            else {
                nonBlank++;
                len += l.length();
                if (l.startsWith("    ") || l.startsWith("\t")) indented++;
            }
        }
        MemBook b = new MemBook();
        b.title = Text.baseName(name);
        // Hard-wrapped text (paragraphs separated by blank lines) vs. one paragraph per line.
        boolean joinLines = nonBlank > 0 && blank > nonBlank / 8 && len / nonBlank < 100;
        boolean preformatted = nonBlank > 20 && indented > nonBlank / 3;
        List<String> paras = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String l : lines) {
            String t = preformatted ? stripEnd(l) : l.trim();
            if (t.isEmpty()) {
                if (cur.length() > 0) paras.add(cur.toString());
                cur.setLength(0);
                if (preformatted) paras.add("");
                continue;
            }
            if (!joinLines || preformatted) {
                if (cur.length() > 0) paras.add(cur.toString());
                cur.setLength(0);
                paras.add(t);
                continue;
            }
            // A heading-like line on its own ends the paragraph even without a blank line.
            if (cur.length() > 0) cur.append(' ');
            cur.append(t);
        }
        if (cur.length() > 0) paras.add(cur.toString());

        StringBuilder body = new StringBuilder();
        String chapterTitle = null;
        if (preformatted) body.append("<pre>");
        for (String para : paras) {
            boolean heading = !preformatted && para.length() < 72 && HEADING.matcher(para).find();
            if ((heading && body.length() > 200) || body.length() > CHAPTER_CHARS) {
                flush(b, body, chapterTitle, preformatted);
                chapterTitle = null;
            }
            if (heading) {
                if (!para.startsWith("*")) {
                    chapterTitle = para;
                    b.toc.add(new FlowBook.TocEntry(para, 0, b.chapters.size(), null));
                }
                body.append("<h2>");
                Text.esc(body, para);
                body.append("</h2>");
            } else if (preformatted) {
                Text.esc(body, para);
                body.append('\n');
            } else {
                body.append("<p>");
                Text.esc(body, para);
                body.append("</p>");
            }
        }
        flush(b, body, chapterTitle, preformatted);
        if (b.chapters.isEmpty()) b.addChapter(MemBook.page(b.title, null, TXT_CSS, "<p></p>"));
        if (b.toc.size() < 2) b.toc.clear();
        return b;
    }

    private static String stripEnd(String s) {
        int e = s.length();
        while (e > 0 && Character.isWhitespace(s.charAt(e - 1))) e--;
        return s.substring(0, e);
    }

    private static void flush(MemBook b, StringBuilder body, String title, boolean pre) {
        if (body.length() == 0 || (pre && body.length() == 5)) return;
        if (pre) body.append("</pre>");
        b.addChapter(MemBook.page(title != null ? title : b.title, null, TXT_CSS, body));
        body.setLength(0);
        if (pre) body.append("<pre>");
    }

    /** A single HTML file (images and styles referenced by it are not available). */
    public static MemBook html(byte[] data, String name) {
        MemBook b = new MemBook();
        String html = FlowBook.xhtmlToHtml(FlowBook.decodeMarkup(data));
        Matcher m = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
        b.title = m.find() ? FlowBook.decodeEntities(m.group(1)).trim() : Text.baseName(name);
        if (b.title.isEmpty()) b.title = Text.baseName(name);
        b.addChapter(html);
        return b;
    }

    // ---- Markdown (CommonMark subset) ----------------------------------------------------------

    private static final String MD_CSS = "h1,h2,h3,h4,h5,h6{line-height:1.25;margin:1em 0 .5em}pre{white-space:pre-wrap;"
            + "word-wrap:break-word;padding:.6em;border-radius:4px;background:rgba(127,127,127,.12)}code{font-size:.92em}"
            + "blockquote{margin:.8em 0;padding-left:.9em;border-left:3px solid rgba(127,127,127,.5)}"
            + "table{border-collapse:collapse}td,th{border:1px solid rgba(127,127,127,.5);padding:.2em .5em}";

    private static final Pattern MD_LIST = Pattern.compile("[-*+]\\s+.*|\\d{1,9}[.)]\\s+.*");
    private static final Pattern MD_LIST_MARK = Pattern.compile("^([-*+]|\\d{1,9}[.)])\\s+");
    private static final Pattern MD_RULE = Pattern.compile("-{3,}|\\*{3,}|_{3,}");
    private static final Pattern MD_HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*$");

    public static MemBook markdown(byte[] data, String name) {
        String[] lines = Text.decode(data).replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        MemBook b = new MemBook();
        b.title = Text.baseName(name);
        StringBuilder out = new StringBuilder();
        StringBuilder para = new StringBuilder();
        String listType = null;
        boolean code = false, quote = false;
        int headings = 0;
        for (String raw : lines) {
            String line = raw.replace("\t", "    ");
            if (code) {
                if (line.trim().startsWith("```") || line.trim().startsWith("~~~")) {
                    out.append("</code></pre>");
                    code = false;
                } else {
                    Text.esc(out, line);
                    out.append('\n');
                }
                continue;
            }
            String t = line.trim();
            boolean isList = MD_LIST.matcher(t).matches();
            boolean isRule = MD_RULE.matcher(t).matches();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("```") || t.startsWith("~~~") || t.startsWith(">") != quote
                    || isList || isRule) {
                if (para.length() > 0) {
                    out.append("<p>").append(inline(para.toString())).append("</p>");
                    para.setLength(0);
                }
            }
            if (!isList && listType != null && (t.isEmpty() || !raw.startsWith("  "))) {
                out.append("</li></").append(listType).append('>');
                listType = null;
            }
            if (quote && !t.startsWith(">")) {
                out.append("</blockquote>");
                quote = false;
            }
            if (t.isEmpty()) continue;
            if (t.startsWith("```") || t.startsWith("~~~")) {
                out.append("<pre><code>");
                code = true;
                continue;
            }
            if (t.startsWith(">")) {
                if (!quote) out.append("<blockquote>");
                quote = true;
                t = t.substring(1).trim();
                if (t.isEmpty()) continue;
            }
            Matcher h = MD_HEADING.matcher(t);
            if (h.find()) {
                int level = h.group(1).length();
                String text = h.group(2);
                // Long documents are split at top-level headings (outside lists and quotes).
                if (level <= 2 && listType == null && !quote && out.length() > CHAPTER_CHARS) {
                    b.addChapter(MemBook.page(b.title, null, MD_CSS, out));
                    out.setLength(0);
                }
                String id = "h" + (++headings);
                out.append("<h").append(level).append(" id=\"").append(id).append("\">").append(inline(text)).append("</h").append(level).append('>');
                if (level <= 3) b.toc.add(new FlowBook.TocEntry(FlowBook.htmlToText(inline(text)), level - 1, b.chapters.size(), id));
                if (level == 1 && headings == 1) b.title = FlowBook.htmlToText(inline(text));
                continue;
            }
            if (isRule) {
                out.append("<hr/>");
                continue;
            }
            if (isList) {
                String type = Character.isDigit(t.charAt(0)) ? "ol" : "ul";
                if (!type.equals(listType)) {
                    if (listType != null) out.append("</li></").append(listType).append('>');
                    out.append('<').append(type).append("><li>");
                    listType = type;
                } else {
                    out.append("</li><li>");
                }
                out.append(inline(MD_LIST_MARK.matcher(t).replaceFirst("")));
                continue;
            }
            if (listType != null) {
                out.append(' ').append(inline(t));
                continue;
            }
            if (para.length() > 0) para.append(raw.endsWith("  ") ? "\u0000" : " ");
            para.append(t);
        }
        if (para.length() > 0) out.append("<p>").append(inline(para.toString())).append("</p>");
        if (code) out.append("</code></pre>");
        if (listType != null) out.append("</li></").append(listType).append('>');
        if (quote) out.append("</blockquote>");
        if (out.length() > 0 || b.chapters.isEmpty()) b.addChapter(MemBook.page(b.title, null, MD_CSS, out));
        if (b.toc.size() < 2) b.toc.clear();
        return b;
    }

    private static final Pattern MD_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern MD_IMG = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)\\s]+)[^)]*\\)");
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)\\s]+)[^)]*\\)");
    private static final Pattern MD_BOLD = Pattern.compile("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1");
    private static final Pattern MD_EM = Pattern.compile("(?<![\\w*])([*_])(?=\\S)(.+?)(?<=\\S)\\1(?![\\w*])");

    static String inline(String s) {
        // Protect code spans first, then escape and apply emphasis / links.
        List<String> codes = new ArrayList<>();
        Matcher m = MD_CODE.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            codes.add(m.group(1));
            m.appendReplacement(sb, "\u0001" + (codes.size() - 1) + "\u0001");
        }
        m.appendTail(sb);
        String t = Text.esc(sb.toString());
        t = MD_IMG.matcher(t).replaceAll("[$1]");
        t = MD_LINK.matcher(t).replaceAll("<a href=\"$2\">$1</a>");
        t = MD_BOLD.matcher(t).replaceAll("<strong>$2</strong>");
        t = MD_EM.matcher(t).replaceAll("<em>$2</em>");
        t = t.replace("\u0000", "<br/>");
        for (int i = 0; i < codes.size(); i++)
            t = t.replace("\u0001" + i + "\u0001", "<code>" + Text.esc(codes.get(i)) + "</code>");
        return t;
    }
}
