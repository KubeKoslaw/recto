package org.recto.reader.shelf;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

/** One book standing on the shelf: a spine, or a cover with a page edge and a bookmark ribbon. */
public final class BookDraw {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect src = new Rect();
    private final RectF face = new RectF();
    private float density = 1f;

    public BookDraw() {
        text.setTypeface(Typeface.create(Typeface.SERIF, Typeface.NORMAL));
        text.setTextAlign(Paint.Align.LEFT);
    }

    public void setDensity(float density) { this.density = density <= 0 ? 1f : density; }

    /**
     * Draws into {@code r} (the body). The ribbon may extend a few pixels above {@code r}.
     * {@code cover} is used only for a face; a missing cover gets a paper board and the title.
     */
    public void draw(Canvas c, RectF r, boolean face, String title, int color, Bitmap cover, float progress) {
        String name = title == null || title.isEmpty() ? "?" : title;
        float d = density;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x33000000);
        c.drawRoundRect(r.left + 2 * d, r.top + 3 * d, r.right + d, r.bottom + 2 * d, 2 * d, 2 * d, paint);
        if (face) drawCover(c, r, name, cover, d);
        else drawSpine(c, r, name, color, d);
        if (progress > 0.004f) drawRibbon(c, r, progress, d);
    }

    private void drawSpine(Canvas c, RectF r, String title, int color, float d) {
        paint.setColor(color);
        c.drawRoundRect(r, 2 * d, 2 * d, paint);
        paint.setColor(0x40000000);
        c.drawRect(r.right - 3 * d, r.top + d, r.right, r.bottom - d, paint);
        text.setColor(ink(color));
        text.setTextSize(11 * d);
        float max = r.height() - 16 * d;
        String shown = fit(title, max);
        c.save();
        c.clipRect(r);
        c.rotate(-90, r.centerX(), r.centerY());
        float tw = text.measureText(shown);
        c.drawText(shown, r.centerX() - tw / 2f, r.centerY() + text.getTextSize() * 0.32f, text);
        c.restore();
    }

    private void drawCover(Canvas c, RectF r, String title, Bitmap cover, float d) {
        float edge = 5 * d;
        face.set(r.left, r.top, r.right - edge, r.bottom);
        paint.setColor(0xfff6f0e6);
        c.drawRoundRect(r, 3 * d, 3 * d, paint);
        paint.setColor(0xffe4d5c0);
        c.drawRect(r.right - edge, r.top + 2 * d, r.right - d, r.bottom - 2 * d, paint);
        paint.setColor(0xffcbb89a);
        c.drawRect(r.right - edge, r.top + 2 * d, r.right - edge + d, r.bottom - 2 * d, paint);
        c.save();
        c.clipRect(face);
        if (cover != null && !cover.isRecycled()) {
            int bw = cover.getWidth(), bh = cover.getHeight();
            float scale = Math.max(face.width() / bw, face.height() / bh);
            int sw = Math.max(1, Math.round(face.width() / scale));
            int sh = Math.max(1, Math.round(face.height() / scale));
            int sx = Math.max(0, (bw - sw) / 2);
            int sy = Math.max(0, (bh - sh) / 2);
            src.set(sx, sy, Math.min(bw, sx + sw), Math.min(bh, sy + sh));
            c.drawBitmap(cover, src, face, paint);
        } else {
            paint.setColor(0xfff7f1e6);
            c.drawRect(face, paint);
            text.setColor(0xff2a241c);
            text.setTextSize(12 * d);
            float max = face.width() - 10 * d;
            float y = face.top + 14 * d;
            int at = 0;
            for (int line = 0; line < 5 && at < title.length(); line++) {
                int n = text.breakText(title, at, title.length(), true, max, null);
                if (n <= 0) break;
                String row = title.substring(at, at + n);
                c.drawText(row, face.left + 5 * d, y, text);
                y += text.getTextSize() * 1.25f;
                at += n;
            }
        }
        c.restore();
    }

    private void drawRibbon(Canvas c, RectF r, float progress, float d) {
        float w = 6 * d;
        float x = r.centerX() - w / 2f;
        float top = r.top - 7 * d;
        float len = 12 * d + Math.max(0f, Math.min(1f, progress)) * r.height() * 0.45f;
        paint.setColor(0xff8c2f2f);
        c.drawRect(x, top, x + w, top + len, paint);
        paint.setColor(0xff5e1e1e);
        c.drawRect(x + w - d, top, x + w, top + len, paint);
    }

    private String fit(String title, float max) {
        if (text.measureText(title) <= max) return title;
        int n = text.breakText(title, true, Math.max(0, max - text.measureText("…")), null);
        if (n <= 0) return "";
        return title.substring(0, n) + "…";
    }

    private static int ink(int color) {
        int r = (color >> 16) & 255, g = (color >> 8) & 255, b = color & 255;
        return (r * 3 + g * 6 + b) / 10 > 170 ? 0xff1c1b1a : 0xfff7f4ee;
    }
}
