package org.recto.reader.shelf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.Button;

import org.recto.reader.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Books standing on paper planks. Novels are spines; comics and manga are covers.
 * The view is as tall as the shelves, so it lives in a scroll view.
 */
public final class ShelfBoard extends View {
    public static final class Slot {
        public final String id, title;
        public final boolean face;
        public final float progress;
        public final int color;
        public Bitmap cover;
        final Rect box = new Rect();

        public Slot(String id, String title, boolean face, float progress, int color) {
            this.id = id;
            this.title = title;
            this.face = face;
            this.progress = progress;
            this.color = color;
        }
    }

    public interface Pick {
        void open(int index, Rect screen);

        void menu(int index, Rect screen);
    }

    private final BookDraw books = new BookDraw();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Slot> slots = new ArrayList<>();
    private final RectF body = new RectF();
    private final List<Integer> rowEnd = new ArrayList<>();
    private Pick pick;
    private boolean night, motion;
    private int bookH, plankH, gap, padX, padY, pressed = -1;
    private int contentH;
    private float downX, downY;
    private int downIndex = -1;
    private boolean longFired;
    private final Runnable longPress = () -> {
        if (downIndex < 0) return;
        longFired = true;
        if (pick != null) pick.menu(downIndex, screen(downIndex));
    };

    public ShelfBoard(Context c, AttributeSet a) {
        super(c, a);
        float d = getResources().getDisplayMetrics().density;
        books.setDensity(d);
        bookH = Math.round(132 * d);
        plankH = Math.round(14 * d);
        gap = Math.round(10 * d);
        padX = Math.round(16 * d);
        padY = Math.round(12 * d);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public void setPick(Pick pick) { this.pick = pick; }

    public void setNight(boolean night) {
        this.night = night;
        invalidate();
    }

    /** A pressed book lifts a little. Off when motion is disabled. */
    public void setMotion(boolean motion) { this.motion = motion; }

    @SuppressWarnings("deprecation")
    public int wallColor() {
        return getResources().getColor(night ? R.color.shelf_wall_dark : R.color.shelf_wall);
    }

    public void setSlots(List<Slot> next) {
        slots.clear();
        if (next != null) slots.addAll(next);
        pressed = -1;
        requestLayout();
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    }

    public void setCover(String id, Bitmap cover) {
        if (id == null) return;
        for (Slot s : slots) {
            if (id.equals(s.id)) {
                s.cover = cover;
                invalidate();
                return;
            }
        }
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        if (w <= 0) w = getResources().getDisplayMetrics().widthPixels;
        place(w);
        int h = Math.max(contentH, padY + bookH + plankH + padY);
        setMeasuredDimension(w, h);
    }

    private void place(int width) {
        rowEnd.clear();
        float d = getResources().getDisplayMetrics().density;
        int x = padX;
        int y = padY;
        int inner = Math.max(padX, width - padX);
        if (slots.isEmpty()) {
            contentH = padY + bookH + plankH + padY;
            return;
        }
        for (int i = 0; i < slots.size(); i++) {
            Slot s = slots.get(i);
            int bw = s.face ? Math.round(bookH * 2f / 3f) : spineWidth(s.title, d);
            if (x > padX && x + bw > inner) {
                rowEnd.add(i);
                x = padX;
                y += bookH + plankH + gap;
            }
            s.box.set(x, y, x + bw, y + bookH);
            x += bw + gap;
        }
        rowEnd.add(slots.size());
        contentH = y + bookH + plankH + padY;
    }

    private int spineWidth(String title, float d) {
        int n = title == null ? 1 : title.length();
        int w = Math.round((28 + Math.min(18, n)) * d);
        return Math.max(Math.round(26 * d), Math.min(Math.round(48 * d), w));
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth();
        c.drawColor(wallColor());
        if (slots.isEmpty()) {
            drawPlank(c, padX, padY + bookH, w - padX);
            return;
        }
        int start = 0;
        for (int end : rowEnd) {
            if (start >= slots.size()) break;
            int top = slots.get(start).box.top;
            int left = slots.get(start).box.left;
            int right = slots.get(end - 1).box.right;
            drawPlank(c, left - gap / 2, top + bookH, right + gap / 2);
            for (int i = start; i < end; i++) drawSlot(c, i);
            start = end;
        }
    }

    private void drawPlank(Canvas c, int left, int top, int right) {
        paint.setColor(night ? 0xff3a322a : 0xffc6a67a);
        c.drawRect(left, top - gap / 3f, right, top + plankH, paint);
        paint.setColor(night ? 0xff241e18 : 0xff8d6842);
        c.drawRect(left, top + plankH - gap / 3f, right, top + plankH, paint);
    }

    private void drawSlot(Canvas c, int i) {
        Slot s = slots.get(i);
        body.set(s.box);
        if (motion && i == pressed) body.offset(0, -6 * getResources().getDisplayMetrics().density);
        books.draw(c, body, s.face, s.title, s.color, s.cover, s.progress);
    }

    private int hit(float x, float y) {
        for (int i = slots.size() - 1; i >= 0; i--) if (slots.get(i).box.contains((int) x, (int) y)) return i;
        return -1;
    }

    private Rect screen(int index) {
        Rect b = new Rect(slots.get(index).box);
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        b.offset(loc[0], loc[1]);
        return b;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downIndex = hit(e.getX(), e.getY());
                downX = e.getX();
                downY = e.getY();
                longFired = false;
                if (downIndex < 0) return false;
                pressed = downIndex;
                invalidate();
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (downIndex >= 0 && Math.hypot(e.getX() - downX, e.getY() - downY) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                    removeCallbacks(longPress);
                    downIndex = -1;
                    pressed = -1;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                int up = downIndex;
                downIndex = -1;
                pressed = -1;
                invalidate();
                if (!longFired && up >= 0 && hit(e.getX(), e.getY()) == up && pick != null) pick.open(up, screen(up));
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                downIndex = -1;
                pressed = -1;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() { return nodes; }

    private final AccessibilityNodeProvider nodes = new AccessibilityNodeProvider() {
        @Override @SuppressWarnings("deprecation")
        public AccessibilityNodeInfo createAccessibilityNodeInfo(int id) {
            if (id == HOST_VIEW_ID) {
                AccessibilityNodeInfo host = AccessibilityNodeInfo.obtain(ShelfBoard.this);
                onInitializeAccessibilityNodeInfo(host);
                for (int i = 0; i < slots.size(); i++) host.addChild(ShelfBoard.this, i);
                return host;
            }
            if (id < 0 || id >= slots.size()) return null;
            Slot s = slots.get(id);
            AccessibilityNodeInfo n = AccessibilityNodeInfo.obtain(ShelfBoard.this, id);
            n.setClassName(Button.class.getName());
            n.setPackageName(getContext().getPackageName());
            n.setSource(ShelfBoard.this, id);
            n.setParent(ShelfBoard.this);
            String label = s.title == null || s.title.isEmpty() ? "?" : s.title;
            if (s.progress > 0) label = label + ", " + Math.round(s.progress * 100) + "%";
            n.setContentDescription(label);
            n.setClickable(true);
            n.setLongClickable(true);
            n.setFocusable(true);
            n.setVisibleToUser(true);
            n.addAction(AccessibilityNodeInfo.ACTION_CLICK);
            n.addAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
            Rect parent = new Rect(s.box);
            n.setBoundsInParent(parent);
            int[] loc = new int[2];
            getLocationOnScreen(loc);
            Rect screen = new Rect(parent);
            screen.offset(loc[0], loc[1]);
            n.setBoundsInScreen(screen);
            return n;
        }

        @Override public boolean performAction(int id, int action, Bundle args) {
            if (id == HOST_VIEW_ID) return ShelfBoard.this.performAccessibilityAction(action, args);
            if (id < 0 || id >= slots.size() || pick == null) return false;
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                pick.open(id, screen(id));
                return true;
            }
            if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK) {
                pick.menu(id, screen(id));
                return true;
            }
            return false;
        }
    };
}
