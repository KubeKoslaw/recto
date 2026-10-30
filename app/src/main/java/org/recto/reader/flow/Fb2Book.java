package org.recto.reader.flow;

import org.recto.reader.util.Text;
import org.recto.reader.util.Xml;
import org.xmlpull.v1.XmlPullParser;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** FictionBook 2 to HTML: one chapter per top-level section, notes bodies appended at the end. */
public final class Fb2Book {
    private Fb2Book() {}

    static final String CSS = "body{margin:0}p{margin:0;text-indent:1.4em;text-align:justify}"
            + "h1,h2,h3,h4,h5{text-align:center;text-indent:0;margin:1em 0 .6em}h1{font-size:1.5em}h2{font-size:1.3em}"
            + "h3,h4,h5{font-size:1.1em}.subtitle{text-align:center;font-weight:bold;margin:.8em 0;text-indent:0}"
            + ".epigraph{margin:1em 0 1em 25%;font-style:italic}.cite{margin:1em 1.5em}.poem{margin:1em 2em}"
            + ".stanza{margin:.6em 0}.v{text-indent:0;text-align:left}.author{text-align:right;font-style:italic;text-indent:0}"
            + ".image{text-align:center;text-indent:0;margin:.6em 0}.image img{max-width:100%}.annotation{font-size:.9em;margin:1em 0}"
            + "a.note{text-decoration:none;vertical-align:super;font-size:.75em}table{border-collapse:collapse;margin:.8em auto}"
            + "td,th{border:1px solid currentColor;padding:.2em .4em}";

    private static final Pattern LINK = Pattern.compile("href=\"@@([^\"]*)@@\"");

    public static MemBook parse(InputStream in) throws IOException {
        try {
            return new Converter().run(in);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Invalid FB2: " + e.getMessage(), e);
        }
    }

    private static final class Converter {
        final MemBook book = new MemBook();
        final HashMap<String, Integer> idChapter = new HashMap<>();
        final List<StringBuilder> bodies = new ArrayList<>();
        final List<String> titles = new ArrayList<>();
        StringBuilder out;
        XmlPullParser p;
        String coverId;
        int sectionDepth, bodyIndex = -1;
        boolean notesBody;

        MemBook run(InputStream in) throws Exception {
            p = Xml.parser();
            p.setInput(in, null);
            for (int t = p.getEventType(); t != XmlPullParser.END_DOCUMENT; t = p.next()) {
                if (t != XmlPullParser.START_TAG) continue;
                String n = Xml.name(p);
                if (n.equals("description")) description();
                else if (n.equals("body")) body();
                else if (n.equals("binary")) binary();
            }
            // Resolve note links now that every id is known to live in some chapter.
            for (int i = 0; i < bodies.size(); i++) {
                Matcher m = LINK.matcher(bodies.get(i));
                StringBuffer sb = new StringBuffer();
                while (m.find()) {
                    String id = m.group(1);
                    Integer ch = idChapter.get(id);
                    String href = ch == null || ch == i ? "#" + id : "c" + ch + ".html#" + id;
                    m.appendReplacement(sb, Matcher.quoteReplacement("href=\"" + Text.esc(href) + "\""));
                }
                m.appendTail(sb);
                book.addChapter(MemBook.page(titles.get(i), book.language, CSS, sb));
            }
            if (book.chapters.isEmpty()) throw new IOException("Invalid FB2: no content");
            if (coverId != null && book.resources.containsKey("img/" + coverId)) book.coverPath = "img/" + coverId;
            return book;
        }

        void description() throws Exception {
            boolean inTitleInfo = false;
            int depth = 1;
            String first = null, middle = null, last = null, nick = null;
            boolean inAuthor = false;
            while (depth > 0) {
                int t = p.next();
                if (t == XmlPullParser.END_DOCUMENT) return;
                if (t == XmlPullParser.END_TAG) {
                    depth--;
                    String n = Xml.name(p);
                    if (n.equals("title-info")) inTitleInfo = false;
                    else if (n.equals("author") && inAuthor) {
                        inAuthor = false;
                        if (book.author == null) {
                            StringBuilder a = new StringBuilder();
                            for (String s : new String[]{first, middle, last}) if (s != null && !s.isEmpty()) a.append(a.length() > 0 ? " " : "").append(s);
                            book.author = a.length() > 0 ? a.toString() : nick;
                        }
                    }
                    continue;
                }
                if (t != XmlPullParser.START_TAG) continue;
                depth++;
                String n = Xml.name(p);
                if (n.equals("title-info")) inTitleInfo = true;
                if (!inTitleInfo) continue;
                switch (n) {
                    case "author": inAuthor = true; first = middle = last = nick = null; break;
                    case "first-name": if (inAuthor) first = Xml.text(p); depth--; break;
                    case "middle-name": if (inAuthor) middle = Xml.text(p); depth--; break;
                    case "last-name": if (inAuthor) last = Xml.text(p); depth--; break;
                    case "nickname": if (inAuthor) nick = Xml.text(p); depth--; break;
                    case "book-title": book.title = Xml.text(p); depth--; break;
                    case "lang": book.language = Xml.text(p); depth--; break;
                    case "image": {
                        String href = Xml.attr(p, "href");
                        if (href != null && href.startsWith("#")) coverId = href.substring(1);
                        break;
                    }
                    default: break;
                }
            }
        }

        void startChapter(String title) {
            out = new StringBuilder();
            bodies.add(out);
            titles.add(title);
        }

        int chapter() { return bodies.size() - 1; }

        void body() throws Exception {
            bodyIndex++;
            String name = Xml.attr(p, "name");
            notesBody = bodyIndex > 0 || (name != null && (name.equals("notes") || name.equals("comments")));
            sectionDepth = 0;
            out = null;
            if (notesBody) {
                startChapter(name != null ? name : "Notes");
                out.append("<hr/>");
            }
            int depth = 1;
            while (depth > 0) {
                int t = p.next();
                if (t == XmlPullParser.END_DOCUMENT) return;
                if (t == XmlPullParser.END_TAG) {
                    depth--;
                    if (depth > 0) close(Xml.name(p));
                    continue;
                }
                if (t == XmlPullParser.TEXT) {
                    if (out == null && !p.isWhitespace()) ensureChapter();
                    if (out != null) Text.esc(out, p.getText());
                    continue;
                }
                if (t != XmlPullParser.START_TAG) continue;
                depth++;
                if (open(Xml.name(p))) depth--; // element consumed up to its end tag
            }
        }

        private final ArrayList<String> sectionIds = new ArrayList<>();
        private int autoId;

        private void id() {
            String id = Xml.attr(p, "id");
            if (id != null && out != null) {
                out.append(" id=\"").append(Text.esc(id)).append('"');
                idChapter.put(id, chapter());
            }
        }

        private void ensureChapter() {
            if (out == null) startChapter(book.title);
        }

        /** Emits the opening markup; returns true if the whole element was consumed. */
        boolean open(String n) throws Exception {
            switch (n) {
                case "section": {
                    if (sectionDepth == 0 && !notesBody) startChapter(null);
                    sectionDepth++;
                    ensureChapter();
                    String id = Xml.attr(p, "id");
                    if (id == null) id = "s" + (++autoId);
                    sectionIds.add(id);
                    idChapter.put(id, chapter());
                    out.append("<div class=\"section\" id=\"").append(Text.esc(id)).append("\">");
                    break;
                }
                case "title": {
                    ensureChapter();
                    int level = Math.min(5, Math.max(1, sectionDepth == 0 ? 1 : sectionDepth + 1));
                    String text = titleMarkup();
                    out.append("<h").append(level).append('>').append(text).append("</h").append(level).append('>');
                    String plain = FlowBook.htmlToText(text).replaceAll("\\s+", " ").trim();
                    if (!plain.isEmpty() && !notesBody && sectionDepth <= 2) {
                        if (sectionDepth <= 1 && titles.get(chapter()) == null) titles.set(chapter(), plain);
                        String anchor = sectionDepth >= 2 ? sectionIds.get(sectionIds.size() - 1) : null;
                        book.toc.add(new FlowBook.TocEntry(plain, Math.max(0, sectionDepth - 1), chapter(), anchor));
                    }
                    return true;
                }
                case "p": ensureChapter(); out.append("<p"); id(); out.append('>'); break;
                case "subtitle": ensureChapter(); out.append("<p class=\"subtitle\""); id(); out.append('>'); break;
                case "empty-line": ensureChapter(); out.append("<p>&#160;</p>"); break;
                case "emphasis": out.append("<em>"); break;
                case "strong": out.append("<strong>"); break;
                case "strikethrough": out.append("<s>"); break;
                case "sub": out.append("<sub>"); break;
                case "sup": out.append("<sup>"); break;
                case "code": out.append("<code>"); break;
                case "epigraph": ensureChapter(); out.append("<div class=\"epigraph\">"); break;
                case "cite": ensureChapter(); out.append("<div class=\"cite\""); id(); out.append('>'); break;
                case "annotation": ensureChapter(); out.append("<div class=\"annotation\">"); break;
                case "poem": ensureChapter(); out.append("<div class=\"poem\""); id(); out.append('>'); break;
                case "stanza": out.append("<div class=\"stanza\">"); break;
                case "v": out.append("<p class=\"v\">"); break;
                case "text-author": out.append("<p class=\"author\">"); break;
                case "date": out.append("<p class=\"author\">"); break;
                case "table": ensureChapter(); out.append("<table>"); break;
                case "tr": out.append("<tr>"); break;
                case "td": out.append("<td>"); break;
                case "th": out.append("<th>"); break;
                case "a": {
                    String href = Xml.attr(p, "href");
                    String type = Xml.attr(p, "type");
                    if (href == null) href = "";
                    out.append("<a");
                    if ("note".equals(type)) out.append(" class=\"note\"");
                    if (href.startsWith("#")) out.append(" href=\"@@").append(Text.esc(href.substring(1))).append("@@\">");
                    else out.append(" href=\"").append(Text.esc(href)).append("\">");
                    break;
                }
                case "image": {
                    ensureChapter();
                    String href = Xml.attr(p, "href");
                    if (href != null && href.startsWith("#")) {
                        out.append("<img src=\"img/").append(Text.esc(href.substring(1))).append("\" alt=\"\"/>");
                    }
                    break;
                }
                default: break;
            }
            return false;
        }

        /** Reads a {@code <title>} element: its paragraphs joined with line breaks. */
        String titleMarkup() throws Exception {
            StringBuilder sb = new StringBuilder();
            int depth = 1;
            boolean firstP = true;
            while (depth > 0) {
                int t = p.next();
                if (t == XmlPullParser.END_DOCUMENT) break;
                if (t == XmlPullParser.START_TAG) {
                    depth++;
                    String n = Xml.name(p);
                    if (n.equals("p")) {
                        if (!firstP) sb.append("<br/>");
                        firstP = false;
                    } else if (n.equals("emphasis")) sb.append("<em>");
                    else if (n.equals("strong")) sb.append("<strong>");
                } else if (t == XmlPullParser.END_TAG) {
                    depth--;
                    String n = Xml.name(p);
                    if (n.equals("emphasis")) sb.append("</em>");
                    else if (n.equals("strong")) sb.append("</strong>");
                } else if (t == XmlPullParser.TEXT) {
                    Text.esc(sb, p.getText());
                }
            }
            return sb.toString().trim();
        }

        void close(String n) {
            if (out == null) return;
            switch (n) {
                case "section":
                    out.append("</div>");
                    sectionDepth--;
                    if (!sectionIds.isEmpty()) sectionIds.remove(sectionIds.size() - 1);
                    break;
                case "p": case "subtitle": case "v": case "text-author": case "date": out.append("</p>"); break;
                case "emphasis": out.append("</em>"); break;
                case "strong": out.append("</strong>"); break;
                case "strikethrough": out.append("</s>"); break;
                case "sub": out.append("</sub>"); break;
                case "sup": out.append("</sup>"); break;
                case "code": out.append("</code>"); break;
                case "epigraph": case "cite": case "annotation": case "poem": case "stanza": out.append("</div>"); break;
                case "table": out.append("</table>"); break;
                case "tr": out.append("</tr>"); break;
                case "td": out.append("</td>"); break;
                case "th": out.append("</th>"); break;
                case "a": out.append("</a>"); break;
                default: break;
            }
        }

        void binary() throws Exception {
            String id = Xml.attr(p, "id"), type = Xml.attr(p, "content-type");
            StringBuilder sb = new StringBuilder();
            for (int t = p.next(); t != XmlPullParser.END_TAG && t != XmlPullParser.END_DOCUMENT; t = p.next())
                if (t == XmlPullParser.TEXT) sb.append(p.getText());
            if (id == null) return;
            try {
                byte[] data = Text.base64(sb);
                String mime = Text.sniffImage(data, data.length);
                book.addResource("img/" + id, mime != null ? mime : type != null ? type : "image/jpeg", data);
            } catch (IllegalArgumentException ignored) {}
        }
    }
}
