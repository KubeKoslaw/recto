package org.recto.reader.screen;

/**
 * Decides when to show two pages side by side, like an open book.
 *
 * <p>The decision is made from the size of the reader's own window in dp, not from the device
 * model, so it follows every change: unfolding or folding a foldable (e.g. Honor Magic V3,
 * Galaxy Z Fold, Pixel Fold), rotation, split screen and free-form windows. Android delivers
 * those as size changes of the view, so no hinge sensor or extra library is needed.
 *
 * <p>Typical windows (dp, full screen):
 * <ul>
 *   <li>Foldable inner screen, unfolded (Magic V3 ~ 860 x 935): two pages.</li>
 *   <li>Foldable outer screen or a normal phone in portrait (~ 385 x 850): one page.</li>
 *   <li>Normal phone in landscape (~ 850 x 385): one page in {@link #AUTO}, two in {@link #ON}.</li>
 *   <li>Tablet in landscape: two pages; in portrait (aspect ~ 0.63): one page.</li>
 *   <li>Half of an unfolded foldable in split screen (~ 430 x 860): one page.</li>
 * </ul>
 */
public final class Spread {
    /** Two pages on a large, book-shaped or landscape window (unfolded foldable, tablet). */
    public static final int AUTO = 0;
    /** Always one page. */
    public static final int OFF = 1;
    /** Two pages whenever the window is wide enough, also on phones in landscape. */
    public static final int ON = 2;

    /** Shorter window side (dp) from which the screen counts as large (Android's tablet class). */
    static final float LARGE_DP = 600f;
    /**
     * Minimum width / height. An unfolded foldable is almost square (Magic V3 ~ 0.92, Galaxy
     * Z Fold ~ 0.89), a tablet in portrait is ~ 0.63-0.75 and must stay single.
     */
    static final float MIN_ASPECT = 0.8f;
    /** In {@link #ON}: each page needs at least this width (dp) to stay readable. */
    static final float MIN_PAGE_DP = 240f;

    private Spread() {}

    /**
     * True if a window of {@code wDp} x {@code hDp} should show two pages.
     *
     * @param mode {@link #AUTO}, {@link #OFF} or {@link #ON}
     */
    public static boolean twoPages(int mode, float wDp, float hDp) {
        if (mode == OFF || wDp <= 0 || hDp <= 0) return false;
        if (wDp < hDp * MIN_ASPECT) return false;
        if (mode == ON) return wDp >= 2 * MIN_PAGE_DP;
        return Math.min(wDp, hDp) >= LARGE_DP;
    }

    /** Same as {@link #twoPages(int, float, float)} for a size in px. */
    public static boolean twoPages(int mode, int wPx, int hPx, float density) {
        if (density <= 0) return false;
        return twoPages(mode, wPx / density, hPx / density);
    }

    /**
     * True if the page should be shown alone even in two-page mode: landscape pages (e.g. a
     * double-page spread already scanned as one image, or a landscape PDF page).
     */
    public static boolean isWide(float pageW, float pageH) {
        return pageH > 0 && pageW > pageH * 1.05f;
    }
}
