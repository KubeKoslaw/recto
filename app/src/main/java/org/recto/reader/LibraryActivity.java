package org.recto.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.recto.reader.shelf.Shelf;
import org.recto.reader.util.Text;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Home screen: recently read books, books found in chosen folders, open-file button. */
public final class LibraryActivity extends Activity {
    private static final int REQ_OPEN = 1, REQ_FOLDER = 2;
    private static boolean scannedThisRun;

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private Prefs prefs;
    private Library library;
    private ListView list;
    private TextView empty;
    private EditText query;
    private final Adapter adapter = new Adapter();
    private List<Library.Book> all = new ArrayList<>();
    private final List<Library.Book> shown = new ArrayList<>();
    private int themeApplied;

    @Override protected void onCreate(Bundle state) {
        prefs = new Prefs(this);
        themeApplied = prefs.appTheme();
        if (themeApplied == 1) setTheme(R.style.AppTheme_Light);
        else if (themeApplied == 2) setTheme(R.style.AppTheme_Dark);
        super.onCreate(state);
        library = Library.get(this);
        setContentView(R.layout.activity_library);
        edgeToEdge();
        list = findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        query = findViewById(R.id.query);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> openBook(shown.get(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            bookMenu(shown.get(pos));
            return true;
        });
        findViewById(R.id.open).setOnClickListener(v -> pickFile());
        findViewById(R.id.folder).setOnClickListener(v -> pickFolder());
        findViewById(R.id.menu).setOnClickListener(this::menu);
        findViewById(R.id.filter).setOnClickListener(v -> toggleFilter());
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override public void afterTextChanged(Editable s) { filter(); }
        });
        buildShelf();
        if (!scannedThisRun && !folders().isEmpty()) {
            scannedThisRun = true;
            scan();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (prefs.appTheme() != themeApplied) {
            recreate();
            return;
        }
        reload();
    }

    @Override protected void onDestroy() {
        bg.shutdown();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private void edgeToEdge() {
        View root = findViewById(R.id.root);
        boolean night = isNight();
        if (Build.VERSION.SDK_INT >= 30) {
            Compat.V30.edgeToEdge(getWindow(), !night);
        } else {
            int f = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (!night && Build.VERSION.SDK_INT >= 23) f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (!night && Build.VERSION.SDK_INT >= 26) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(f);
            // Before Android 6 status bar icons are always white: keep the bar readable.
            if (Build.VERSION.SDK_INT < 23) getWindow().setStatusBarColor(0x55000000);
            if (Build.VERSION.SDK_INT < 26) getWindow().setNavigationBarColor(0x55000000);
        }
        View fab = findViewById(R.id.open);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            int t, b, l, r;
            if (Build.VERSION.SDK_INT >= 30) {
                int[] i = new int[4];
                Compat.V30.insets(ins, i, null, true);
                l = i[0]; t = i[1]; r = i[2]; b = i[3];
            } else {
                t = ins.getSystemWindowInsetTop(); b = ins.getSystemWindowInsetBottom();
                l = ins.getSystemWindowInsetLeft(); r = ins.getSystemWindowInsetRight();
            }
            v.setPadding(l, t, r, 0);
            list.setPadding(0, 0, 0, b + dp(96));
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) fab.getLayoutParams();
            lp.bottomMargin = b + dp(20);
            fab.setLayoutParams(lp);
            return ins;
        });
    }

    private boolean isNight() {
        int t = prefs.appTheme();
        if (t != 0) return t == 2;
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private int dp(float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }

    // ---- list -------------------------------------------------------------------------------------

    private void reload() {
        all = library.all();
        filter();
    }

    private void filter() {
        String q = query.getVisibility() == View.VISIBLE ? query.getText().toString().trim().toLowerCase(Locale.getDefault()) : "";
        int mode = Shelf.filter(prefs.shelfFilter());
        shown.clear();
        for (Library.Book b : all) {
            if (!Shelf.visible(mode, b.opened, b.progress)) continue;
            if (q.isEmpty() || (b.title != null && b.title.toLowerCase(Locale.getDefault()).contains(q))
                    || (b.author != null && b.author.toLowerCase(Locale.getDefault()).contains(q))) shown.add(b);
        }
        final int sort = Shelf.sort(prefs.shelfSort());
        java.util.Collections.sort(shown, (a, b) -> Shelf.compare(sort, a.title, a.author, a.opened, a.progress,
                b.title, b.author, b.opened, b.progress));
        adapter.notifyDataSetChanged();
        if (shown.isEmpty() && !all.isEmpty()) empty.setText(R.string.shelf_empty);
        else empty.setText(R.string.empty);
        empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void buildShelf() {
        LinearLayout row = findViewById(R.id.shelfRow);
        row.removeAllViews();
        int sort = Shelf.sort(prefs.shelfSort());
        int[] sortLabels = {R.string.sort_recent, R.string.sort_title, R.string.sort_author, R.string.sort_progress};
        for (int i = 0; i < sortLabels.length; i++) shelfChip(row, sortLabels[i], i == sort, "shelfSort", i);
        View gap = new View(this);
        row.addView(gap, new LinearLayout.LayoutParams(dp(10), 1));
        int filter = Shelf.filter(prefs.shelfFilter());
        int[] filterLabels = {R.string.shelf_all, R.string.shelf_new, R.string.shelf_reading, R.string.shelf_finished};
        for (int i = 0; i < filterLabels.length; i++) shelfChip(row, filterLabels[i], i == filter, "shelfFilter", i);
    }

    private void shelfChip(LinearLayout row, int label, boolean on, String key, int value) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(13);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(6));
        row.addView(t, lp);
        chip(t, on);
        t.setOnClickListener(v -> {
            prefs.put(key, value);
            buildShelf();
            filter();
        });
    }

    private void toggleFilter() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (query.getVisibility() == View.VISIBLE) {
            query.setText("");
            query.setVisibility(View.GONE);
            if (imm != null) imm.hideSoftInputFromWindow(query.getWindowToken(), 0);
        } else {
            query.setVisibility(View.VISIBLE);
            query.requestFocus();
            if (imm != null) imm.showSoftInput(query, InputMethodManager.SHOW_IMPLICIT);
        }
        filter();
    }

    private static final int[] BADGE = {0xff2f6f5e, 0xff8a4b2a, 0xff3d5a99, 0xff7b3f7a, 0xff5b6b2f, 0xff9a3b3b};

    private static final class Holder {
        TextView title, sub, pct, badge;
        ProgressBar bar;
        ImageView cover;
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }

        @Override public Object getItem(int i) { return shown.get(i); }

        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int pos, View v, ViewGroup parent) {
            Holder h;
            if (v == null) {
                v = LayoutInflater.from(LibraryActivity.this).inflate(R.layout.item_book, parent, false);
                h = new Holder();
                h.title = v.findViewById(R.id.title);
                h.sub = v.findViewById(R.id.sub);
                h.pct = v.findViewById(R.id.pct);
                h.badge = v.findViewById(R.id.badge);
                h.bar = v.findViewById(R.id.bar);
                h.cover = v.findViewById(R.id.cover);
                v.setTag(h);
            } else {
                h = (Holder) v.getTag();
            }
            Library.Book b = shown.get(pos);
            h.title.setText(b.title != null ? b.title : "?");
            String sub = b.format != null ? b.format : "";
            if (b.author != null && !b.author.isEmpty()) sub = b.author + "  ·  " + sub;
            if (b.opened > 0) {
                long now = System.currentTimeMillis();
                sub += "  \u00b7  " + (now - b.opened < DateUtils.MINUTE_IN_MILLIS ? getString(R.string.just_now)
                        : DateUtils.getRelativeTimeSpanString(b.opened, now, DateUtils.MINUTE_IN_MILLIS));
            }
            h.sub.setText(sub);
            h.bar.setProgress(Math.round(b.progress * 1000));
            h.bar.setVisibility(b.opened > 0 ? View.VISIBLE : View.INVISIBLE);
            h.pct.setText(b.opened > 0 ? Math.round(b.progress * 100) + "%" : getString(R.string.status_new));
            h.badge.setText(b.format);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(3));
            g.setColor(BADGE[Math.abs(b.id.hashCode()) % BADGE.length]);
            h.badge.setBackground(g);
            // Thumbnails are decoded / generated off the UI thread; the row may be recycled meanwhile.
            final ImageView cover = h.cover;
            cover.setTag(b.id);
            Bitmap bmp = Covers.cached(b.id);
            cover.setImageBitmap(bmp);
            if (bmp == null) {
                Covers.load(LibraryActivity.this, b, (id, loaded) -> {
                    if (loaded != null) runOnUiThread(() -> {
                        if (id.equals(cover.getTag())) cover.setImageBitmap(loaded);
                    });
                });
            }
            return v;
        }
    }

    // ---- opening ----------------------------------------------------------------------------------

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_OPEN);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_picker, Toast.LENGTH_LONG).show();
        }
    }

    private void pickFolder() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_picker, Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {}
        if (req == REQ_OPEN) {
            startReader(uri);
        } else if (req == REQ_FOLDER) {
            Set<String> f = folders();
            f.add(uri.toString());
            prefs.put("folders", join(f));
            scan();
        }
    }

    private void openBook(Library.Book b) {
        if (b.uri == null) return;
        startReader(Uri.parse(b.uri));
    }

    private void startReader(Uri uri) {
        Intent i = new Intent(this, ReaderActivity.class);
        i.setData(uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(i);
    }

    private void bookMenu(Library.Book b) {
        String[] items = {getString(R.string.open), getString(R.string.remove)};
        new AlertDialog.Builder(this).setTitle(b.title).setItems(items, (d, which) -> {
            if (which == 0) openBook(b);
            else {
                library.remove(b.id);
                Covers.forget(this, b.id);
                releaseIfUnused(b.uri);
                reload();
            }
        }).show();
    }

    private void releaseIfUnused(String uri) {
        if (uri == null) return;
        for (String f : folders()) if (uri.startsWith(f)) return; // still covered by a folder grant
        try {
            getContentResolver().releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {}
    }

    // ---- folders ---------------------------------------------------------------------------------

    private Set<String> folders() {
        Set<String> s = new LinkedHashSet<>();
        for (String f : prefs.s("folders", "").split("\n")) if (!f.isEmpty()) s.add(f);
        return s;
    }

    private void scan() {
        final Set<String> roots = folders();
        Toast.makeText(this, R.string.scanning, Toast.LENGTH_SHORT).show();
        bg.execute(() -> {
            List<Library.Book> found = new ArrayList<>();
            // Roots that could not be listed completely (revoked access, unmounted card, provider
            // error): their books are kept rather than treated as deleted.
            List<String> incomplete = new ArrayList<>();
            for (String f : roots) {
                Uri tree = Uri.parse(f);
                boolean complete;
                try {
                    complete = walk(tree, DocumentsContract.getTreeDocumentId(tree), 0, found);
                } catch (Exception e) {
                    complete = false;
                }
                if (!complete) incomplete.add(f);
            }
            Set<String> ids = new HashSet<>();
            List<Library.Book> updates = new ArrayList<>();
            for (Library.Book b : found) {
                if (!ids.add(b.id)) continue;
                Library.Book old = library.find(b.id);
                if (old == null) {
                    // Same document already known under another id (e.g. its size was not reported).
                    Library.Book same = library.findByUri(b.uri);
                    if (same != null) ids.add(same.id); else updates.add(b);
                } else if (old.uri == null || !old.uri.equals(b.uri)) {
                    Library.Book moved = old.copy(); // never mutate shared entries off the UI thread
                    moved.uri = b.uri;
                    updates.add(moved);
                }
            }
            List<String> removes = new ArrayList<>();
            for (Library.Book b : library.all()) {
                if (!b.folder || b.opened != 0 || ids.contains(b.id)) continue;
                boolean keep = false;
                for (String f : incomplete) if (b.uri != null && b.uri.startsWith(f)) keep = true;
                if (!keep) removes.add(b.id);
            }
            library.putAll(updates, removes);
            runOnUiThread(() -> {
                if (!isDestroyed()) reload();
            });
        });
    }

    /** Lists a folder tree; returns false if some folder could not be read. */
    private boolean walk(Uri tree, String docId, int depth, List<Library.Book> out) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE};
        List<String> dirs = new ArrayList<>();
        try (Cursor c = getContentResolver().query(children, cols, null, null, null)) {
            if (c == null) return false;
            long now = System.currentTimeMillis();
            while (c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                if (name == null || name.startsWith(".")) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    dirs.add(id);
                } else if (Opener.supportedName(name)) {
                    long size = c.isNull(3) ? 0 : c.getLong(3);
                    Library.Book b = new Library.Book();
                    b.id = Library.idFor(name, size);
                    b.uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString();
                    b.title = Text.baseName(name);
                    String ext = name.toLowerCase(Locale.ROOT).endsWith(".fb2.zip") ? "fb2" : Text.ext(name);
                    b.format = ext.toUpperCase(Locale.ROOT);
                    b.size = size;
                    b.added = now;
                    b.folder = true;
                    out.add(b);
                }
            }
        } catch (Exception e) {
            return false;
        }
        boolean complete = true;
        if (depth < 10) for (String d : dirs) complete &= walk(tree, d, depth + 1, out);
        return complete;
    }

    private void manageFolders() {
        final List<String> f = new ArrayList<>(folders());
        String[] names = new String[f.size()];
        for (int i = 0; i < names.length; i++) {
            String path = Uri.decode(Uri.parse(f.get(i)).getLastPathSegment());
            names[i] = path != null ? path : f.get(i);
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(R.string.folders)
                .setPositiveButton(R.string.add_folder, (d, w) -> pickFolder())
                .setNeutralButton(R.string.rescan, (d, w) -> scan())
                .setNegativeButton(android.R.string.cancel, null);
        if (f.isEmpty()) b.setMessage(R.string.no_folders);
        else b.setItems(names, (d, which) -> new AlertDialog.Builder(this).setTitle(names[which])
                .setMessage(R.string.remove_folder_q)
                .setPositiveButton(R.string.remove, (d2, w2) -> {
                    String uri = f.remove(which);
                    prefs.put("folders", join(f));
                    try {
                        getContentResolver().releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception ignored) {}
                    List<String> removes = new ArrayList<>();
                    for (Library.Book book : library.all()) if (book.folder && book.opened == 0) removes.add(book.id);
                    library.putAll(new ArrayList<>(), removes);
                    if (!f.isEmpty()) scan();
                    reload();
                })
                .setNegativeButton(android.R.string.cancel, null).show());
        b.show();
    }

    // ---- menu, settings, about ------------------------------------------------------------------

    private void menu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, R.string.settings);
        m.getMenu().add(0, 2, 0, R.string.folders);
        m.getMenu().add(0, 3, 0, R.string.about);
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: settings(); break;
                case 2: manageFolders(); break;
                default: about();
            }
            return true;
        });
        m.show();
    }

    private void settings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), dp(8));
        TextView themeLabel = new TextView(this);
        themeLabel.setText(R.string.app_theme);
        themeLabel.setTextSize(13);
        themeLabel.setPadding(0, dp(8), 0, dp(4));
        box.addView(themeLabel);
        final int[] themeNames = {R.string.theme_system, R.string.theme_light, R.string.theme_dark};
        LinearLayout row = new LinearLayout(this);
        List<TextView> chips = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            TextView t = new TextView(this);
            t.setText(themeNames[i]);
            t.setGravity(android.view.Gravity.CENTER);
            t.setPadding(dp(4), dp(8), dp(4), dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.setMarginEnd(dp(6));
            row.addView(t, lp);
            chips.add(t);
            final int idx = i;
            t.setOnClickListener(v -> {
                prefs.put("appTheme", idx);
                for (int k = 0; k < chips.size(); k++) chip(chips.get(k), k == idx);
            });
            chip(t, i == prefs.appTheme());
        }
        box.addView(row);
        addSwitch(box, R.string.keep_screen_on, "keepOn", true);
        addSwitch(box, R.string.volume_keys, "volKeys", true);
        addSwitch(box, R.string.tap_zones, "tapZones", true);
        addSwitch(box, R.string.animations, "anim", true);
        addSwitch(box, R.string.fullscreen, "fullscreen", true);
        addSwitch(box, R.string.auto_invert, "autoInvert", true);
        addSwitch(box, R.string.low_memory, "lowMem", false);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this).setTitle(R.string.settings).setView(sv)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    if (prefs.appTheme() != themeApplied) recreate();
                }).show();
    }

    private void chip(TextView t, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(18));
        if (on) g.setColor(themeColor(android.R.attr.colorAccent));
        else g.setStroke(dp(1), 0x66888888);
        t.setBackground(g);
        t.setTextColor(themeColor(on ? android.R.attr.colorBackground : android.R.attr.textColorPrimary));
    }

    private void addSwitch(LinearLayout box, int label, String key, boolean def) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextSize(15);
        s.setChecked(prefs.b(key, def));
        s.setPadding(0, dp(12), 0, dp(12));
        s.setOnCheckedChangeListener((b, v) -> prefs.put(key, v));
        box.addView(s);
    }

    private void about() {
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {}
        new AlertDialog.Builder(this).setTitle(getString(R.string.app_name) + " " + version)
                .setMessage(R.string.about_text).setPositiveButton(android.R.string.ok, null).show();
    }

    private static String join(Iterable<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        return sb.toString();
    }

    private int themeColor(int attr) {
        android.content.res.TypedArray a = obtainStyledAttributes(new int[]{attr});
        int c = a.getColor(0, 0xff888888);
        a.recycle();
        return c;
    }
}
