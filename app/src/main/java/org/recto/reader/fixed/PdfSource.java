package org.recto.reader.fixed;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.pdf.LoadParams;
import android.graphics.pdf.PdfRenderer;
import android.graphics.pdf.content.PdfPageGotoLinkContent;
import android.graphics.pdf.content.PdfPageLinkContent;
import android.graphics.pdf.content.PdfPageTextContent;
import android.graphics.pdf.models.PageMatchBounds;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** PDF pages through the platform renderer (PDFium) - no bundled native code. */
public final class PdfSource implements PageSource {
    private final PdfRenderer pdf;
    private final int count;
    private PdfRenderer.Page page;
    private final Matrix matrix = new Matrix();

    public PdfSource(ParcelFileDescriptor fd, String password) throws IOException {
        if (password != null && Build.VERSION.SDK_INT >= 35) pdf = V35.open(fd, password);
        else pdf = new PdfRenderer(fd);
        count = pdf.getPageCount();
    }

    /** Password-protected documents can only be opened on Android 15+. */
    public static boolean canOpenEncrypted() { return Build.VERSION.SDK_INT >= 35; }

    private PdfRenderer.Page open(int i) {
        if (page != null) {
            if (page.getIndex() == i) return page;
            page.close();
            page = null;
        }
        page = pdf.openPage(i);
        return page;
    }

    @Override public int count() { return count; }

    @Override public synchronized void size(int i, float[] out) {
        PdfRenderer.Page p = open(i);
        out[0] = Math.max(1, p.getWidth());
        out[1] = Math.max(1, p.getHeight());
    }

    @Override public synchronized Bitmap render(int i, float scale, Rect r, boolean lowMemory) {
        PdfRenderer.Page p = open(i);
        Bitmap b = Bitmap.createBitmap(Math.max(1, r.width()), Math.max(1, r.height()), Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.WHITE);
        matrix.setScale(scale, scale);
        matrix.postTranslate(-r.left, -r.top);
        p.render(b, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        return b;
    }

    @Override public boolean isImages() { return false; }

    public static boolean canSearch() { return Build.VERSION.SDK_INT >= 35; }

    /** Match rectangles (in PDF points) of {@code query} on a page. Android 15+. */
    public synchronized List<RectF> search(int i, String query) { return V35.search(open(i), query); }

    /** Plain text of a page (for search snippets). Android 15+. */
    public synchronized String text(int i) { return V35.text(open(i)); }

    /** Links of a page as {RectF in PDF points, Integer target page | String URI}. Android 15+. */
    public synchronized List<Object[]> links(int i) { return V35.links(open(i)); }

    /** Android 15 PdfRenderer features, isolated so older devices never load these classes. */
    @TargetApi(35)
    private static final class V35 {
        static PdfRenderer open(ParcelFileDescriptor fd, String password) throws IOException {
            return new PdfRenderer(fd, new LoadParams.Builder().setPassword(password).build());
        }

        static List<RectF> search(PdfRenderer.Page p, String query) {
            List<RectF> out = new ArrayList<>();
            for (PageMatchBounds m : p.searchText(query)) out.addAll(m.getBounds());
            return out;
        }

        static String text(PdfRenderer.Page p) {
            StringBuilder sb = new StringBuilder();
            for (PdfPageTextContent t : p.getTextContents()) sb.append(t.getText()).append(' ');
            return sb.toString();
        }

        static List<Object[]> links(PdfRenderer.Page p) {
            List<Object[]> out = new ArrayList<>();
            for (PdfPageGotoLinkContent l : p.getGotoLinks())
                for (RectF r : l.getBounds()) out.add(new Object[]{r, l.getDestination().getPageNumber()});
            for (PdfPageLinkContent l : p.getLinkContents())
                for (RectF r : l.getBounds()) out.add(new Object[]{r, l.getUri().toString()});
            return out;
        }
    }

    @Override public synchronized void close() {
        if (page != null) page.close();
        page = null;
        pdf.close();
    }
}
