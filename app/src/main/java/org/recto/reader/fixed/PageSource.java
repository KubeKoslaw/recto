package org.recto.reader.fixed;

import android.graphics.Bitmap;
import android.graphics.Rect;

import java.io.Closeable;
import java.io.IOException;

/** A document made of fixed-size pages. All methods except {@link #count()} run on the render thread. */
public interface PageSource extends Closeable {
    int count();

    /** Page size in source units (PDF points, image pixels) into out[0], out[1]. */
    void size(int page, float[] out) throws IOException;

    /**
     * Renders {@code region}, given in pixels of the page scaled by {@code scale}. The bitmap may be
     * larger or smaller than the region; callers draw it scaled into the region.
     */
    Bitmap render(int page, float scale, Rect region, boolean lowMemory) throws IOException;

    /** True for scanned/photographic pages (comics), false for vector documents. */
    boolean isImages();
}
