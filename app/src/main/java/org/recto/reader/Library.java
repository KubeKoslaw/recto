package org.recto.reader;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.recto.reader.util.Text;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Books known to the app with their reading state, stored as one small JSON file. */
final class Library {
    static final class Book {
        String id, uri, title, author, format, pos;
        long size, added, opened;
        float progress;
        int pages;
        /** Fixed-layout view settings, -1 = default. */
        int mode = -1, dir = -1, fit = -1, invert = -1;
        /** Found by a folder scan (as opposed to opened directly). */
        boolean folder;
        /** Bookmarks, newest first. Null when the book has none. */
        JSONArray marks;

        JSONObject json() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id).put("uri", uri).put("title", title).put("author", author).put("format", format).put("pos", pos)
                    .put("size", size).put("added", added).put("opened", opened).put("progress", (double) progress)
                    .put("pages", pages).put("mode", mode).put("dir", dir).put("fit", fit).put("invert", invert).put("folder", folder);
            if (marks != null && marks.length() > 0) o.put("marks", marks);
            return o;
        }

        Book copy() {
            Book b = new Book();
            b.id = id; b.uri = uri; b.title = title; b.author = author; b.format = format; b.pos = pos;
            b.size = size; b.added = added; b.opened = opened; b.progress = progress; b.pages = pages;
            b.mode = mode; b.dir = dir; b.fit = fit; b.invert = invert; b.folder = folder;
            if (marks != null) {
                try {
                    b.marks = new JSONArray(marks.toString());
                } catch (Exception e) {
                    b.marks = marks;
                }
            }
            return b;
        }

        static Book of(JSONObject o) {
            Book b = new Book();
            b.id = o.optString("id");
            b.uri = o.optString("uri", null);
            b.title = o.optString("title", null);
            b.author = o.optString("author", null);
            b.format = o.optString("format", "");
            b.pos = o.optString("pos", null);
            b.size = o.optLong("size");
            b.added = o.optLong("added");
            b.opened = o.optLong("opened");
            b.progress = (float) o.optDouble("progress", 0);
            b.pages = o.optInt("pages");
            b.mode = o.optInt("mode", -1);
            b.dir = o.optInt("dir", -1);
            b.fit = o.optInt("fit", -1);
            b.invert = o.optInt("invert", -1);
            b.folder = o.optBoolean("folder");
            b.marks = o.optJSONArray("marks");
            return b;
        }
    }

    private static Library instance;
    private final File file;
    private final LinkedHashMap<String, Book> books = new LinkedHashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    static synchronized Library get(Context c) {
        if (instance == null) instance = new Library(c.getApplicationContext());
        return instance;
    }

    private Library(Context c) {
        file = new File(c.getFilesDir(), "library.json");
        try (FileInputStream in = new FileInputStream(file)) {
            JSONArray a = new JSONArray(new String(Text.readAll(in, 32 << 20), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                Book b = Book.of(a.getJSONObject(i));
                if (b.id != null && !b.id.isEmpty()) books.put(b.id, b);
            }
        } catch (Exception ignored) {
            // first start or unreadable file: start empty
        }
    }

    static String idFor(String name, long size) { return Text.sha1(name + ":" + size); }

    synchronized Book find(String id) { return books.get(id); }

    /** Entry with this document URI, or null. */
    synchronized Book findByUri(String uri) {
        if (uri == null) return null;
        for (Book b : books.values()) if (uri.equals(b.uri)) return b;
        return null;
    }

    synchronized List<Book> all() {
        List<Book> l = new ArrayList<>(books.values());
        Collections.sort(l, (a, b) -> {
            if (a.opened != b.opened) return Long.compare(b.opened, a.opened);
            return org.recto.reader.util.Natural.cmp(a.title == null ? "" : a.title, b.title == null ? "" : b.title);
        });
        return l;
    }

    synchronized void put(Book b) {
        books.put(b.id, b);
        save();
    }

    synchronized void remove(String id) {
        books.remove(id);
        save();
    }

    synchronized void putAll(List<Book> list, List<String> removeIds) {
        for (String id : removeIds) books.remove(id);
        for (Book b : list) books.put(b.id, b);
        save();
    }

    private boolean saveQueued;

    /**
     * Writes asynchronously and atomically (temp file + rename). The JSON is built on the I/O
     * thread from a snapshot, and saves requested while one is queued are merged into it, so the
     * caller (usually the UI thread) never pays for serializing a large library.
     */
    synchronized void save() {
        if (saveQueued) return;
        saveQueued = true;
        io.execute(this::write);
    }

    private void write() {
        List<Book> snapshot;
        synchronized (this) {
            saveQueued = false;
            snapshot = new ArrayList<>(books.size());
            for (Book b : books.values()) snapshot.add(b.copy());
        }
        final String data;
        try {
            JSONArray a = new JSONArray();
            for (Book b : snapshot) a.put(b.json());
            data = a.toString();
        } catch (Exception e) {
            return;
        }
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(file)) tmp.delete();
    }
}
