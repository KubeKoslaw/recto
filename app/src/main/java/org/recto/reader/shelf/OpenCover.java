package org.recto.reader.shelf;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * The book that was tapped, drawn on top of the reader. It grows from the shelf slot
 * to the middle of the screen, then fades once the page is ready.
 */
public final class OpenCover extends View {
    private final BookDraw books = new BookDraw();
    private final RectF from = new RectF(), to = new RectF(), now = new RectF();
    private String title = "";
    private int color;
    private float progress;
    private boolean face, grown, pageReady, leaving;
    private Bitmap cover;
    private ValueAnimator grow, fade;
    private Runnable gone;

    public OpenCover(Context c) {
        super(c);
        books.setDensity(getResources().getDisplayMetrics().density);
        setClickable(true);
    }

    public void whenGone(Runnable gone) { this.gone = gone; }

    public void show(RectF start, boolean face, String title, int color, float progress, Bitmap cover) {
        this.face = face;
        this.title = title == null ? "" : title;
        this.color = color;
        this.progress = progress;
        this.cover = cover;
        from.set(start);
        now.set(start);
        setAlpha(1f);
        setVisibility(VISIBLE);
        post(this::grow);
    }

    private void grow() {
        if (getWidth() == 0 || getHeight() == 0) {
            post(this::grow);
            return;
        }
        float h = getHeight() * (face ? 0.62f : 0.52f);
        float w = face ? h * 2f / 3f : h * 0.28f;
        to.set((getWidth() - w) / 2f, (getHeight() - h) / 2f, (getWidth() + w) / 2f, (getHeight() + h) / 2f);
        if (from.width() < 2 || from.height() < 2) from.set(to);
        grow = ValueAnimator.ofFloat(0f, 1f);
        grow.setDuration(340);
        grow.setInterpolator(new DecelerateInterpolator());
        grow.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            now.left = from.left + (to.left - from.left) * t;
            now.top = from.top + (to.top - from.top) * t;
            now.right = from.right + (to.right - from.right) * t;
            now.bottom = from.bottom + (to.bottom - from.bottom) * t;
            invalidate();
        });
        grow.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (leaving) return;
                grown = true;
                if (pageReady) fadeOut();
            }
        });
        grow.start();
    }

    /** The page is on screen. Fade after the grow finishes. */
    public void reveal() {
        pageReady = true;
        if (grown) fadeOut();
    }

    /** Give up (the file could not be opened) without waiting for the page. */
    public void dismiss() { fadeOut(); }

    private void fadeOut() {
        if (leaving) return;
        leaving = true;
        if (grow != null) grow.cancel();
        fade = ValueAnimator.ofFloat(getAlpha(), 0f);
        fade.setDuration(200);
        fade.addUpdateListener(a -> setAlpha((float) a.getAnimatedValue()));
        fade.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                setVisibility(GONE);
                if (gone != null) gone.run();
            }
        });
        fade.start();
    }

    public void stop() {
        if (grow != null) grow.cancel();
        if (fade != null) fade.cancel();
    }

    @Override protected void onDraw(Canvas c) {
        books.draw(c, now, face, title, color, cover, progress);
    }
}
