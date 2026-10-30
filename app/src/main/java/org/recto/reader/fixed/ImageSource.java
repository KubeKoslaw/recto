package org.recto.reader.fixed;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;

import org.recto.reader.io.Archive;
import org.recto.reader.io.Seekable;
import org.recto.reader.util.Text;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Pages backed by encoded images (comic archives, fixed-layout EPUBs, single pictures).
 * Decodes with power-of-two subsampling and region decoding so memory stays close to screen size.
 */
public final class ImageSource implements PageSource {
    /** Supplies encoded image bytes per page. */
    public interface Pages {
        int count();

        byte[] load(int i) throws IOException;

        InputStream stream(int i) throws IOException;

        void close() throws IOException;
    }

    private final Pages pages;
    private final int count;
    // Small cache of encoded bytes plus the region decoder of the most recent page.
    private int dataPage = -1, dataPage2 = -1;
    private byte[] data, data2;
    private int regionPage = -1;
    private BitmapRegionDecoder region;

    public ImageSource(Pages pages) {
        this.pages = pages;
        this.count = pages.count();
    }

    @Override public int count() { return count; }

    @Override public boolean isImages() { return true; }

    @Override public synchronized void size(int i, float[] out) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        if (i == dataPage || i == dataPage2) {
            byte[] d = bytes(i);
            BitmapFactory.decodeByteArray(d, 0, d.length, o);
        } else {
            try (InputStream in = pages.stream(i)) {
                BitmapFactory.decodeStream(in, null, o);
            }
        }
        if (o.outWidth <= 0 || o.outHeight <= 0) throw new IOException("Unsupported image");
        out[0] = o.outWidth;
        out[1] = o.outHeight;
    }

    private byte[] bytes(int i) throws IOException {
        if (i == dataPage) return data;
        if (i == dataPage2) {
            byte[] t = data;
            int tp = dataPage;
            data = data2;
            dataPage = dataPage2;
            data2 = t;
            dataPage2 = tp;
            return data;
        }
        byte[] d = pages.load(i);
        data2 = data;
        dataPage2 = dataPage;
        data = d;
        dataPage = i;
        return d;
    }

    @Override public synchronized Bitmap render(int i, float scale, Rect r, boolean lowMemory) throws IOException {
        byte[] d = bytes(i);
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(d, 0, d.length, b);
        int w = b.outWidth, h = b.outHeight;
        if (w <= 0 || h <= 0) throw new IOException("Unsupported image");
        // Source rectangle in image pixels.
        Rect src = new Rect((int) Math.floor(r.left / scale), (int) Math.floor(r.top / scale),
                (int) Math.ceil(r.right / scale), (int) Math.ceil(r.bottom / scale));
        if (!src.intersect(0, 0, w, h)) throw new IOException("Empty region");
        int sample = 1;
        while (scale * sample * 2 <= 1.001f) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = lowMemory ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
        o.inDither = lowMemory;
        Bitmap bmp = null;
        boolean full = src.left == 0 && src.top == 0 && src.right == w && src.bottom == h;
        if (!full) {
            try {
                if (regionPage != i || region == null) {
                    if (region != null) region.recycle();
                    region = null;
                    regionPage = i;
                    region = newRegionDecoder(d);
                }
                bmp = region.decodeRegion(src, o);
            } catch (IOException | RuntimeException e) {
                bmp = null; // GIF/BMP etc.: fall back to a full decode and crop
            }
        }
        if (bmp == null) {
            Bitmap all = BitmapFactory.decodeByteArray(d, 0, d.length, o);
            if (all == null) throw new IOException("Cannot decode image");
            if (full) {
                bmp = all;
            } else {
                int s = Math.max(1, Math.round((float) w / all.getWidth()));
                Rect c = new Rect(src.left / s, src.top / s, Math.min(all.getWidth(), src.right / s), Math.min(all.getHeight(), src.bottom / s));
                bmp = Bitmap.createBitmap(all, c.left, c.top, Math.max(1, c.width()), Math.max(1, c.height()));
                if (bmp != all) all.recycle();
            }
        }
        if (bmp == null) throw new IOException("Cannot decode image");
        // Subsampling only works in powers of two: trim what is clearly more than needed.
        int tw = Math.max(1, r.width()), th = Math.max(1, r.height());
        if (bmp.getWidth() > tw * 1.4f && bmp.getHeight() > th * 1.4f) {
            Bitmap s = Bitmap.createScaledBitmap(bmp, tw, th, true);
            if (s != bmp) bmp.recycle();
            bmp = s;
        }
        return bmp;
    }

    @SuppressWarnings("deprecation")
    private static BitmapRegionDecoder newRegionDecoder(byte[] d) throws IOException {
        return BitmapRegionDecoder.newInstance(d, 0, d.length, false);
    }

    @Override public synchronized void close() throws IOException {
        if (region != null) region.recycle();
        region = null;
        data = data2 = null;
        pages.close();
    }

    /** Pages from image entries of an archive. */
    public static final class ArchivePages implements Pages {
        private final Archive archive;
        private final List<? extends Archive.Entry> entries;

        public ArchivePages(Archive archive, List<? extends Archive.Entry> entries) {
            this.archive = archive;
            this.entries = entries;
        }

        @Override public int count() { return entries.size(); }

        @Override public byte[] load(int i) throws IOException { return archive.read(entries.get(i), 256L << 20); }

        @Override public InputStream stream(int i) throws IOException { return archive.open(entries.get(i)); }

        @Override public void close() throws IOException { archive.close(); }
    }

    /** A single picture file. */
    public static final class SinglePage implements Pages {
        private final Seekable src;

        public SinglePage(Seekable src) { this.src = src; }

        @Override public int count() { return 1; }

        @Override public byte[] load(int i) throws IOException {
            long n = src.size();
            if (n > 256L << 20) throw new IOException("Image too large");
            byte[] b = new byte[(int) n];
            src.readFully(0, b, 0, b.length);
            return b;
        }

        @Override public InputStream stream(int i) throws IOException {
            return new org.recto.reader.io.SliceStream(src, 0, src.size());
        }

        @Override public void close() throws IOException { Text.close(src); }
    }
}
