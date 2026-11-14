package org.recto.reader.shelf;

import org.recto.reader.util.Natural;

/** How the library list is narrowed and ordered. Pure, so the activity only applies the result. */
public final class Shelf {
    public static final int SORT_RECENT = 0, SORT_TITLE = 1, SORT_AUTHOR = 2, SORT_PROGRESS = 3;
    public static final int FILTER_ALL = 0, FILTER_NEW = 1, FILTER_READING = 2, FILTER_FINISHED = 3;
    /** A book counts as finished once this much of it has been read. */
    public static final float FINISHED = 0.98f;

    private Shelf() {}

    public static int sort(int value) {
        return value >= SORT_RECENT && value <= SORT_PROGRESS ? value : SORT_RECENT;
    }

    public static int filter(int value) {
        return value >= FILTER_ALL && value <= FILTER_FINISHED ? value : FILTER_ALL;
    }

    /** New means never opened. Finished means opened and at least {@link #FINISHED}. */
    public static boolean visible(int filter, long opened, float progress) {
        switch (filter(filter)) {
            case FILTER_NEW: return opened <= 0;
            case FILTER_READING: return opened > 0 && progress < FINISHED;
            case FILTER_FINISHED: return opened > 0 && progress >= FINISHED;
            default: return true;
        }
    }

    /**
     * Order for {@code sort}. Titles and authors use natural, case-insensitive order.
     * An empty author sorts after real names. Recent and progress put the larger value first.
     */
    public static int compare(int sort, String titleA, String authorA, long openedA, float progressA,
                               String titleB, String authorB, long openedB, float progressB) {
        switch (sort(sort)) {
            case SORT_TITLE: {
                int c = Natural.cmp(name(titleA), name(titleB));
                return c != 0 ? c : Long.compare(openedB, openedA);
            }
            case SORT_AUTHOR: {
                int c = Natural.cmp(authorKey(authorA), authorKey(authorB));
                if (c == 0) c = Natural.cmp(name(titleA), name(titleB));
                return c;
            }
            case SORT_PROGRESS: {
                int c = Float.compare(progressB, progressA);
                if (c == 0) c = Natural.cmp(name(titleA), name(titleB));
                return c;
            }
            default: {
                int c = Long.compare(openedB, openedA);
                return c != 0 ? c : Natural.cmp(name(titleA), name(titleB));
            }
        }
    }

    private static String name(String s) { return s == null ? "" : s; }

    /** Empty authors sort last without changing the comparison of real names. */
    private static String authorKey(String s) {
        return s == null || s.isEmpty() ? "\uffff" : s;
    }
}
