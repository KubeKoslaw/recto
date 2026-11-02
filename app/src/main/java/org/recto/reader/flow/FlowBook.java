package org.recto.reader.flow;

import org.recto.reader.util.Text;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A reflowable book: an ordered list of HTML chapters plus resources, rendered by {@link FlowView}. */
public abstract class FlowBook implements Closeable {
    public String title, author, language;
    /** Right-to-left page progression. */
    public boolean rtl;
    public final List<Chapter> chapters = new ArrayList<>();
    public final List<TocEntry> toc = new ArrayList<>();

    public static final class Chapter {
        public final String path;
        public final long weight;

        public Chapter(String path, long weight) {
            this.path = path;
            this.weight = Math.max(1, weight);
        }
    }

    public static final class TocEntry {
        public final String title, anchor;
        public final int depth, chapter;

        public TocEntry(String title, int depth, int chapter, String anchor) {
            this.title = title;
            this.depth = depth;
            this.chapter = chapter;
            this.anchor = anchor;
        }
    }

    public static final class Resource {
        public final String mime;
        public final InputStream data;

        public Resource(String mime, InputStream data) {
            this.mime = mime;
            this.data = data;
        }

        public Resource(String mime, byte[] data) { this(mime, new ByteArrayInputStream(data)); }
    }

    /** HTML source of a chapter, safe to parse with an HTML (not XML) parser. */
    public abstract String chapterHtml(int i) throws IOException;

    /** A resource (image, stylesheet, font...) by book path, or null. */
    public abstract Resource resource(String path) throws IOException;

    /** Encoded cover image, or null. */
    public byte[] cover() throws IOException { return null; }

    private HashMap<String, Integer> pathIndex, foldedIndex;
    private int indexedChapters = -1;

    /** Chapter by exact path, else case-insensitive match, else -1. Hash lookup (called per request). */
    public int chapterIndex(String path) {
        if (path == null) return -1;
        HashMap<String, Integer> exact, folded;
        synchronized (this) {
            int n = chapters.size();
            if (indexedChapters != n) { // chapters are only appended while a book is being built
                pathIndex = new HashMap<>(n * 2);
                foldedIndex = new HashMap<>(n * 2);
                for (int i = n - 1; i >= 0; i--) { // first occurrence wins
                    String p = chapters.get(i).path;
                    pathIndex.put(p, i);
                    foldedIndex.put(fold(p), i);
                }
                indexedChapters = n;
            }
            exact = pathIndex;
            folded = foldedIndex;
        }
        Integer i = exact.get(path);
        if (i == null) i = folded.get(fold(path));
        return i == null ? -1 : i;
    }

    private static String fold(String s) { return s.toLowerCase(Locale.ROOT).toUpperCase(Locale.ROOT); }

    /** Visible text of a chapter (used for search). */
    public String chapterText(int i) throws IOException { return htmlToText(chapterHtml(i)); }

    @Override public void close() throws IOException {}

    // ---- helpers shared by the formats ---------------------------------------------------------

    private static final Pattern SELF_CLOSING = Pattern.compile(
            "<(?!(?:area|base|br|col|embed|hr|img|input|link|meta|param|source|track|wbr|basefont|frame|keygen)[\\s/>])([a-zA-Z][\\w:.-]*)((?:\\s[^<>]*?)?)\\s*/>");
    private static final Pattern XML_ENCODING = Pattern.compile("^<\\?xml[^>]*encoding\\s*=\\s*[\"']([\\w.:-]+)[\"']");
    private static final Pattern META_CHARSET = Pattern.compile("<meta[^>]+charset\\s*=\\s*[\"']?([\\w.:-]+)", Pattern.CASE_INSENSITIVE);

    /** Expands XHTML empty-element shorthand ({@code <div/>}) that HTML parsers would misread. */
    public static String xhtmlToHtml(String s) {
        if (s.indexOf("/>") < 0) return s;
        return SELF_CLOSING.matcher(s).replaceAll("<$1$2></$1>");
    }

    /** Decodes an (X)HTML document honoring BOM, XML declaration and meta charset. */
    public static String decodeMarkup(byte[] b) {
        int n = b.length;
        if (n >= 3 && (b[0] & 0xff) == 0xef && (b[1] & 0xff) == 0xbb && (b[2] & 0xff) == 0xbf)
            return new String(b, 3, n - 3, StandardCharsets.UTF_8);
        if (n >= 2 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xfe) return new String(b, 2, n - 2, StandardCharsets.UTF_16LE);
        if (n >= 2 && (b[0] & 0xff) == 0xfe && (b[1] & 0xff) == 0xff) return new String(b, 2, n - 2, StandardCharsets.UTF_16BE);
        String head = new String(b, 0, Math.min(n, 1024), StandardCharsets.ISO_8859_1);
        Matcher m = XML_ENCODING.matcher(head.trim());
        String cs = m.find() ? m.group(1) : null;
        if (cs == null) {
            m = META_CHARSET.matcher(head);
            if (m.find()) cs = m.group(1);
        }
        if (cs != null) {
            try {
                Charset c = Charset.forName(cs);
                if (!c.name().toLowerCase(Locale.ROOT).startsWith("utf-16")) return new String(b, c);
            } catch (Exception ignored) {}
        }
        return Text.decode(b);
    }

    private static final Pattern DROP = Pattern.compile("(?is)<(head|script|style)\\b.*?</\\1\\s*>|<!--.*?-->");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");

    public static String htmlToText(String html) {
        String s = DROP.matcher(html).replaceAll(" ");
        s = TAG.matcher(s).replaceAll("");
        return decodeEntities(s);
    }

    public static String decodeEntities(String s) {
        if (s.indexOf('&') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            int semi;
            if (c == '&' && (semi = s.indexOf(';', i)) > i && semi - i < 12) {
                String e = s.substring(i + 1, semi);
                String r = null;
                try {
                    if (e.startsWith("#x") || e.startsWith("#X")) r = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
                    else if (e.startsWith("#")) r = new String(Character.toChars(Integer.parseInt(e.substring(1))));
                } catch (Exception ignored) {}
                if (r == null) {
                    switch (e) {
                        case "amp": r = "&"; break;
                        case "lt": r = "<"; break;
                        case "gt": r = ">"; break;
                        case "quot": r = "\""; break;
                        case "apos": r = "'"; break;
                        case "nbsp": r = "\u00a0"; break;
                        case "shy": r = ""; break;
                        case "mdash": r = "\u2014"; break;
                        case "ndash": r = "\u2013"; break;
                        case "hellip": r = "\u2026"; break;
                        case "laquo": r = "\u00ab"; break;
                        case "raquo": r = "\u00bb"; break;
                        case "lsquo": r = "\u2018"; break;
                        case "rsquo": r = "\u2019"; break;
                        case "ldquo": r = "\u201c"; break;
                        case "rdquo": r = "\u201d"; break;
                        case "bdquo": r = "\u201e"; break;
                        case "copy": r = "\u00a9"; break;
                        default: break;
                    }
                }
                if (r != null) {
                    sb.append(r);
                    i = semi;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Resolves {@code href} (percent-encoded, may contain ../) against a directory ("a/b/"). */
    public static String resolve(String dir, String href) {
        int hash = href.indexOf('#');
        if (hash >= 0) href = href.substring(0, hash);
        int q = href.indexOf('?');
        if (q >= 0) href = href.substring(0, q);
        href = percentDecode(href);
        String path = href.startsWith("/") ? href.substring(1) : dir + href;
        List<String> out = new ArrayList<>();
        for (String seg : path.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (!out.isEmpty()) out.remove(out.size() - 1);
            } else out.add(seg);
        }
        StringBuilder sb = new StringBuilder();
        for (String seg : out) {
            if (sb.length() > 0) sb.append('/');
            sb.append(seg);
        }
        return sb.toString();
    }

    public static String fragment(String href) {
        int hash = href.indexOf('#');
        return hash >= 0 && hash < href.length() - 1 ? percentDecode(href.substring(hash + 1)) : null;
    }

    public static String dirOf(String path) {
        int s = path.lastIndexOf('/');
        return s >= 0 ? path.substring(0, s + 1) : "";
    }

    public static String percentDecode(String s) {
        if (s.indexOf('%') < 0) return s;
        byte[] out = new byte[s.length() * 3];
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                try {
                    out[n++] = (byte) Integer.parseInt(s.substring(i + 1, i + 3), 16);
                    i += 2;
                    continue;
                } catch (NumberFormatException ignored) {
                    n--;
                }
            }
            byte[] u = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
            System.arraycopy(u, 0, out, n, u.length);
            n += u.length;
        }
        return new String(out, 0, n, StandardCharsets.UTF_8);
    }
}
