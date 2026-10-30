package org.recto.reader.flow;

import org.recto.reader.io.Archive;
import org.recto.reader.io.ZipArchive;
import org.recto.reader.util.Text;
import org.recto.reader.util.Xml;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** EPUB 2/3 reader: package document, spine, NCX / nav table of contents, obfuscated fonts. */
public final class EpubBook extends FlowBook {
    private final ZipArchive zip;
    private final HashMap<String, String> mimes = new HashMap<>();
    private final HashMap<String, Integer> obfuscated = new HashMap<>(); // path -> 1 IDPF, 2 Adobe
    private String coverPath, uniqueId;
    /** Pre-paginated (fixed layout) book. */
    public boolean fixedLayout;

    private static final class Item {
        final String id, path, mime, props;

        Item(String id, String path, String mime, String props) {
            this.id = id;
            this.path = path;
            this.mime = mime;
            this.props = props == null ? "" : props;
        }
    }

    public EpubBook(ZipArchive zip) throws IOException {
        this.zip = zip;
        try {
            String opf = null;
            Archive.Entry container = zip.find("META-INF/container.xml");
            if (container != null) {
                XmlPullParser p = Xml.parser();
                p.setInput(new ByteArrayInputStream(zip.read(container, 1 << 20)), null);
                for (int t = p.getEventType(); t != XmlPullParser.END_DOCUMENT && opf == null; t = p.next())
                    if (t == XmlPullParser.START_TAG && Xml.name(p).equals("rootfile")) opf = Xml.attr(p, "full-path");
            }
            if (opf == null || zip.find(opf) == null) {
                opf = null;
                for (Archive.Entry e : zip.entries()) if (e.name.toLowerCase(Locale.ROOT).endsWith(".opf")) { opf = e.name; break; }
            }
            if (opf == null) throw new IOException("Invalid EPUB: package document not found");
            parsePackage(opf, zip.read(zip.find(opf), 16 << 20));
            readEncryption();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Invalid EPUB: " + e.getMessage(), e);
        }
        if (chapters.isEmpty()) throw new IOException("Invalid EPUB: empty spine");
    }

    private void parsePackage(String opfPath, byte[] data) throws Exception {
        String dir = dirOf(opfPath);
        HashMap<String, Item> manifest = new HashMap<>();
        List<String> spine = new ArrayList<>(), extra = new ArrayList<>();
        String ncxId = null, coverId = null, navPath = null, uidRef = null;
        HashMap<String, String> identifiers = new HashMap<>();
        XmlPullParser p = Xml.parser();
        p.setInput(new ByteArrayInputStream(data), null);
        boolean inMeta = false;
        for (int t = p.getEventType(); t != XmlPullParser.END_DOCUMENT; t = p.next()) {
            if (t == XmlPullParser.END_TAG && Xml.name(p).equals("metadata")) inMeta = false;
            if (t != XmlPullParser.START_TAG) continue;
            String n = Xml.name(p);
            switch (n) {
                case "package": uidRef = Xml.attr(p, "unique-identifier"); break;
                case "metadata": inMeta = true; break;
                case "title": if (inMeta && title == null) title = emptyToNull(Xml.text(p)); break;
                case "creator": if (inMeta && author == null) author = emptyToNull(Xml.text(p)); break;
                case "language": if (inMeta && language == null) language = emptyToNull(Xml.text(p)); break;
                case "identifier": {
                    String id = Xml.attr(p, "id");
                    identifiers.put(id == null ? "" : id, Xml.text(p));
                    break;
                }
                case "meta": {
                    String name = Xml.attr(p, "name"), prop = Xml.attr(p, "property");
                    if ("cover".equals(name)) coverId = Xml.attr(p, "content");
                    else if ("rendition:layout".equals(prop)) fixedLayout = "pre-paginated".equals(Xml.text(p));
                    else if ("fixed-layout".equals(name)) fixedLayout |= "true".equals(Xml.attr(p, "content"));
                    break;
                }
                case "item": {
                    String id = Xml.attr(p, "id"), href = Xml.attr(p, "href");
                    if (id == null || href == null) break;
                    Item it = new Item(id, resolve(dir, href), Xml.attr(p, "media-type"), Xml.attr(p, "properties"));
                    manifest.put(id, it);
                    if (it.mime != null) mimes.put(it.path, it.mime);
                    if (it.props.contains("nav")) navPath = it.path;
                    if (it.props.contains("cover-image")) coverPath = it.path;
                    break;
                }
                case "spine": {
                    ncxId = Xml.attr(p, "toc");
                    rtl = "rtl".equals(Xml.attr(p, "page-progression-direction"));
                    break;
                }
                case "itemref": {
                    String idref = Xml.attr(p, "idref");
                    if (idref == null) break;
                    if ("no".equals(Xml.attr(p, "linear"))) extra.add(idref); else spine.add(idref);
                    break;
                }
                default: break;
            }
        }
        uniqueId = uidRef != null ? identifiers.get(uidRef) : null;
        if (uniqueId == null && !identifiers.isEmpty()) uniqueId = identifiers.values().iterator().next();
        spine.addAll(extra); // non-linear items stay reachable through links
        for (String id : spine) {
            Item it = manifest.get(id);
            if (it == null) continue;
            Archive.Entry e = zip.find(it.path);
            if (e == null) continue;
            chapters.add(new Chapter(e.name, e.size));
        }
        if (coverPath == null && coverId != null && manifest.containsKey(coverId)) coverPath = manifest.get(coverId).path;
        if (coverPath == null) {
            for (Item it : manifest.values())
                if (it.mime != null && it.mime.startsWith("image/") && (it.id.toLowerCase(Locale.ROOT).contains("cover")
                        || it.path.toLowerCase(Locale.ROOT).contains("cover"))) { coverPath = it.path; break; }
        }
        try {
            if (navPath != null && zip.find(navPath) != null) parseNav(navPath);
        } catch (Exception ignored) {}
        if (toc.isEmpty() && ncxId != null && manifest.containsKey(ncxId)) {
            try { parseNcx(manifest.get(ncxId).path); } catch (Exception ignored) {}
        }
        if (toc.isEmpty()) {
            for (Item it : manifest.values())
                if ("application/x-dtbncx+xml".equals(it.mime)) {
                    try { parseNcx(it.path); } catch (Exception ignored) {}
                    break;
                }
        }
    }

    private static String emptyToNull(String s) { return s == null || s.isEmpty() ? null : s; }

    private void addToc(String title, int depth, String dir, String href) {
        if (title == null || title.isEmpty()) return;
        int ch = -1;
        String anchor = null;
        if (href != null && !href.isEmpty()) {
            ch = chapterIndex(resolve(dir, href));
            anchor = fragment(href);
        }
        if (ch < 0 && href != null) return;
        toc.add(new TocEntry(title, depth, ch, anchor));
    }

    private void parseNav(String path) throws Exception {
        String dir = dirOf(path);
        XmlPullParser p = Xml.parser();
        p.setInput(new ByteArrayInputStream(zip.read(zip.find(path), 8 << 20)), null);
        int navDepth = 0, olDepth = 0;
        boolean inToc = false, done = false;
        String pendingHref = null;
        StringBuilder label = null;
        for (int t = p.getEventType(); t != XmlPullParser.END_DOCUMENT && !done; t = p.next()) {
            if (t == XmlPullParser.START_TAG) {
                String n = Xml.name(p);
                if (n.equals("nav")) {
                    navDepth++;
                    String type = Xml.attr(p, "type"), role = Xml.attr(p, "role");
                    if (!inToc && ((type != null && type.contains("toc")) || "doc-toc".equals(role))) inToc = true;
                } else if (inToc && n.equals("ol")) {
                    olDepth++;
                } else if (inToc && (n.equals("a") || n.equals("span")) && label == null) {
                    pendingHref = n.equals("a") ? Xml.attr(p, "href") : null;
                    label = new StringBuilder();
                    label.append(Xml.text(p));
                    addToc(label.toString().trim(), Math.max(0, olDepth - 1), dir, pendingHref);
                    label = null;
                }
            } else if (t == XmlPullParser.END_TAG) {
                String n = Xml.name(p);
                if (n.equals("nav")) {
                    navDepth--;
                    if (inToc) done = true;
                } else if (inToc && n.equals("ol")) olDepth--;
            }
        }
        // Fallback: a nav without epub:type - take the first one.
        if (toc.isEmpty() && !inToc) {
            p = Xml.parser();
            p.setInput(new ByteArrayInputStream(zip.read(zip.find(path), 8 << 20)), null);
            olDepth = 0;
            for (int t = p.getEventType(); t != XmlPullParser.END_DOCUMENT; t = p.next()) {
                if (t == XmlPullParser.START_TAG) {
                    String n = Xml.name(p);
                    if (n.equals("ol")) olDepth++;
                    else if (n.equals("a") && olDepth > 0) {
                        String href = Xml.attr(p, "href");
                        addToc(Xml.text(p), olDepth - 1, dir, href);
                    }
                } else if (t == XmlPullParser.END_TAG && Xml.name(p).equals("ol")) {
                    if (--olDepth == 0) break;
                }
            }
        }
    }

    private void parseNcx(String path) throws Exception {
        Archive.Entry e = zip.find(path);
        if (e == null) return;
        String dir = dirOf(path);
        XmlPullParser p = Xml.parser();
        p.setInput(new ByteArrayInputStream(zip.read(e, 8 << 20)), null);
        int depth = 0;
        String label = null;
        for (int t = p.getEventType(); t != XmlPullParser.END_DOCUMENT; t = p.next()) {
            if (t == XmlPullParser.START_TAG) {
                String n = Xml.name(p);
                if (n.equals("navpoint")) {
                    depth++;
                    label = null;
                } else if (n.equals("text") && depth > 0 && label == null) {
                    label = Xml.text(p);
                } else if (n.equals("content") && depth > 0) {
                    addToc(label, depth - 1, dir, Xml.attr(p, "src"));
                    label = "";
                } else if (n.equals("navmap")) depth = 0;
            } else if (t == XmlPullParser.END_TAG) {
                String n = Xml.name(p);
                if (n.equals("navpoint")) depth--;
                else if (n.equals("navmap")) break;
            }
        }
    }

    private void readEncryption() {
        Archive.Entry e = zip.find("META-INF/encryption.xml");
        if (e == null || uniqueId == null) return;
        try {
            XmlPullParser p = Xml.parser();
            p.setInput(new ByteArrayInputStream(zip.read(e, 1 << 20)), null);
            int algo = 0;
            for (int t = p.getEventType(); t != XmlPullParser.END_DOCUMENT; t = p.next()) {
                if (t != XmlPullParser.START_TAG) continue;
                String n = Xml.name(p);
                if (n.equals("encryptiondata")) algo = 0;
                else if (n.equals("encryptionmethod")) {
                    String a = Xml.attr(p, "algorithm");
                    algo = "http://www.idpf.org/2008/embedding".equals(a) ? 1 : "http://ns.adobe.com/pdf/enc#RC".equals(a) ? 2 : 0;
                } else if (n.equals("cipherreference") && algo != 0) {
                    String uri = Xml.attr(p, "uri");
                    if (uri != null) obfuscated.put(resolve("", uri), algo);
                }
            }
        } catch (Exception ignored) {}
    }

    private byte[] deobfuscate(byte[] data, int algo) {
        try {
            byte[] key;
            int len;
            if (algo == 1) {
                key = MessageDigest.getInstance("SHA-1").digest(uniqueId.replaceAll("[\\s\\u0020\\u0009\\u000D\\u000A]", "").getBytes(StandardCharsets.UTF_8));
                len = 1040;
            } else {
                String hex = uniqueId.replaceFirst("(?i)^urn:uuid:", "").replace("-", "");
                if (hex.length() < 32) return data;
                key = new byte[16];
                for (int i = 0; i < 16; i++) key[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
                len = 1024;
            }
            for (int i = 0; i < Math.min(len, data.length); i++) data[i] ^= key[i % key.length];
        } catch (Exception ignored) {}
        return data;
    }

    @Override public String chapterHtml(int i) throws IOException {
        Archive.Entry e = zip.find(chapters.get(i).path);
        if (e == null) throw new IOException("Missing chapter");
        return xhtmlToHtml(decodeMarkup(zip.read(e, 32 << 20)));
    }

    @Override public Resource resource(String path) throws IOException {
        Archive.Entry e = zip.find(path);
        if (e == null) return null;
        String mime = mimes.get(e.name);
        if (mime == null) mime = guessMime(e.name);
        Integer algo = obfuscated.get(e.name);
        if (algo != null) return new Resource(mime, deobfuscate(zip.read(e, 64 << 20), algo));
        if (mime.contains("html") || mime.contains("xml") && !mime.contains("svg"))
            return new Resource("text/html", xhtmlToHtml(decodeMarkup(zip.read(e, 32 << 20))).getBytes(StandardCharsets.UTF_8));
        return new Resource(mime, zip.open(e));
    }

    static String guessMime(String name) {
        switch (Text.ext(name)) {
            case "css": return "text/css";
            case "xhtml": case "html": case "htm": return "application/xhtml+xml";
            case "svg": return "image/svg+xml";
            case "ttf": return "font/ttf";
            case "otf": return "font/otf";
            case "woff": return "font/woff";
            case "woff2": return "font/woff2";
            case "js": return "text/javascript";
            case "mp3": return "audio/mpeg";
            case "mp4": return "video/mp4";
            default: return Text.isImageName(name) ? Text.imageMime(name) : "application/octet-stream";
        }
    }

    @Override public byte[] cover() throws IOException {
        if (coverPath == null) return null;
        Archive.Entry e = zip.find(coverPath);
        if (e == null) return null;
        if (Text.isImageName(e.name)) return zip.read(e, 32 << 20);
        // Cover given as an XHTML page: take its first image.
        List<String> imgs = images(xhtmlToHtml(decodeMarkup(zip.read(e, 4 << 20))), dirOf(e.name));
        Archive.Entry img = imgs.isEmpty() ? null : zip.find(imgs.get(0));
        return img == null ? null : zip.read(img, 32 << 20);
    }

    private static final Pattern IMG = Pattern.compile("<(?:img|image)\\b[^>]*?\\b(?:src|xlink:href|href)\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    static List<String> images(String html, String dir) {
        List<String> out = new ArrayList<>();
        Matcher m = IMG.matcher(html);
        while (m.find()) if (!m.group(1).startsWith("data:")) out.add(resolve(dir, m.group(1)));
        return out;
    }

    /**
     * For fixed-layout books where every page is a single picture (manga, comics), returns the zip
     * entries of those pictures so they can be shown by the image viewer. Otherwise null.
     */
    public List<Archive.Entry> imagePages() {
        if (!fixedLayout) return null;
        List<Archive.Entry> pages = new ArrayList<>();
        try {
            for (int i = 0; i < chapters.size(); i++) {
                String path = chapters.get(i).path;
                if (Text.isImageName(path)) {
                    pages.add(zip.find(path));
                    continue;
                }
                String html = chapterHtml(i);
                List<String> imgs = images(html, dirOf(path));
                if (imgs.size() != 1 || htmlToText(html).trim().length() > 200) return null;
                Archive.Entry e = zip.find(imgs.get(0));
                if (e == null) return null;
                pages.add(e);
            }
        } catch (IOException e) {
            return null;
        }
        return pages.isEmpty() ? null : pages;
    }

    public ZipArchive archive() { return zip; }

    @Override public void close() throws IOException { zip.close(); }
}
