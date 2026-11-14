package org.recto.reader;

import android.content.Context;
import android.content.SharedPreferences;

/** Global settings. */
final class Prefs {
    // Reader themes for reflowable books.
    static final int THEME_AUTO = 0, THEME_LIGHT = 1, THEME_SEPIA = 2, THEME_DARK = 3, THEME_BLACK = 4;

    private final SharedPreferences p;

    Prefs(Context c) { p = c.getSharedPreferences("settings", Context.MODE_PRIVATE); }

    int i(String k, int def) { return p.getInt(k, def); }

    boolean b(String k, boolean def) { return p.getBoolean(k, def); }

    String s(String k, String def) { return p.getString(k, def); }

    void put(String k, int v) { p.edit().putInt(k, v).apply(); }

    void put(String k, boolean v) { p.edit().putBoolean(k, v).apply(); }

    void put(String k, String v) { p.edit().putString(k, v).apply(); }

    // App-wide
    boolean keepScreenOn() { return b("keepOn", true); }

    boolean volumeKeys() { return b("volKeys", true); }

    boolean tapZones() { return b("tapZones", true); }

    boolean animations() { return b("anim", true); }

    boolean fullscreen() { return b("fullscreen", true); }

    boolean lowMemory() { return b("lowMem", false); }

    /** 0 follow system, 1 light, 2 dark. */
    int appTheme() { return i("appTheme", 0); }

    int orientation() { return i("orientation", 0); }

    /** Two pages side by side: {@link org.recto.reader.screen.Spread#AUTO} (large screens), OFF or ON. */
    int spread() { return i("spread", org.recto.reader.screen.Spread.AUTO); }

    /** {@link org.recto.reader.shelf.Shelf} sort mode. */
    int shelfSort() { return i("shelfSort", org.recto.reader.shelf.Shelf.SORT_RECENT); }

    /** {@link org.recto.reader.shelf.Shelf} reading-state filter. */
    int shelfFilter() { return i("shelfFilter", org.recto.reader.shelf.Shelf.FILTER_ALL); }

    // Reflowable text
    int fontSize() { return i("fontSize", 100); }

    int font() { return i("font", 0); }

    int lineHeight() { return i("lineHeight", 0); }

    int margin() { return i("margin", 18); }

    int textAlign() { return i("align", 0); }

    boolean hyphens() { return b("hyphens", false); }

    boolean scrollMode() { return b("scroll", false); }

    int readerTheme() { return i("readerTheme", THEME_AUTO); }
}
