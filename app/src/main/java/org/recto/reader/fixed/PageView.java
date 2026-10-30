package org.recto.reader.fixed;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Fixed-layout page viewer: paged (LTR / RTL) or continuous vertical scrolling, pinch zoom.
 * Pages are rendered on one background thread at exactly the on-screen resolution; tall pages are
 * split into bands and, when zoomed in, the visible part is re-rendered sharp ("patch").
 */
public final class PageView extends View {
    public interface Listener {
        void onPageChanged(int page);

        void onCenterTap();

        /** Tap on a page point (source units); return true if consumed (e.g. a link). */
        boolean onPageTap(int page, float x, float y);
    }

    private static final int BAND = 2048;

    private final float density;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint fill = new Paint();
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final OverScroller scroller;
    private final GestureDetector gestures;
    private final ScaleGestureDetector scaler;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final DecelerateInterpolator decel = new DecelerateInterpolator(1.6f);

    private Listener listener;
    private PageSource src;
    private Worker worker;
    private int count;
    private float[] pw = new float[0], ph = new float[0];
    private boolean[] known = new boolean[0];
    private float[] utop = new float[1];

    private boolean continuous, rtl, fitWidth, animate = true, tapZones = true, lowMem;
    private int bg = Color.BLACK;
    private float gapUnits;

    private int vw, vh, cur;
    private float zoom = 1f, sx, sy, dragX, flingVx;
    private boolean pageDrag, panStartedAtEdgeL, panStartedAtEdgeR, idle, scaling;
    private ValueAnimator anim;
    private int highlightPage = -1;
    private float pendingOffset;
    private List<RectF> highlights;

    private final LinkedHashMap<Long, Tile> cache = new LinkedHashMap<>(32, 0.75f, true);
    private final ArrayList<Tile> patches = new ArrayList<>();
    private final Set<Long> inflight = Collections.synchronizedSet(new HashSet<>());
    private final Set<Long> failed = new HashSet<>();
    /** Tiles the current view needs (visible + prefetch); never evicted. */
    private final Set<Long> wanted = new HashSet<>();
    private long wantedBytes;
    private long cacheBytes;
    private final long cacheLimit;
    private final RectF tmp = new RectF(), tmp2 = new RectF();

    private static final class Tile {
        final int page;
        final float scale;
        final Rect region;
        final Bitmap bmp;

        Tile(int page, float scale, Rect region, Bitmap bmp) {
            this.page = page;
            this.scale = scale;
            this.region = region;
            this.bmp = bmp;
        }
    }

    private static final class Job {
        static final int BASE = 0, PATCH = 1, SIZES = 2;
        final int type, page, to;
        final float scale;
        final Rect region;
        final long key;

        Job(int type, int page, int to, float scale, Rect region, long key) {
            this.type = type;
            this.page = page;
            this.to = to;
            this.scale = scale;
            this.region = region;
            this.key = key;
        }
    }

    public PageView(Context c) {
        super(c);
        density = getResources().getDisplayMetrics().density;
        scroller = new OverScroller(c);
        gestures = new GestureDetector(c, new Gestures());
        gestures.setIsLongpressEnabled(false);
        scaler = new ScaleGestureDetector(c, new Scales());
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        int mb = am != null ? am.getMemoryClass() : 64;
        cacheLimit = Math.max(16, Math.min(64, mb / 4)) * 1024L * 1024L;
        mark.setColor(0x55ffd600);
        setFocusable(true);
    }

    // ---- configuration -------------------------------------------------------------------

    public void setListener(Listener l) { listener = l; }

    /** Attaches an opened source; firstW/firstH is the (already known) size of the start page. */
    public void setSource(PageSource s, int page, float offset, float firstW, float firstH) {
        src = s;
        count = s.count();
        pw = new float[count];
        ph = new float[count];
        known = new boolean[count];
        for (int i = 0; i < count; i++) {
            pw[i] = firstW;
            ph[i] = firstH;
        }
        cur = clampPage(page);
        if (count > 0) known[cur] = true;
        gapUnits = s.isImages() ? 0 : 0.012f;
        worker = new Worker();
        worker.start();
        pendingOffset = offset;
        relayout(cur, offset);
    }

    public void setOptions(boolean continuous, boolean rtl, boolean fitWidth, boolean invert, boolean animate,
                           boolean tapZones, boolean lowMemory, int background) {
        int page = cur;
        float off = continuous ? pageOffset() : 0;
        boolean layoutChanged = this.continuous != continuous || this.fitWidth != fitWidth;
        this.continuous = continuous;
        this.rtl = rtl;
        this.fitWidth = fitWidth;
        this.animate = animate;
        this.tapZones = tapZones;
        this.bg = background;
        if (this.lowMem != lowMemory) {
            this.lowMem = lowMemory;
            clearCache();
        }
        if (invert) {
            ColorMatrix m = new ColorMatrix(new float[]{-1, 0, 0, 0, 255, 0, -1, 0, 0, 255, 0, 0, -1, 0, 255, 0, 0, 0, 1, 0});
            ColorMatrix hue = new ColorMatrix(); // rotate hue back so colours keep their tint
            float c = -1f, s = 0f, lr = 0.213f, lg = 0.715f, lb = 0.072f;
            hue.set(new float[]{
                    lr + c * (1 - lr) + s * (-lr), lg + c * (-lg) + s * (-lg), lb + c * (-lb) + s * (1 - lb), 0, 0,
                    lr + c * (-lr) + s * 0.143f, lg + c * (1 - lg) + s * 0.140f, lb + c * (-lb) + s * (-0.283f), 0, 0,
                    lr + c * (-lr) + s * (-(1 - lr)), lg + c * (-lg) + s * lg, lb + c * (1 - lb) + s * lb, 0, 0,
                    0, 0, 0, 1, 0});
            m.postConcat(hue);
            paint.setColorFilter(new ColorMatrixColorFilter(m));
        } else {
            paint.setColorFilter(null);
        }
        if (layoutChanged) {
            zoom = 1;
            sx = sy = 0;
            patches.clear();
        }
        relayout(page, off);
    }

    public int getPage() { return cur; }

    public int getCount() { return count; }

    /** Offset inside the current page in continuous mode, 0..1. */
    public float pageOffset() {
        if (!continuous || count == 0 || vw == 0) return 0;
        float h = pageH(cur);
        return h <= 0 ? 0 : Math.max(0, Math.min(1, (sy - top(cur)) / h));
    }

    public void goTo(int page, float offset) {
        stopMotion();
        page = clampPage(page);
        if (continuous) {
            sy = top(page) + pageH(page) * offset;
            clampScroll();
        } else {
            setPage(page, false);
        }
        updateCurrent();
        invalidate();
        schedule();
    }

    public void highlight(int page, List<RectF> rects) {
        highlightPage = page;
        highlights = rects;
        invalidate();
    }

    public void release() {
        if (worker != null) worker.quit();
        worker = null;
        clearCache();
    }

    public void trimMemory() {
        Iterator<Tile> it = cache.values().iterator();
        while (it.hasNext()) {
            Tile t = it.next();
            if (!isVisible(t.page)) {
                cacheBytes -= t.bmp.getByteCount();
                it.remove();
            }
        }
        patches.clear();
    }

    private void clearCache() {
        cache.clear();
        patches.clear();
        failed.clear();
        cacheBytes = 0;
    }

    // ---- geometry -----------------------------------------------------------------------------

    private int clampPage(int p) { return Math.max(0, Math.min(count - 1, p)); }

    /** Scale (px per source unit) of page i at zoom 1. */
    private float baseScale(int i) {
        if (continuous || fitWidth) return vw / pw[i];
        return Math.min(vw / pw[i], vh / ph[i]);
    }

    private float top(int i) { return utop[i] * vw * zoom; }

    private float pageH(int i) { return ph[i] / pw[i] * vw * zoom; }

    private float docH() { return utop[count] * vw * zoom - gapUnits * vw * zoom; }

    private void computeTops() {
        if (utop.length != count + 1) utop = new float[count + 1];
        float y = 0;
        for (int i = 0; i < count; i++) {
            utop[i] = y;
            y += ph[i] / pw[i] + gapUnits;
        }
        utop[count] = y;
    }

    private int pageAtY(float y) {
        int lo = 0, hi = count - 1;
        float u = y / (vw * zoom);
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (utop[mid] <= u) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    /** On-screen rectangle of a page in paged mode with the given pan state. */
    private void pagedRect(int i, float z, float px, float py, float shift, RectF out) {
        float s = baseScale(i) * z, w = pw[i] * s, h = ph[i] * s;
        float l = w <= vw ? (vw - w) / 2 : -px, t = h <= vh ? (vh - h) / 2 : -py;
        out.set(l + shift, t, l + shift + w, t + h);
    }

    private void contRect(int i, RectF out) {
        float w = vw * zoom, t = top(i) - sy, l = w <= vw ? (vw - w) / 2 : -sx;
        out.set(l, t, l + w, t + pageH(i));
    }

    private float maxSx() {
        if (count == 0) return 0;
        float w = continuous ? vw * zoom : pw[cur] * baseScale(cur) * zoom;
        return Math.max(0, w - vw);
    }

    private float maxSy() {
        if (count == 0) return 0;
        if (continuous) return Math.max(0, docH() - vh);
        return Math.max(0, ph[cur] * baseScale(cur) * zoom - vh);
    }

    private void clampScroll() {
        sx = Math.max(0, Math.min(maxSx(), sx));
        sy = Math.max(0, Math.min(maxSy(), sy));
    }

    private boolean isVisible(int page) {
        if (count == 0 || vw == 0) return false;
        if (!continuous) return page == cur || (dragX != 0 && Math.abs(page - cur) == 1);
        return page >= pageAtY(sy) && page <= pageAtY(sy + vh);
    }

    private void relayout(int page, float offset) {
        if (count == 0 || vw == 0) {
            invalidate();
            return;
        }
        computeTops();
        cur = clampPage(page);
        if (continuous) sy = top(cur) + pageH(cur) * offset;
        clampScroll();
        invalidate();
        schedule();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        int page = cur;
        float off = vw == 0 ? pendingOffset : pageOffset();
        vw = w;
        vh = h;
        patches.clear();
        relayout(page, off);
    }

    private void updateCurrent() {
        if (count == 0 || vw == 0) return;
        int p = continuous ? pageAtY(sy + vh / 2f) : cur;
        if (p != cur) {
            cur = p;
            if (listener != null) listener.onPageChanged(cur);
        }
    }

    // ---- navigation -----------------------------------------------------------------------

    private void setPage(int page, boolean atEnd) {
        page = clampPage(page);
        boolean changed = page != cur;
        cur = page;
        zoom = 1;
        sx = rtl ? Float.MAX_VALUE : 0;
        sy = atEnd ? Float.MAX_VALUE : 0;
        clampScroll();
        dragX = 0;
        patches.clear();
        if (changed && listener != null) listener.onPageChanged(cur);
        invalidate();
        schedule();
    }

    /** Next page in reading order (or scroll down within a tall page). */
    public void next() { step(1); }

    public void prev() { step(-1); }

    private void step(int dir) {
        if (count == 0 || vw == 0) return;
        finishAnim();
        scroller.forceFinished(true);
        if (continuous) {
            float target = Math.max(0, Math.min(maxSy(), sy + dir * vh * 0.9f));
            animateScroll(target);
            return;
        }
        float my = maxSy();
        if (dir > 0 && sy < my - 1) {
            animateScroll(Math.min(my, sy + vh * 0.9f));
            return;
        }
        if (dir < 0 && sy > 1) {
            animateScroll(Math.max(0, sy - vh * 0.9f));
            return;
        }
        int target = cur + dir;
        if (target < 0 || target >= count) return;
        // In RTL the next page comes from the left, i.e. content slides right.
        float to = (dir > 0) != rtl ? -vw : vw;
        if (!animate) {
            setPage(target, dir < 0 && fitWidth);
            return;
        }
        animateDrag(to, target, dir < 0 && fitWidth);
    }

    private void animateScroll(float target) {
        final float from = sy;
        if (!animate) {
            sy = target;
            afterMotion();
            return;
        }
        anim = ValueAnimator.ofFloat(0, 1);
        anim.setDuration(200);
        anim.setInterpolator(decel);
        anim.addUpdateListener(a -> {
            sy = from + (target - from) * (float) a.getAnimatedValue();
            clampScroll();
            updateCurrent();
            invalidate();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                anim = null;
                afterMotion();
            }
        });
        setIdle(false);
        anim.start();
    }

    private void animateDrag(float to, int targetPage, boolean atEnd) {
        final float from = dragX;
        anim = ValueAnimator.ofFloat(0, 1);
        anim.setDuration((long) (90 + 160 * Math.min(1, Math.abs(to - from) / Math.max(1, vw))));
        anim.setInterpolator(decel);
        anim.addUpdateListener(a -> {
            dragX = from + (to - from) * (float) a.getAnimatedValue();
            invalidate();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            boolean done;

            @Override public void onAnimationEnd(Animator a) {
                if (done) return;
                done = true;
                anim = null;
                if (targetPage >= 0) setPage(targetPage, atEnd);
                else dragX = 0;
                afterMotion();
            }
        });
        setIdle(false);
        anim.start();
        schedule();
    }

    private void finishAnim() {
        if (anim != null) {
            ValueAnimator a = anim;
            anim = null;
            a.end();
        }
    }

    private void stopMotion() {
        finishAnim();
        scroller.forceFinished(true);
        dragX = 0;
        pageDrag = false;
    }

    private void afterMotion() {
        clampScroll();
        updateCurrent();
        invalidate();
        setIdle(false);
        ui.removeCallbacks(goIdle);
        ui.postDelayed(goIdle, 140);
    }

    private final Runnable goIdle = () -> setIdle(true);

    private void setIdle(boolean v) {
        if (idle == v) return;
        idle = v;
        if (v) schedule();
    }

    /** Zoom to {@code target} keeping the point (fx, fy) fixed. */
    private void zoomAround(float target, float fx, float fy) {
        float nz = Math.max(1f, Math.min(maxZoom(), target));
        float f = nz / zoom;
        if (f == 1f) return;
        if (continuous) {
            float cx = vw * zoom <= vw ? (vw - vw * zoom) / 2 : -sx;
            sx = -(fx - (fx - cx) * f);
            sy = (sy + fy) * f - fy;
            zoom = nz;
            computeTops();
        } else {
            pagedRect(cur, zoom, sx, sy, 0, tmp);
            float l = fx - (fx - tmp.left) * f, t = fy - (fy - tmp.top) * f;
            zoom = nz;
            sx = -l;
            sy = -t;
        }
        clampScroll();
        updateCurrent();
        invalidate();
    }

    private float maxZoom() {
        if (count == 0) return 1;
        if (src != null && src.isImages()) return Math.max(3f, 2f / baseScale(cur));
        return 8f;
    }

    private void animateZoom(float target, float fx, float fy) {
        finishAnim();
        final float from = zoom;
        anim = ValueAnimator.ofFloat(0, 1);
        anim.setDuration(animate ? 220 : 0);
        anim.setInterpolator(decel);
        anim.addUpdateListener(a -> zoomAround(from + (target - from) * (float) a.getAnimatedValue(), fx, fy));
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                anim = null;
                if (zoom <= 1.01f) patches.clear();
                afterMotion();
            }
        });
        setIdle(false);
        anim.start();
    }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            sx = scroller.getCurrX();
            sy = scroller.getCurrY();
            clampScroll();
            updateCurrent();
            postInvalidateOnAnimation();
            if (scroller.isFinished()) afterMotion();
            else schedule();
        }
    }

    // ---- touch ----------------------------------------------------------------------------------

    @Override public boolean onTouchEvent(MotionEvent e) {
        scaler.onTouchEvent(e);
        gestures.onTouchEvent(e);
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) release(a == MotionEvent.ACTION_UP);
        return true;
    }

    private void release(boolean up) {
        if (pageDrag) {
            pageDrag = false;
            float th = vw * 0.18f;
            int dir = 0; // +1: reveal the page on the right, -1: on the left
            if (up && (dragX < -th || (flingVx < -600 * density && dragX < 0))) dir = 1;
            else if (up && (dragX > th || (flingVx > 600 * density && dragX > 0))) dir = -1;
            int target = dir == 0 ? -1 : (dir > 0) != rtl ? cur + 1 : cur - 1;
            if (target < 0 || target >= count) target = -1;
            boolean back = target >= 0 && target < cur;
            if (!animate) {
                dragX = 0;
                if (target >= 0) setPage(target, back && fitWidth);
                else invalidate();
                afterMotion();
            } else {
                animateDrag(target < 0 ? 0 : dir > 0 ? -vw : vw, target, back && fitWidth);
            }
        } else if (!scroller.isFinished()) {
            schedule();
        } else if (anim == null) {
            afterMotion();
        }
        flingVx = 0;
    }

    private final class Gestures extends GestureDetector.SimpleOnGestureListener {
        @Override public boolean onDown(MotionEvent e) {
            if (anim != null && dragX != 0) finishAnim();
            else if (anim != null) {
                anim.cancel();
                anim = null;
            }
            scroller.forceFinished(true);
            setIdle(false);
            flingVx = 0;
            float m = maxSx();
            panStartedAtEdgeL = sx <= 1;
            panStartedAtEdgeR = sx >= m - 1;
            return true;
        }

        @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
            if (count == 0 || scaling) return true;
            if (continuous) {
                sx += dx;
                sy += dy;
                clampScroll();
                updateCurrent();
                invalidate();
                schedule();
                return true;
            }
            if (pageDrag) {
                float nd = dragX - dx;
                boolean edge = (nd < 0 && ((!rtl && cur >= count - 1) || (rtl && cur <= 0)))
                        || (nd > 0 && ((!rtl && cur <= 0) || (rtl && cur >= count - 1)));
                dragX = edge ? dragX - dx * 0.3f : nd;
                if (animate) invalidate();
                return true;
            }
            float m = maxSx();
            float nsx = sx + dx;
            boolean horizontal = Math.abs(dx) > Math.abs(dy) * 0.8f;
            if (horizontal && ((nsx < 0 && panStartedAtEdgeL) || (nsx > m && panStartedAtEdgeR))) {
                pageDrag = true;
                dragX = -(nsx < 0 ? nsx : nsx - m);
                sx = Math.max(0, Math.min(m, nsx));
            } else {
                sx = nsx;
            }
            sy += dy;
            clampScroll();
            invalidate();
            return true;
        }

        @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
            if (count == 0) return true;
            flingVx = vx;
            if (pageDrag) return true;
            if (continuous || maxSx() > 0 || maxSy() > 0) {
                scroller.fling((int) sx, (int) sy, (int) -vx, (int) -vy, 0, (int) maxSx(), 0, (int) maxSy());
                postInvalidateOnAnimation();
            }
            return true;
        }

        /** Side zones act immediately (no double-tap delay); the centre waits for a possible double tap. */
        @Override public boolean onSingleTapUp(MotionEvent e) {
            if (count == 0 || scaling) return true;
            int z = zone(e.getX());
            if (z != 0 && !(listener != null && tapOnPage(e.getX(), e.getY()))) turn(z);
            return true;
        }

        @Override public boolean onSingleTapConfirmed(MotionEvent e) {
            if (count == 0 || zone(e.getX()) != 0) return true;
            if (listener != null && !tapOnPage(e.getX(), e.getY())) listener.onCenterTap();
            return true;
        }

        @Override public boolean onDoubleTap(MotionEvent e) {
            if (count == 0) return true;
            int z = zone(e.getX());
            if (z != 0) turn(z);
            else animateZoom(zoom > 1.05f ? 1f : 2.5f, e.getX(), e.getY());
            return true;
        }
    }

    /** -1 left zone, 1 right zone, 0 centre (or tap zones disabled). */
    private int zone(float x) {
        if (!tapZones) return 0;
        float f = x / Math.max(1, vw);
        return f < 0.3f ? -1 : f > 0.7f ? 1 : 0;
    }

    private void turn(int zone) {
        if (continuous) step(zone);
        else step((zone < 0) != rtl ? -1 : 1);
    }

    private boolean tapOnPage(float x, float y) {
        int page;
        if (continuous) {
            page = pageAtY(sy + y);
            contRect(page, tmp);
        } else {
            page = cur;
            pagedRect(cur, zoom, sx, sy, 0, tmp);
        }
        if (!tmp.contains(x, y)) return false;
        float s = tmp.width() / pw[page];
        return listener.onPageTap(page, (x - tmp.left) / s, (y - tmp.top) / s);
    }

    private final class Scales extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override public boolean onScaleBegin(ScaleGestureDetector d) {
            if (pageDrag) {
                dragX = 0;
                pageDrag = false;
            }
            scaling = true;
            setIdle(false);
            return true;
        }

        @Override public boolean onScale(ScaleGestureDetector d) {
            zoomAround(zoom * d.getScaleFactor(), d.getFocusX(), d.getFocusY());
            return true;
        }

        @Override public void onScaleEnd(ScaleGestureDetector d) {
            scaling = false;
            if (zoom <= 1.01f) patches.clear();
            afterMotion();
        }
    }

    // ---- drawing ------------------------------------------------------------------------------

    @Override protected void onDraw(Canvas c) {
        c.drawColor(bg);
        if (count == 0 || vw == 0) return;
        if (continuous) {
            int first = pageAtY(sy), last = pageAtY(sy + vh);
            for (int i = first; i <= last; i++) {
                contRect(i, tmp);
                drawPage(c, i, tmp);
            }
        } else {
            pagedRect(cur, zoom, sx, sy, dragX, tmp);
            drawPage(c, cur, tmp);
            if (dragX != 0) {
                boolean right = dragX < 0;
                int n = right != rtl ? cur + 1 : cur - 1;
                if (n >= 0 && n < count) {
                    boolean back = n < cur;
                    float ny = back && fitWidth ? Float.MAX_VALUE : 0;
                    float h = ph[n] * baseScale(n);
                    pagedRect(n, 1, 0, Math.min(ny, Math.max(0, h - vh)), dragX + (right ? vw : -vw), tmp);
                    drawPage(c, n, tmp);
                }
            }
        }
    }

    private void drawPage(Canvas c, int i, RectF r) {
        if (r.bottom < 0 || r.top > vh || r.right < 0 || r.left > vw) return;
        float s = r.width() / pw[i];
        boolean any = false;
        // Pass 1: tiles at an outdated scale (e.g. after rotation) as fallback; pass 2: exact ones.
        float bs = baseScale(i);
        for (int pass = 0; pass < 2; pass++) {
            for (Tile t : cache.values()) {
                if (t.page != i || (Math.abs(t.scale - bs) < bs * 0.002f) != (pass == 1)) continue;
                drawTile(c, t, r, s);
                any = true;
            }
        }
        if (!any) {
            fill.setColor(src != null && src.isImages() ? 0xff202020 : 0xfff2f2f2);
            c.drawRect(Math.max(0, r.left), Math.max(0, r.top), Math.min(vw, r.right), Math.min(vh, r.bottom), fill);
            if (failed.contains(key(i, 0))) {
                mark.setColor(0xffcc4444);
                c.drawCircle(r.centerX(), Math.max(r.top, 0) + Math.min(r.height(), vh) / 2, 12 * density, mark);
                mark.setColor(0x55ffd600);
            }
        }
        for (Tile t : patches) if (t.page == i) drawTile(c, t, r, s);
        if (i == highlightPage && highlights != null) {
            for (RectF h : highlights) {
                tmp2.set(r.left + h.left * s, r.top + h.top * s, r.left + h.right * s, r.top + h.bottom * s);
                c.drawRect(tmp2, mark);
            }
        }
    }

    private void drawTile(Canvas c, Tile t, RectF r, float s) {
        float k = s / t.scale;
        tmp2.set(r.left + t.region.left * k, r.top + t.region.top * k, r.left + t.region.right * k, r.top + t.region.bottom * k);
        if (tmp2.bottom < 0 || tmp2.top > vh || tmp2.right < 0 || tmp2.left > vw) return;
        c.drawBitmap(t.bmp, null, tmp2, paint);
    }

    // ---- render scheduling ---------------------------------------------------------------------

    private static long key(int page, int band) { return ((long) page << 20) | band; }

    private static long patchKey(int page) { return Long.MIN_VALUE + page; }

    private int bands(int i, float bs) {
        int h = Math.round(ph[i] * bs);
        return h <= BAND * 2 ? 1 : (h + BAND - 1) / BAND;
    }

    private Rect bandRect(int i, float bs, int band, int n) {
        int w = Math.max(1, Math.round(pw[i] * bs)), h = Math.max(1, Math.round(ph[i] * bs));
        if (n == 1) return new Rect(0, 0, w, h);
        return new Rect(0, band * BAND, w, Math.min(h, (band + 1) * BAND));
    }

    private void addBase(List<Job> jobs, int i, float winTop, float winBottom, float pageTop, float pageScreenScale, boolean visible) {
        if (i < 0 || i >= count) return;
        float bs = baseScale(i);
        int n = bands(i, bs);
        for (int b = 0; b < n; b++) {
            Rect r = bandRect(i, bs, b, n);
            float k = pageScreenScale / bs;
            float t = pageTop + r.top * k, btm = pageTop + r.bottom * k;
            if (btm < winTop || t > winBottom) continue;
            // Prefetch only what fits in the memory budget, so eviction never fights rendering.
            long est = (long) r.width() * r.height() * (lowMem && src.isImages() ? 2 : 4);
            if (!visible && wantedBytes + est > cacheLimit) return;
            long key = key(i, b);
            wanted.add(key);
            wantedBytes += est;
            Tile have = cache.get(key);
            if ((have != null && Math.abs(have.scale - bs) < bs * 0.002f) || inflight.contains(key) || failed.contains(key)) continue;
            jobs.add(new Job(Job.BASE, i, 0, bs, r, key));
        }
    }

    private void addPatch(List<Job> jobs, int i, RectF r) {
        if (zoom <= 1.01f || !idle) return;
        float s = r.width() / pw[i];
        if (src.isImages() && baseScale(i) >= 1f) return; // already at native resolution
        Rect region = new Rect((int) Math.max(0, -r.left), (int) Math.max(0, -r.top),
                (int) Math.min(r.width(), vw - r.left), (int) Math.min(r.height(), vh - r.top));
        if (region.width() <= 0 || region.height() <= 0) return;
        for (Tile t : patches)
            if (t.page == i && Math.abs(t.scale - s) < s * 0.002f && t.region.contains(region)) return;
        if (inflight.contains(patchKey(i))) return;
        jobs.add(new Job(Job.PATCH, i, 0, s, region, patchKey(i)));
    }

    private void schedule() {
        if (worker == null || count == 0 || vw == 0) return;
        ArrayList<Job> jobs = new ArrayList<>();
        wanted.clear();
        wantedBytes = 0;
        if (continuous) {
            int first = pageAtY(sy), last = pageAtY(sy + vh);
            for (int i = first; i <= last; i++) {
                contRect(i, tmp);
                addBase(jobs, i, 0, vh, tmp.top, tmp.width() / pw[i], true);
            }
            for (int i = first; i <= last; i++) {
                contRect(i, tmp);
                addPatch(jobs, i, tmp);
            }
            int pf = pageAtY(Math.min(docH(), sy + vh * 2f));
            for (int i = last; i <= Math.min(pf, last + 2); i++) {
                contRect(i, tmp);
                addBase(jobs, i, -vh, vh * 2f, tmp.top, tmp.width() / pw[i], false);
            }
            if (first > 0) {
                contRect(first - 1, tmp);
                addBase(jobs, first - 1, -vh, vh * 2f, tmp.top, tmp.width() / pw[first - 1], false);
            }
        } else {
            pagedRect(cur, zoom, sx, sy, 0, tmp);
            addBase(jobs, cur, 0, vh, tmp.top, tmp.width() / pw[cur], true);
            addBase(jobs, cur, -vh * 0.5f, vh * 1.5f, tmp.top, tmp.width() / pw[cur], false);
            addPatch(jobs, cur, tmp);
            for (int d : new int[]{1, -1, 2}) {
                int n = cur + d;
                if (n < 0 || n >= count) continue;
                float h = ph[n] * baseScale(n);
                float t = d < 0 && fitWidth ? Math.min(0, vh - h) : Math.max(0, (vh - h) / 2);
                addBase(jobs, n, 0, vh, t, baseScale(n), false);
            }
        }
        // Low priority: learn real page sizes around the current page.
        int from = -1;
        for (int d = 0; d < 400 && from < 0; d++) {
            int a = cur + d, b = cur - d;
            if (a < count && !known[a]) from = a;
            else if (b >= 0 && !known[b]) from = b;
        }
        if (from >= 0 && !inflight.contains(-1L)) {
            int to = from;
            while (to < count && to < from + 32 && !known[to]) to++;
            jobs.add(new Job(Job.SIZES, from, to, 0, null, -1L));
        }
        worker.post(jobs);
    }

    private void deliver(Job j, Bitmap b, float[] sizes, boolean error) {
        inflight.remove(j.key);
        if (worker == null) {
            if (b != null) b.recycle();
            return;
        }
        if (j.type == Job.SIZES) {
            if (sizes != null) applySizes(j.page, sizes);
            schedule();
            return;
        }
        if (error) {
            failed.add(j.key);
            invalidate();
            schedule();
            return;
        }
        if (b == null) return;
        Tile t = new Tile(j.page, j.scale, j.region, b);
        if (j.type == Job.PATCH) {
            for (Iterator<Tile> it = patches.iterator(); it.hasNext(); ) if (it.next().page == j.page) it.remove();
            if (zoom > 1.01f) patches.add(t);
        } else {
            Tile old = cache.put(j.key, t);
            if (old != null) cacheBytes -= old.bmp.getByteCount();
            cacheBytes += b.getByteCount();
            evict();
        }
        invalidate();
        schedule();
    }

    private void evict() {
        if (cacheBytes <= cacheLimit) return;
        Iterator<java.util.Map.Entry<Long, Tile>> it = cache.entrySet().iterator();
        while (cacheBytes > cacheLimit && it.hasNext()) {
            java.util.Map.Entry<Long, Tile> e = it.next();
            if (wanted.contains(e.getKey()) || isVisible(e.getValue().page)) continue;
            cacheBytes -= e.getValue().bmp.getByteCount();
            it.remove();
        }
    }

    private void applySizes(int from, float[] s) {
        int page = cur;
        float off = pageOffset();
        boolean changed = false;
        for (int k = 0; k * 2 + 1 < s.length; k++) {
            int i = from + k;
            if (i >= count || s[k * 2] <= 0) continue;
            if (pw[i] != s[k * 2] || ph[i] != s[k * 2 + 1]) changed = true;
            pw[i] = s[k * 2];
            ph[i] = s[k * 2 + 1];
            known[i] = true;
        }
        if (changed) relayout(page, off);
    }

    private final class Worker extends Thread {
        private final Object lock = new Object();
        private ArrayList<Job> queue = new ArrayList<>();
        private volatile boolean quit;

        Worker() { super("page-render"); }

        void post(ArrayList<Job> jobs) {
            synchronized (lock) {
                queue = jobs;
                lock.notifyAll();
            }
        }

        void quit() {
            quit = true;
            synchronized (lock) { lock.notifyAll(); }
        }

        @Override public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY + 2);
            final PageSource s = src;
            float[] one = new float[2];
            while (!quit) {
                Job j = null;
                synchronized (lock) {
                    while (!quit) {
                        while (!queue.isEmpty()) {
                            Job c = queue.remove(0);
                            if (!inflight.contains(c.key)) {
                                j = c;
                                break;
                            }
                        }
                        if (j != null) break;
                        try { lock.wait(); } catch (InterruptedException e) { return; }
                    }
                    if (quit) break;
                    inflight.add(j.key);
                }
                Bitmap b = null;
                float[] sizes = null;
                boolean error = false;
                try {
                    if (j.type == Job.SIZES) {
                        sizes = new float[(j.to - j.page) * 2];
                        for (int i = j.page; i < j.to && !quit; i++) {
                            try {
                                s.size(i, one);
                                sizes[(i - j.page) * 2] = one[0];
                                sizes[(i - j.page) * 2 + 1] = one[1];
                            } catch (Exception e) {
                                sizes[(i - j.page) * 2] = -1;
                            }
                        }
                    } else {
                        b = s.render(j.page, j.scale, j.region, lowMem);
                    }
                } catch (OutOfMemoryError oom) {
                    ui.post(PageView.this::trimMemory);
                    try { Thread.sleep(50); } catch (InterruptedException e) { return; }
                    error = j.type == Job.BASE;
                } catch (Throwable t) {
                    error = true;
                }
                final Bitmap fb = b;
                final float[] fs = sizes;
                final boolean fe = error;
                final Job fj = j;
                ui.post(() -> {
                    if (fj.type == Job.SIZES && fs != null) {
                        // Mark failed probes as known so they are not retried forever.
                        for (int k = 0; k * 2 < fs.length; k++) if (fs[k * 2] < 0 && fj.page + k < count) known[fj.page + k] = true;
                    }
                    deliver(fj, fb, fs, fe);
                });
            }
            try { s.close(); } catch (Exception ignored) {}
        }
    }
}
