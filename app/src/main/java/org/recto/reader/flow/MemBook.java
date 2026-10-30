package org.recto.reader.flow;

import org.recto.reader.io.Archive;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** A book converted to in-memory HTML chapters (FB2, MOBI, TXT, DOCX, ...). */
public class MemBook extends FlowBook {
    final List<String> html = new ArrayList<>();
    final HashMap<String, Object[]> resources = new HashMap<>(); // path -> {mime, byte[]}
    String coverPath;
    /** Optional archive serving resources not held in memory (images of DOCX / ODT files). */
    Archive archive;

    /** Adds a chapter and returns its index. */
    public int addChapter(String body) {
        int i = html.size();
        String path = "c" + i + ".html";
        html.add(body);
        chapters.add(new Chapter(path, body.length()));
        return i;
    }

    public void addResource(String path, String mime, byte[] data) { resources.put(path, new Object[]{mime, data}); }

    @Override public String chapterHtml(int i) { return html.get(i); }

    @Override public Resource resource(String path) throws IOException {
        Object[] r = resources.get(path);
        if (r != null) return new Resource((String) r[0], (byte[]) r[1]);
        Archive.Entry e = archive != null ? archive.find(path) : null;
        return e == null ? null : new Resource(EpubBook.guessMime(e.name), archive.open(e));
    }

    @Override public void close() throws IOException {
        if (archive != null) archive.close();
    }

    @Override public byte[] cover() {
        Object[] r = coverPath == null ? null : resources.get(coverPath);
        return r == null ? null : (byte[]) r[1];
    }

    /** Wraps body markup into a complete HTML document with a shared stylesheet. */
    public static String page(String title, String lang, String css, CharSequence body) {
        StringBuilder sb = new StringBuilder(body.length() + 512);
        sb.append("<!DOCTYPE html><html");
        if (lang != null && !lang.isEmpty()) sb.append(" lang=\"").append(org.recto.reader.util.Text.esc(lang)).append('"');
        sb.append("><head><meta charset=\"utf-8\"><title>");
        if (title != null) org.recto.reader.util.Text.esc(sb, title);
        sb.append("</title>");
        if (css != null) sb.append("<style>").append(css).append("</style>");
        sb.append("</head><body>").append(body).append("</body></html>");
        return sb.toString();
    }
}
