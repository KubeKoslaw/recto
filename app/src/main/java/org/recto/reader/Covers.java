package org.recto.reader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.net.Uri;
import android.util.LruCache;

import org.recto.reader.fixed.PageSource;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small cover thumbnails stored as JPEG files. Decoding and generation happen on background
 * threads; the UI thread only ever touches the in-memory cache.
 */
final class Covers {
    private Covers() {}

    static final int HEIGHT = 240;
    private static final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(8 << 20) {
        @Override protected int sizeOf(String k, Bitmap v) { return v.getByteCount(); }
    };
    /** Decodes stored thumbnails (fast). */
    private static final ExecutorService decoder = Executors.newSingleThreadExecutor();
    /** Opens documents to make missing thumbnails (slow). */
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Set<String> pending = new HashSet<>();
    /** Thumbnails that could not be made now for a passing reason; retried on the next app start. */
    private static final Set<String> skipped = Collections.synchronizedSet(new HashSet<>());
    /** Formats that never have a cover picture: no need to open (and convert) them. */
    private static final Set<String> COVERLESS = new HashSet<>(Arrays.asList("TXT", "MD", "MARKDOWN", "HTML", "HTM", "XHTML", "DOCX", "ODT"));

    interface Loaded {
        /** Called on a background thread; {@code b} is null if there is no thumbnail. */
        void loaded(String id, Bitmap b);
    }

    static File file(Context c, String id) { return new File(new File(c.getFilesDir(), "covers"), id + ".jpg"); }

    private static File none(Context c, String id) { return new File(new File(c.getFilesDir(), "covers"), id + ".none"); }

    /** Thumbnail from the memory cache, or null. Never touches the disk (safe on the UI thread). */
    static Bitmap cached(String id) { return memory.get(id); }

    /**
     * Loads a thumbnail in the background: from its file, or by generating it (if {@code book}
     * has a URI and no thumbnail is known yet). The callback runs once the result is known.
     */
    static void load(Context ctx, Library.Book book, Loaded done) {
        final Context c = ctx.getApplicationContext();
        final String id = book.id;
        decoder.execute(() -> {
            Bitmap b = fromFile(c, id);
            if (b != null || book.uri == null || none(c, id).isFile() || skipped.contains(id)) {
                done.loaded(id, b);
                return;
            }
            request(c, book, done);
        });
    }

    private static Bitmap fromFile(Context c, String id) {
        Bitmap b = memory.get(id);
        if (b != null) return b;
        File f = file(c, id);
        if (!f.isFile()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b != null) memory.put(id, b);
        else f.delete(); // unreadable: make it again
        return b;
    }

    private static boolean known(Context c, String id) { return file(c, id).isFile() || none(c, id).isFile(); }

    private static void markNone(Context c, String id) {
        try {
            File n = none(c, id);
            File dir = n.getParentFile();
            if (dir != null) dir.mkdirs();
            n.createNewFile();
        } catch (Exception ignored) {}
    }

    /** Writes atomically (temp file + rename), so a crash never leaves a broken thumbnail. */
    static void save(Context c, String id, Bitmap src) {
        if (src == null) return;
        File f = file(c, id);
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory()) dir.mkdirs();
        float s = HEIGHT / (float) src.getHeight();
        Bitmap b = s < 1 ? Bitmap.createScaledBitmap(src, Math.max(1, Math.round(src.getWidth() * s)), HEIGHT, true) : src;
        File tmp = new File(f.getPath() + ".tmp");
        boolean ok = false;
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            ok = b.compress(Bitmap.CompressFormat.JPEG, 85, out);
        } catch (Exception ignored) {}
        if (!ok || !tmp.renameTo(f)) tmp.delete();
        memory.remove(id);
        if (b != src) b.recycle();
    }

    static Bitmap decode(byte[] data) {
        if (data == null || data.length == 0) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outHeight <= 0) return null;
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (o.outHeight / (o.inSampleSize * 2) >= HEIGHT) o.inSampleSize *= 2;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    /**
     * Thumbnail of an opened document (call off the UI thread). "No cover" is only recorded when
     * the document really has none, not when reading it failed (e.g. it was closed meanwhile).
     */
    static void fromDoc(Context c, Opener.Doc d) {
        if (file(c, d.id).isFile()) return;
        Bitmap b;
        try {
            if (d.book != null) b = decode(d.book.cover());
            else if (d.pages != null && d.pages.count() > 0) b = renderFirst(d.pages);
            else b = null;
        } catch (Throwable e) {
            return;
        }
        if (b != null) {
            save(c, d.id, b);
            b.recycle();
        } else {
            markNone(c, d.id);
        }
    }

    private static Bitmap renderFirst(PageSource s) throws Exception {
        float[] sz = new float[2];
        s.size(0, sz);
        float scale = Math.min(1f, HEIGHT / sz[1]);
        return s.render(0, scale, new Rect(0, 0, Math.max(1, Math.round(sz[0] * scale)), Math.max(1, Math.round(sz[1] * scale))), true);
    }

    /** Generates a missing thumbnail for a library entry in the background. */
    private static void request(Context c, Library.Book book, Loaded done) {
        synchronized (pending) {
            if (!pending.add(book.id)) return;
        }
        worker.execute(() -> {
            Opener.Doc d = null;
            try {
                if (!known(c, book.id)) generate(c, book);
            } catch (Source.NotSeekable | FileNotFoundException | SecurityException | OutOfMemoryError e) {
                skipped.add(book.id); // cloud stream, missing permission, low memory: try again later
            } catch (Opener.Failure e) {
                if (e.message == R.string.err_memory) skipped.add(book.id); else markNone(c, book.id);
            } catch (Throwable e) {
                markNone(c, book.id); // unsupported or broken document
            } finally {
                synchronized (pending) { pending.remove(book.id); }
            }
            done.loaded(book.id, fromFile(c, book.id));
        });
    }

    private static void generate(Context c, Library.Book book) throws Exception {
        String format = book.format == null ? "" : book.format.toUpperCase(java.util.Locale.ROOT);
        if (COVERLESS.contains(format)) {
            markNone(c, book.id);
            return;
        }
        Uri uri = Uri.parse(book.uri);
        byte[] quick = Opener.quickCover(c, uri);
        if (quick != Opener.NO_QUICK_COVER) {
            Bitmap b = decode(quick);
            if (b == null) markNone(c, book.id);
            else {
                save(c, book.id, b);
                b.recycle();
            }
            return;
        }
        // Never download a streamed (cloud) file just for a thumbnail: it is made when the book is opened.
        Opener.Doc d = Opener.open(c, uri, null, false);
        try {
            d.id = book.id;
            fromDoc(c, d);
            if (!known(c, book.id)) skipped.add(book.id); // reading failed: not again in this run
        } finally {
            d.close();
        }
    }

    static void forget(Context c, String id) {
        memory.remove(id);
        skipped.remove(id);
        file(c, id).delete();
        none(c, id).delete();
    }
}
