package org.recto.reader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.net.Uri;
import android.util.LruCache;

import org.recto.reader.fixed.PageSource;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small cover thumbnails stored as JPEG files, generated lazily on a background thread. */
final class Covers {
    private Covers() {}

    static final int HEIGHT = 240;
    private static final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(4 << 20) {
        @Override protected int sizeOf(String k, Bitmap v) { return v.getByteCount(); }
    };
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Set<String> pending = new HashSet<>();

    interface Done {
        void run(String id);
    }

    static File file(Context c, String id) { return new File(new File(c.getFilesDir(), "covers"), id + ".jpg"); }

    private static File none(Context c, String id) { return new File(new File(c.getFilesDir(), "covers"), id + ".none"); }

    /** Cached thumbnail or null; never blocks on generation. */
    static Bitmap cached(Context c, String id) {
        Bitmap b = memory.get(id);
        if (b != null) return b;
        File f = file(c, id);
        if (!f.isFile()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b != null) memory.put(id, b);
        return b;
    }

    static boolean known(Context c, String id) { return file(c, id).isFile() || none(c, id).isFile(); }

    static void save(Context c, String id, Bitmap src) {
        if (src == null) return;
        File f = file(c, id);
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory()) dir.mkdirs();
        float s = HEIGHT / (float) src.getHeight();
        Bitmap b = s < 1 ? Bitmap.createScaledBitmap(src, Math.max(1, Math.round(src.getWidth() * s)), HEIGHT, true) : src;
        try (FileOutputStream out = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.JPEG, 85, out);
        } catch (Exception ignored) {}
        memory.remove(id);
        if (b != src) b.recycle();
    }

    static Bitmap decode(byte[] data) {
        if (data == null) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outHeight <= 0) return null;
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (o.outHeight / (o.inSampleSize * 2) >= HEIGHT) o.inSampleSize *= 2;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    /** Thumbnail of an opened document (call off the UI thread). */
    static void fromDoc(Context c, Opener.Doc d) {
        if (file(c, d.id).isFile()) return;
        Bitmap b = null;
        try {
            if (d.book != null) b = decode(d.book.cover());
            else if (d.pages != null && d.pages.count() > 0) b = renderFirst(d.pages);
        } catch (Throwable ignored) {}
        if (b != null) {
            save(c, d.id, b);
            b.recycle();
        } else {
            try { none(c, d.id).getParentFile().mkdirs(); none(c, d.id).createNewFile(); } catch (Exception ignored) {}
        }
    }

    private static Bitmap renderFirst(PageSource s) throws Exception {
        float[] sz = new float[2];
        s.size(0, sz);
        float scale = Math.min(1f, HEIGHT / sz[1]);
        return s.render(0, scale, new Rect(0, 0, Math.max(1, Math.round(sz[0] * scale)), Math.max(1, Math.round(sz[1] * scale))), true);
    }

    /** Generates a missing thumbnail for a library entry by opening the file in the background. */
    static void request(Context ctx, Library.Book book, Done done) {
        final Context c = ctx.getApplicationContext();
        synchronized (pending) {
            if (!pending.add(book.id)) return;
        }
        worker.execute(() -> {
            Opener.Doc d = null;
            try {
                if (!known(c, book.id)) {
                    d = Opener.open(c, Uri.parse(book.uri), null);
                    d.id = book.id;
                    fromDoc(c, d);
                }
            } catch (Throwable e) {
                try { none(c, book.id).getParentFile().mkdirs(); none(c, book.id).createNewFile(); } catch (Exception ignored) {}
            } finally {
                if (d != null) d.close();
                synchronized (pending) { pending.remove(book.id); }
            }
            done.run(book.id);
        });
    }

    static void forget(Context c, String id) {
        memory.remove(id);
        file(c, id).delete();
        none(c, id).delete();
    }
}
