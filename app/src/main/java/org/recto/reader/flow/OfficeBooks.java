package org.recto.reader.flow;

import org.recto.reader.io.Archive;
import org.recto.reader.util.Text;
import org.recto.reader.util.Xml;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Word (DOCX) and OpenDocument (ODT) text documents: structure, basic formatting, tables, images, links. */
public final class OfficeBooks {
    private OfficeBooks() {}

    private static final int SPLIT_CHARS = 40_000;
    private static final String CSS = "p{margin:0 0 .6em}h1,h2,h3,h4,h5,h6{margin:1em 0 .5em;line-height:1.25}"
            + "table{border-collapse:collapse;margin:.6em 0;max-width:100%}td,th{border:1px solid rgba(127,127,127,.6);padding:.2em .4em;vertical-align:top}"
            + "img{max-width:100%;height:auto}ul,ol{padding-left:1.6em;margin:.3em 0}";

    /** Accumulates body HTML, splitting long documents into chapters at top-level headings. */
    private static final class Out {
        final MemBook book = new MemBook();
        final StringBuilder sb = new StringBuilder();
        final HashMap<String, Integer> ids = new HashMap<>();
        int headings, depth; // depth: open block-level containers that forbid splitting (tables, lists)

        void heading(int level, String html) {
            String plain = FlowBook.htmlToText(html).replaceAll("\\s+", " ").trim();
            if (level == 1 && depth == 0 && sb.length() > SPLIT_CHARS) flush();
            String id = "h" + (++headings);
            ids.put(id, book.chapters.size());
            sb.append("<h").append(level).append(" id=\"").append(id).append("\">").append(html).append("</h").append(level).append('>');
            if (!plain.isEmpty() && level <= 3) book.toc.add(new FlowBook.TocEntry(plain, level - 1, book.chapters.size(), id));
        }

        void anchor(String name) {
            ids.put(name, book.chapters.size());
            sb.append("<a id=\"").append(Text.esc(name)).append("\"></a>");
        }

        void flush() {
            if (sb.length() == 0) return;
            book.addChapter(MemBook.page(book.title, null, CSS, sb));
            sb.setLength(0);
        }

        MemBook finish(String name) {
            flush();
            if (book.chapters.isEmpty()) book.addChapter(MemBook.page(book.title, null, CSS, ""));
            // Internal links "#x" to anchors living in other chapters.
            for (int i = 0; i < book.html.size(); i++) {
                String h = book.html.get(i);
                if (!h.contains("href=\"#")) continue;
                StringBuffer out = new StringBuffer();
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("href=\"#([^\"]+)\"").matcher(h);
                while (m.find()) {
                    Integer c = ids.get(FlowBook.decodeEntities(m.group(1)));
                    String href = c == null || c == i ? "#" + m.group(1) : "c" + c + ".html#" + m.group(1);
                    m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement("href=\"" + href + "\""));
                }
                m.appendTail(out);
                book.html.set(i, out.toString());
            }
            if (book.title == null || book.title.isEmpty()) book.title = Text.baseName(name);
            if (book.toc.size() < 2) book.toc.clear();
            return book;
        }
    }

    private static XmlPullParser open(Archive a, String path) throws Exception {
        Archive.Entry e = a.find(path);
        if (e == null) return null;
        XmlPullParser p = Xml.parser();
        p.setInput(new ByteArrayInputStream(a.read(e, 64L << 20)), "UTF-8");
        return p;
    }

    private static boolean on(XmlPullParser p) {
        String v = Xml.attr(p, "val");
        return v == null || !(v.equals("0") || v.equals("false") || v.equals("none") || v.equals("off"));
    }

    // ---- DOCX ------------------------------------------------------------------------------------

    public static MemBook docx(Archive zip, String name) throws IOException {
        try {
            return new Docx(zip).run(name);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Invalid DOCX: " + e.getMessage(), e);
        }
    }

    private static final class Docx {
        final Archive zip;
        final Out out = new Out();
        final Map<String, String> rels = new HashMap<>();
        final Map<String, Integer> headingStyles = new HashMap<>();
        XmlPullParser p;
        // current paragraph
        StringBuilder para;
        int paraLevel;
        String paraAlign;
        boolean paraList;
        // current run formatting
        boolean b, i, u, s, sup, sub, inTabs;
        String listOpen;

        Docx(Archive zip) { this.zip = zip; }

        MemBook run(String name) throws Exception {
            out.book.archive = zip;
            XmlPullParser r = open(zip, "word/_rels/document.xml.rels");
            if (r != null) for (int t = r.getEventType(); t != XmlPullParser.END_DOCUMENT; t = r.next())
                if (t == XmlPullParser.START_TAG && Xml.name(r).equals("relationship")) {
                    String id = Xml.attr(r, "Id"), target = Xml.attr(r, "Target"), mode = Xml.attr(r, "TargetMode");
                    if (id != null && target != null)
                        rels.put(id, "External".equals(mode) ? target : FlowBook.resolve("word/", target));
                }
            XmlPullParser st = open(zip, "word/styles.xml");
            if (st != null) {
                String id = null;
                for (int t = st.getEventType(); t != XmlPullParser.END_DOCUMENT; t = st.next()) {
                    if (t != XmlPullParser.START_TAG) continue;
                    String n = Xml.name(st);
                    if (n.equals("style")) id = Xml.attr(st, "styleId");
                    else if (n.equals("name") && id != null) {
                        String v = Xml.attr(st, "val");
                        v = v == null ? "" : v.toLowerCase(Locale.ROOT);
                        if (v.startsWith("heading ")) {
                            try { headingStyles.put(id, Math.min(6, Integer.parseInt(v.substring(8).trim()))); } catch (NumberFormatException ignored) {}
                        } else if (v.equals("title")) headingStyles.put(id, 1);
                    } else if (n.equals("outlinelvl") && id != null && !headingStyles.containsKey(id)) {
                        try { headingStyles.put(id, Math.min(6, Integer.parseInt(Xml.attr(st, "val")) + 1)); } catch (Exception ignored) {}
                    }
                }
            }
            XmlPullParser core = open(zip, "docProps/core.xml");
            if (core != null) for (int t = core.getEventType(); t != XmlPullParser.END_DOCUMENT; t = core.next()) {
                if (t != XmlPullParser.START_TAG) continue;
                String n = Xml.name(core);
                if (n.equals("title") && out.book.title == null) out.book.title = emptyNull(Xml.text(core));
                else if (n.equals("creator") && out.book.author == null) out.book.author = emptyNull(Xml.text(core));
            }
            p = open(zip, "word/document.xml");
            if (p == null) throw new IOException("Invalid DOCX: no document");
            body();
            return out.finish(name);
        }

        void body() throws Exception {
            String link = null;
            for (int t = p.getEventType(); t != XmlPullParser.END_DOCUMENT; t = p.next()) {
                if (t == XmlPullParser.START_TAG) {
                    switch (Xml.name(p)) {
                        case "p":
                            para = new StringBuilder();
                            paraLevel = 0;
                            paraAlign = null;
                            paraList = false;
                            break;
                        case "pstyle": {
                            Integer l = headingStyles.get(Xml.attr(p, "val"));
                            if (l != null) paraLevel = l;
                            break;
                        }
                        case "outlinelvl":
                            if (para != null && paraLevel == 0) {
                                try { paraLevel = Math.min(6, Integer.parseInt(Xml.attr(p, "val")) + 1); } catch (Exception ignored) {}
                                if (paraLevel > 6) paraLevel = 0;
                            }
                            break;
                        case "jc": {
                            String v = Xml.attr(p, "val");
                            if ("center".equals(v)) paraAlign = "center";
                            else if ("right".equals(v) || "end".equals(v)) paraAlign = "right";
                            else if ("both".equals(v)) paraAlign = "justify";
                            break;
                        }
                        case "numpr": paraList = true; break;
                        case "r": b = i = u = s = sup = sub = false; break;
                        case "b": b = on(p); break;
                        case "i": i = on(p); break;
                        case "u": u = on(p); break;
                        case "strike": case "dstrike": s = on(p); break;
                        case "vertalign": {
                            String v = Xml.attr(p, "val");
                            sup = "superscript".equals(v);
                            sub = "subscript".equals(v);
                            break;
                        }
                        case "t": text(Xml.text0(p)); break;
                        case "tabs": inTabs = true; break;
                        case "tab": if (para != null && !inTabs) para.append("&#8195;"); break;
                        case "br": if (para != null) para.append("<br/>"); break;
                        case "hyperlink": {
                            String id = Xml.attr(p, "id"), anchor = Xml.attr(p, "anchor");
                            link = anchor != null ? "#" + anchor : rels.get(id);
                            if (link != null && para != null) para.append("<a href=\"").append(Text.esc(link)).append("\">");
                            break;
                        }
                        case "bookmarkstart": {
                            String n = Xml.attr(p, "name");
                            if (n != null && para != null && !n.startsWith("_GoBack")) {
                                out.ids.put(n, out.book.chapters.size());
                                para.append("<a id=\"").append(Text.esc(n)).append("\"></a>");
                            }
                            break;
                        }
                        case "blip": image(Xml.attr(p, "embed")); break;
                        case "imagedata": image(Xml.attr(p, "id")); break;
                        case "tbl": closeList(); out.depth++; out.sb.append("<table>"); break;
                        case "tr": out.sb.append("<tr>"); break;
                        case "tc": out.sb.append("<td>"); break;
                        case "footnotereference": case "endnotereference": if (para != null) para.append("<sup>*</sup>"); break;
                        default: break;
                    }
                } else if (t == XmlPullParser.END_TAG) {
                    switch (Xml.name(p)) {
                        case "p": endParagraph(); break;
                        case "tabs": inTabs = false; break;
                        case "hyperlink": if (link != null && para != null) para.append("</a>"); link = null; break;
                        case "tbl": out.sb.append("</table>"); out.depth--; break;
                        case "tr": out.sb.append("</tr>"); break;
                        case "tc": out.sb.append("</td>"); break;
                        default: break;
                    }
                }
            }
            closeList();
        }

        void text(String t) {
            if (para == null || t.isEmpty()) return;
            StringBuilder o = para;
            if (b) o.append("<b>");
            if (i) o.append("<i>");
            if (u) o.append("<u>");
            if (s) o.append("<s>");
            if (sup) o.append("<sup>");
            if (sub) o.append("<sub>");
            Text.esc(o, t);
            if (sub) o.append("</sub>");
            if (sup) o.append("</sup>");
            if (s) o.append("</s>");
            if (u) o.append("</u>");
            if (i) o.append("</i>");
            if (b) o.append("</b>");
        }

        void image(String rid) {
            String path = rid == null ? null : rels.get(rid);
            if (path == null || para == null || zip.find(path) == null) return;
            para.append("<img src=\"").append(Text.esc(path)).append("\" alt=\"\"/>");
        }

        void closeList() {
            if (listOpen != null) {
                out.sb.append("</ul>");
                out.depth--;
                listOpen = null;
            }
        }

        void endParagraph() {
            if (para == null) return;
            String html = para.toString();
            para = null;
            if (paraLevel > 0 && !html.trim().isEmpty()) {
                closeList();
                out.heading(paraLevel, html);
                return;
            }
            if (paraList) {
                if (listOpen == null) {
                    out.sb.append("<ul>");
                    out.depth++;
                    listOpen = "ul";
                }
                out.sb.append("<li>").append(html).append("</li>");
                return;
            }
            closeList();
            out.sb.append("<p");
            if (paraAlign != null) out.sb.append(" style=\"text-align:").append(paraAlign).append('"');
            out.sb.append('>').append(html.isEmpty() ? "&#160;" : html).append("</p>");
        }
    }

    private static String emptyNull(String s) { return s == null || s.isEmpty() ? null : s; }

    // ---- ODT -------------------------------------------------------------------------------------

    public static MemBook odt(Archive zip, String name) throws IOException {
        try {
            return new Odt(zip).run(name);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Invalid ODT: " + e.getMessage(), e);
        }
    }

    private static final class Odt {
        static final int B = 1, I = 2, U = 4, S = 8, SUP = 16, SUB = 32;
        final Archive zip;
        final Out out = new Out();
        final Map<String, Integer> fmt = new HashMap<>();
        final Map<String, String> align = new HashMap<>();
        final Map<String, String> parent = new HashMap<>();
        XmlPullParser p;

        Odt(Archive zip) { this.zip = zip; }

        MemBook run(String name) throws Exception {
            out.book.archive = zip;
            XmlPullParser meta = open(zip, "meta.xml");
            if (meta != null) for (int t = meta.getEventType(); t != XmlPullParser.END_DOCUMENT; t = meta.next()) {
                if (t != XmlPullParser.START_TAG) continue;
                String n = Xml.name(meta);
                if (n.equals("title") && out.book.title == null) out.book.title = emptyNull(Xml.text(meta));
                else if ((n.equals("creator") || n.equals("initial-creator")) && out.book.author == null) out.book.author = emptyNull(Xml.text(meta));
            }
            XmlPullParser styles = open(zip, "styles.xml");
            if (styles != null) readStyles(styles);
            p = open(zip, "content.xml");
            if (p == null) throw new IOException("Invalid ODT: no content");
            readStyles(null);
            return out.finish(name);
        }

        int flags(String style) {
            int f = 0;
            for (int guard = 0; style != null && guard < 10; guard++) {
                Integer v = fmt.get(style);
                if (v != null) f |= v;
                style = parent.get(style);
            }
            return f;
        }

        /** Reads style definitions; with {@code null} it parses content.xml (styles + body). */
        void readStyles(XmlPullParser sp) throws Exception {
            XmlPullParser x = sp != null ? sp : p;
            String style = null;
            boolean body = false;
            List<Integer> spanStack = new ArrayList<>();
            StringBuilder o = null;
            int headingLevel = 0, lists = 0;
            String link = null;
            for (int t = x.getEventType(); t != XmlPullParser.END_DOCUMENT; t = x.next()) {
                if (t == XmlPullParser.START_TAG) {
                    String n = Xml.name(x);
                    if (!body) {
                        if (n.equals("style")) {
                            style = Xml.attr(x, "name");
                            String par = Xml.attr(x, "parent-style-name");
                            if (style != null && par != null) parent.put(style, par);
                        } else if (n.equals("text-properties") && style != null) {
                            int f = 0;
                            String w = Xml.attr(x, "font-weight"), st = Xml.attr(x, "font-style"), ul = Xml.attr(x, "text-underline-style"),
                                    lt = Xml.attr(x, "text-line-through-style"), pos = Xml.attr(x, "text-position");
                            if ("bold".equals(w) || "700".equals(w) || "800".equals(w) || "900".equals(w)) f |= B;
                            if ("italic".equals(st) || "oblique".equals(st)) f |= I;
                            if (ul != null && !"none".equals(ul)) f |= U;
                            if (lt != null && !"none".equals(lt)) f |= S;
                            if (pos != null && pos.startsWith("super")) f |= SUP;
                            if (pos != null && pos.startsWith("sub")) f |= SUB;
                            fmt.put(style, f);
                        } else if (n.equals("paragraph-properties") && style != null) {
                            String a = Xml.attr(x, "text-align");
                            if ("center".equals(a)) align.put(style, "center");
                            else if ("end".equals(a) || "right".equals(a)) align.put(style, "right");
                            else if ("justify".equals(a)) align.put(style, "justify");
                        } else if (n.equals("text") && sp == null && "office".equals(prefix(x))) {
                            body = true;
                        }
                        continue;
                    }
                    switch (n) {
                        case "h": {
                            String lv = Xml.attr(x, "outline-level");
                            try { headingLevel = Math.max(1, Math.min(6, Integer.parseInt(lv))); } catch (Exception e) { headingLevel = 1; }
                            o = new StringBuilder();
                            spanStack.clear();
                            int f = flags(Xml.attr(x, "style-name")) & ~B;
                            spanStack.add(f);
                            openFmt(o, f);
                            break;
                        }
                        case "p": {
                            o = new StringBuilder();
                            spanStack.clear();
                            String sn = Xml.attr(x, "style-name");
                            int f = flags(sn);
                            spanStack.add(f);
                            String a = null;
                            for (String s = sn; s != null && a == null; s = parent.get(s)) a = align.get(s);
                            out.sb.append(lists > 0 ? "" : "<p" + (a != null ? " style=\"text-align:" + a + "\"" : "") + ">");
                            openFmt(o, f);
                            break;
                        }
                        case "span": {
                            if (o == null) break;
                            int f = flags(Xml.attr(x, "style-name"));
                            spanStack.add(f);
                            openFmt(o, f);
                            break;
                        }
                        case "a": {
                            link = Xml.attr(x, "href");
                            if (o != null && link != null) o.append("<a href=\"").append(Text.esc(link)).append("\">");
                            break;
                        }
                        case "s": {
                            if (o == null) break;
                            int c = 1;
                            try { c = Integer.parseInt(Xml.attr(x, "c")); } catch (Exception ignored) {}
                            for (int k = 0; k < Math.min(c, 100); k++) o.append("&#160;");
                            break;
                        }
                        case "tab": if (o != null) o.append("&#8195;"); break;
                        case "line-break": if (o != null) o.append("<br/>"); break;
                        case "bookmark": case "bookmark-start": {
                            String bn = Xml.attr(x, "name");
                            if (bn != null && o != null) {
                                out.ids.put(bn, out.book.chapters.size());
                                o.append("<a id=\"").append(Text.esc(bn)).append("\"></a>");
                            }
                            break;
                        }
                        case "image": {
                            String href = Xml.attr(x, "href");
                            if (href != null && zip.find(href) != null) {
                                String tag = "<img src=\"" + Text.esc(href) + "\" alt=\"\"/>";
                                if (o != null) o.append(tag); else out.sb.append(tag);
                            }
                            break;
                        }
                        case "note": skip(x); break;
                        case "list": out.sb.append("<ul>"); out.depth++; lists++; break;
                        case "list-item": out.sb.append("<li>"); break;
                        case "table": out.sb.append("<table>"); out.depth++; break;
                        case "table-row": out.sb.append("<tr>"); break;
                        case "table-cell": {
                            String span = Xml.attr(x, "number-columns-spanned");
                            out.sb.append(span != null ? "<td colspan=\"" + Text.esc(span) + "\">" : "<td>");
                            break;
                        }
                        default: break;
                    }
                } else if (t == XmlPullParser.TEXT && body && o != null) {
                    Text.esc(o, x.getText());
                } else if (t == XmlPullParser.END_TAG) {
                    String n = Xml.name(x);
                    if (!body) {
                        if (n.equals("style")) style = null;
                        continue;
                    }
                    switch (n) {
                        case "h":
                            if (o != null) {
                                closeFmt(o, spanStack.isEmpty() ? 0 : spanStack.get(0));
                                if (!FlowBook.htmlToText(o.toString()).trim().isEmpty()) out.heading(headingLevel, o.toString());
                            }
                            o = null;
                            break;
                        case "p":
                            if (o != null) {
                                closeFmt(o, spanStack.isEmpty() ? 0 : spanStack.get(0));
                                String h = o.toString();
                                if (lists > 0) out.sb.append(h).append("<br/>");
                                else out.sb.append(h.isEmpty() ? "&#160;" : h).append("</p>");
                            }
                            o = null;
                            break;
                        case "span":
                            if (o != null && spanStack.size() > 1) closeFmt(o, spanStack.remove(spanStack.size() - 1));
                            break;
                        case "a": if (o != null && link != null) o.append("</a>"); link = null; break;
                        case "list": out.sb.append("</ul>"); out.depth--; lists--; break;
                        case "list-item": out.sb.append("</li>"); break;
                        case "table": out.sb.append("</table>"); out.depth--; break;
                        case "table-row": out.sb.append("</tr>"); break;
                        case "table-cell": out.sb.append("</td>"); break;
                        default: break;
                    }
                }
            }
        }

        private static String prefix(XmlPullParser x) {
            String n = x.getName();
            int c = n == null ? -1 : n.indexOf(':');
            if (c > 0) return n.substring(0, c);
            String ns = x.getNamespace();
            return ns != null && ns.contains(":office:") ? "office" : "";
        }

        private static void skip(XmlPullParser x) throws Exception {
            for (int d = 1; d > 0; ) {
                int t = x.next();
                if (t == XmlPullParser.START_TAG) d++;
                else if (t == XmlPullParser.END_TAG) d--;
                else if (t == XmlPullParser.END_DOCUMENT) return;
            }
        }

        private static void openFmt(StringBuilder o, int f) {
            if ((f & B) != 0) o.append("<b>");
            if ((f & I) != 0) o.append("<i>");
            if ((f & U) != 0) o.append("<u>");
            if ((f & S) != 0) o.append("<s>");
            if ((f & SUP) != 0) o.append("<sup>");
            if ((f & SUB) != 0) o.append("<sub>");
        }

        private static void closeFmt(StringBuilder o, int f) {
            if ((f & SUB) != 0) o.append("</sub>");
            if ((f & SUP) != 0) o.append("</sup>");
            if ((f & S) != 0) o.append("</s>");
            if ((f & U) != 0) o.append("</u>");
            if ((f & I) != 0) o.append("</i>");
            if ((f & B) != 0) o.append("</b>");
        }
    }
}
