package org.recto.reader;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.recto.reader.fixed.PageView;
import org.recto.reader.fixed.PdfSource;
import org.recto.reader.flow.FlowBook;
import org.recto.reader.flow.FlowView;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shows one document: fixed pages ({@link PageView}) or reflowable text ({@link FlowView}). */
public final class ReaderActivity extends Activity implements PageView.Listener, FlowView.Listener {
    private static final int MODE_PAGED = 0, MODE_CONTINUOUS = 1;
    private static final int REQ_STORAGE = 7;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private Prefs prefs;
    private Library library;
    private Library.Book entry;
    private Opener.Doc doc;
    private Uri uri;
    private PageView pageView;
    private FlowView flowView;
    private View root, top, bottom, panelScroll;
    private FrameLayout content;
    private LinearLayout panel;
    private TextView title, chapterLabel, pageLabel, status;
    private SeekBar seek;
    private ProgressBar progress;
    private boolean uiShown, seeking, showStatus, destroyed;
    private int insetTop, insetBottom, insetLeft, insetRight, cutTop, cutBottom, cutLeft, cutRight;
    private Object backCallback;
    private final HashMap<Integer, List<Object[]>> pdfLinks = new HashMap<>();
    private final ExecutorService linkLoader = Executors.newSingleThreadExecutor();

    interface IntPick {
        void pick(int i);
    }

    // ---- lifecycle ---------------------------------------------------------------------------

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        library = Library.get(this);
        setContentView(R.layout.activity_reader);
        root = findViewById(R.id.root);
        content = findViewById(R.id.content);
        top = findViewById(R.id.top);
        bottom = findViewById(R.id.bottom);
        panelScroll = findViewById(R.id.panel);
        panel = findViewById(R.id.panelBody);
        title = findViewById(R.id.title);
        chapterLabel = findViewById(R.id.chapter);
        pageLabel = findViewById(R.id.pageLabel);
        status = findViewById(R.id.status);
        seek = findViewById(R.id.seek);
        progress = findViewById(R.id.progress);

        edgeToEdge();
        if (prefs.keepScreenOn()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyOrientation();
        setSystemBars(false);

        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.toc).setOnClickListener(v -> showToc());
        findViewById(R.id.search).setOnClickListener(v -> showSearch());
        findViewById(R.id.settings).setOnClickListener(v -> togglePanel());
        findViewById(R.id.more).setOnClickListener(this::showMore);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) return;
                if (pageView != null) pageLabel.setText((p + 1) + " / " + pageView.getCount());
                else pageLabel.setText(Math.round(p / 10f) + "%");
            }

            @Override public void onStartTrackingTouch(SeekBar s) { seeking = true; }

            @Override public void onStopTrackingTouch(SeekBar s) {
                seeking = false;
                if (pageView != null) pageView.goTo(s.getProgress(), 0);
                else if (flowView != null) flowView.seek(s.getProgress() / 1000f);
            }
        });

        uri = getIntent().getData();
        if (uri == null) {
            finish();
            return;
        }
        open(null);
    }

    @Override protected void onPause() {
        super.onPause();
        savePosition();
        if (flowView != null) flowView.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (flowView != null) flowView.onResume();
        if (!uiShown) setSystemBars(false);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (pageView != null) pageView.release();
        else if (doc != null && doc.pages != null) doc.close();
        if (flowView != null) {
            flowView.destroy();
            if (doc != null) doc.close();
        }
        bg.shutdown();
        linkLoader.shutdown();
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (pageView != null && level >= TRIM_MEMORY_RUNNING_LOW) pageView.trimMemory();
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (flowView != null && prefs.readerTheme() == Prefs.THEME_AUTO) applyFlowStyle();
        if (pageView != null) applyFixedOptions();
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus && !uiShown) setSystemBars(false);
    }

    // ---- window / insets -------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    private void edgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) Compat.V30.edgeToEdge(getWindow(), false);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                int[] bars = new int[4], cut = new int[4];
                Compat.V30.insets(ins, bars, cut, false);
                insetLeft = bars[0];
                insetTop = bars[1];
                insetRight = bars[2];
                insetBottom = bars[3];
                cutLeft = cut[0];
                cutTop = cut[1];
                cutRight = cut[2];
                cutBottom = cut[3];
            } else {
                insetTop = ins.getStableInsetTop();
                insetBottom = ins.getStableInsetBottom();
                insetLeft = ins.getStableInsetLeft();
                insetRight = ins.getStableInsetRight();
                if (Build.VERSION.SDK_INT >= 28 && ins.getDisplayCutout() != null) {
                    cutTop = ins.getDisplayCutout().getSafeInsetTop();
                    cutBottom = ins.getDisplayCutout().getSafeInsetBottom();
                    cutLeft = ins.getDisplayCutout().getSafeInsetLeft();
                    cutRight = ins.getDisplayCutout().getSafeInsetRight();
                }
            }
            applyInsets();
            return ins;
        });
    }

    private void applyInsets() {
        boolean full = prefs.fullscreen();
        top.setPadding(insetLeft, insetTop, insetRight, 0);
        bottom.setPadding(dp(16) + insetLeft, dp(6), dp(12) + insetRight, dp(6) + insetBottom);
        panel.setPadding(dp(16) + insetLeft, dp(8), dp(16) + insetRight, dp(12) + insetBottom);
        if (full) content.setPadding(cutLeft, cutTop, cutRight, cutBottom);
        else content.setPadding(insetLeft, insetTop, insetRight, insetBottom);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) status.getLayoutParams();
        lp.bottomMargin = full ? cutBottom : insetBottom;
        status.setLayoutParams(lp);
        ViewGroup.LayoutParams pl = panelScroll.getLayoutParams();
        pl.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        panelScroll.setLayoutParams(pl);
    }

    @SuppressWarnings("deprecation")
    private void setSystemBars(boolean show) {
        if (!prefs.fullscreen()) show = true;
        if (Build.VERSION.SDK_INT >= 30) {
            Compat.V30.systemBars(getWindow(), show);
        } else {
            int f = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (!show) f |= View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            getWindow().getDecorView().setSystemUiVisibility(f);
        }
    }

    @SuppressLint("SourceLockedOrientationActivity") // user-chosen rotation lock
    private void applyOrientation() {
        switch (prefs.orientation()) {
            case 1: setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT); break;
            case 2: setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE); break;
            default: setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
    }

    private int dp(float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }

    boolean isNight() {
        int t = prefs.appTheme();
        if (t != 0) return t == 2;
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, isNight() ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert);
    }

    // ---- opening ---------------------------------------------------------------------------------

    private void open(String password) {
        progress.setVisibility(View.VISIBLE);
        bg.execute(() -> {
            try {
                Opener.Doc d = Opener.open(getApplicationContext(), uri, password);
                Library.Book e = library.find(d.id);
                if (d.pages != null && d.pages.count() > 0) {
                    int p = e != null ? fixedPage(e.pos, d.pages.count()) : 0;
                    float[] s = new float[2];
                    try {
                        d.pages.size(p, s);
                        d.firstW = s[0];
                        d.firstH = s[1];
                    } catch (Exception ignored) {}
                }
                handler.post(() -> {
                    if (destroyed) d.close();
                    else show(d);
                });
                Covers.fromDoc(getApplicationContext(), d);
            } catch (Opener.PasswordNeeded e) {
                handler.post(this::askPassword);
            } catch (Opener.Failure e) {
                handler.post(() -> fail(getString(e.message)));
            } catch (SecurityException | FileNotFoundException e) {
                handler.post(() -> {
                    if ("file".equals(uri.getScheme()) && Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 32
                            && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
                    } else fail(getString(R.string.err_access));
                });
            } catch (Throwable e) {
                handler.post(() -> fail(getString(R.string.err_corrupt)));
            }
        });
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_STORAGE) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) open(null);
        else fail(getString(R.string.err_access));
    }

    private void askPassword() {
        progress.setVisibility(View.GONE);
        AlertDialog.Builder b = dialog();
        EditText input = new EditText(b.getContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        FrameLayout box = new FrameLayout(b.getContext());
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input);
        b.setTitle(R.string.password).setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> open(input.getText().toString()))
                .setNegativeButton(android.R.string.cancel, (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    private void fail(String msg) {
        if (destroyed) return;
        progress.setVisibility(View.GONE);
        dialog().setTitle(R.string.cannot_open).setMessage(msg)
                .setPositiveButton(android.R.string.ok, (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    private static int fixedPage(String pos, int count) {
        try {
            if (pos != null && pos.startsWith("p:")) return Math.max(0, Math.min(count - 1, Integer.parseInt(pos.split(":")[1])));
        } catch (Exception ignored) {}
        return 0;
    }

    private void show(Opener.Doc d) {
        doc = d;
        progress.setVisibility(View.GONE);
        long now = System.currentTimeMillis();
        entry = library.find(d.id);
        if (entry == null) {
            entry = new Library.Book();
            entry.id = d.id;
            entry.added = now;
        }
        entry.uri = uri.toString();
        entry.title = d.title;
        entry.author = d.author;
        entry.format = d.format;
        entry.size = d.size;
        entry.opened = now;
        library.put(entry);
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {}
        title.setText(d.title);
        if (d.pages != null) showFixed(d);
        else showFlow(d);
        boolean rtl = flowView != null ? d.book.rtl : fixedRtl();
        seek.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        findViewById(R.id.toc).setVisibility(flowView != null ? View.VISIBLE : View.GONE);
        boolean search = flowView != null || (d.pages instanceof PdfSource && PdfSource.canSearch());
        findViewById(R.id.search).setVisibility(search ? View.VISIBLE : View.GONE);
        updateLabels();
    }

    private void showFixed(Opener.Doc d) {
        pageView = new PageView(this);
        pageView.setListener(this);
        content.addView(pageView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        int count = d.pages.count();
        int page = fixedPage(entry.pos, count);
        float off = 0;
        try {
            String[] p = entry.pos.split(":");
            if (p.length > 2) off = Float.parseFloat(p[2]);
        } catch (Exception ignored) {}
        entry.pages = count;
        applyFixedOptions();
        pageView.setSource(d.pages, page, off, d.firstW, d.firstH);
        seek.setMax(Math.max(1, count - 1));
        showStatus = !d.comic;
        root.setBackgroundColor(Color.BLACK);
        status.setTextColor(d.comic ? Color.WHITE : (isNight() ? 0xffcccccc : 0xff333333));
        status.setVisibility(showStatus ? View.VISIBLE : View.GONE);
        loadLinks(page);
    }

    private void showFlow(Opener.Doc d) {
        flowView = new FlowView(this);
        flowView.setListener(this);
        content.addView(flowView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        int ch = 0, tp = -1;
        float frac = 0;
        try {
            String[] p = entry.pos != null ? entry.pos.split(":") : new String[0];
            if (p.length >= 4 && p[0].equals("c")) {
                ch = Integer.parseInt(p[1]);
                tp = Integer.parseInt(p[2]);
                frac = Float.parseFloat(p[3]);
            }
        } catch (Exception ignored) {}
        if (ch >= d.book.chapters.size()) {
            ch = 0;
            tp = -1;
        }
        seek.setMax(1000);
        showStatus = true;
        FlowView.Style s = style();
        root.setBackgroundColor(s.bg);
        status.setTextColor(s.fg);
        flowView.open(d.book, s, ch, tp, frac);
        status.setVisibility(View.VISIBLE);
    }

    // ---- fixed layout options -------------------------------------------------------------------

    private void applyFixedOptions() {
        if (pageView == null || doc == null) return;
        boolean comic = doc.comic;
        int mode = entry.mode >= 0 ? entry.mode : prefs.i(comic ? "comicMode" : "docMode", comic ? MODE_PAGED : MODE_CONTINUOUS);
        boolean rtl = entry.dir >= 0 ? entry.dir == 1 : (doc.rtl || (comic && prefs.b("comicRtl", false)));
        boolean fitWidth = entry.fit >= 0 ? entry.fit == 1 : prefs.b(comic ? "comicFitWidth" : "docFitWidth", false);
        boolean invert = entry.invert >= 0 ? entry.invert == 1 : (!comic && isNight() && prefs.b("autoInvert", true));
        int bg = comic ? Color.BLACK : (isNight() || invert ? 0xff1b1b1b : 0xffd9d9d6);
        pageView.setOptions(mode == MODE_CONTINUOUS, rtl, fitWidth, invert, prefs.animations(), prefs.tapZones(), prefs.lowMemory(), bg);
        if (!comic) status.setTextColor(isNight() || invert ? 0xffbbbbbb : 0xff333333);
    }

    private boolean fixedRtl() {
        return entry != null && doc != null && (entry.dir >= 0 ? entry.dir == 1 : doc.rtl);
    }

    // ---- reflowable style -------------------------------------------------------------------------

    private FlowView.Style style() {
        FlowView.Style s = new FlowView.Style();
        s.fontPercent = prefs.fontSize();
        s.font = prefs.font();
        s.lineHeight = prefs.lineHeight();
        s.align = prefs.textAlign();
        s.hyphens = prefs.hyphens();
        s.scroll = prefs.scrollMode();
        s.marginH = dp(prefs.margin());
        s.marginV = dp(Math.max(10, Math.min(prefs.margin(), 28)));
        s.insetTop = dp(4);
        s.insetBottom = dp(14);
        int t = prefs.readerTheme();
        if (t == Prefs.THEME_AUTO) t = isNight() ? Prefs.THEME_DARK : Prefs.THEME_LIGHT;
        switch (t) {
            case Prefs.THEME_SEPIA:
                s.bg = 0xfff4ecd8; s.fg = 0xff3b2f20; s.link = 0xff7a4b16; s.forceBg = true; break;
            case Prefs.THEME_DARK:
                s.bg = 0xff1e1e1e; s.fg = 0xffd6d3cd; s.link = 0xff8ab4f8; s.forceBg = true; s.forceFg = true; break;
            case Prefs.THEME_BLACK:
                s.bg = 0xff000000; s.fg = 0xffc9c9c9; s.link = 0xff8ab4f8; s.forceBg = true; s.forceFg = true; break;
            default:
                s.bg = 0xfffbfbf8; s.fg = 0xff1c1b1a; s.link = 0xff1a5fb4;
        }
        return s;
    }

    private void applyFlowStyle() {
        if (flowView == null) return;
        FlowView.Style s = style();
        root.setBackgroundColor(s.bg);
        status.setTextColor(s.fg);
        flowView.setStyle(s);
    }

    // ---- UI chrome --------------------------------------------------------------------------------

    private void showUi(boolean show) {
        uiShown = show;
        top.setVisibility(show ? View.VISIBLE : View.GONE);
        bottom.setVisibility(show && !panelVisible() ? View.VISIBLE : View.GONE);
        if (!show) hidePanel();
        status.setVisibility(!show && showStatus ? View.VISIBLE : View.GONE);
        setSystemBars(show);
        updateLabels();
    }

    private boolean panelVisible() { return panelScroll.getVisibility() == View.VISIBLE; }

    private void hidePanel() {
        if (!panelVisible()) return;
        panelScroll.setVisibility(View.GONE);
        if (uiShown) bottom.setVisibility(View.VISIBLE);
        updateBack();
    }

    private void togglePanel() {
        if (panelVisible()) {
            hidePanel();
            return;
        }
        buildPanel();
        panelScroll.setVisibility(View.VISIBLE);
        bottom.setVisibility(View.GONE);
        int max = (int) (getResources().getDisplayMetrics().heightPixels * 0.6f);
        panelScroll.measure(View.MeasureSpec.makeMeasureSpec(root.getWidth(), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        ViewGroup.LayoutParams lp = panelScroll.getLayoutParams();
        lp.height = panelScroll.getMeasuredHeight() > max ? max : ViewGroup.LayoutParams.WRAP_CONTENT;
        panelScroll.setLayoutParams(lp);
        updateBack();
    }

    private void updateLabels() {
        if (pageView != null) {
            int p = pageView.getPage(), n = pageView.getCount();
            String t = (p + 1) + " / " + n;
            pageLabel.setText(t);
            status.setText(t);
            chapterLabel.setVisibility(View.GONE);
            if (!seeking) seek.setProgress(p);
        } else if (flowView != null && flowView.book() != null) {
            FlowBook b = flowView.book();
            String ch = chapterTitle(b, flowView.chapter());
            chapterLabel.setVisibility(ch != null ? View.VISIBLE : View.GONE);
            chapterLabel.setText(ch);
            float prog = flowView.progress();
            int pct = Math.round(prog * 100);
            pageLabel.setText(pct + "%");
            status.setText(flowView.isLoading() ? "" : (flowView.page() + 1) + " / " + flowView.pages() + "  ·  " + pct + "%");
            if (!seeking) seek.setProgress(Math.round(prog * 1000));
        }
    }

    private static String chapterTitle(FlowBook b, int ch) {
        String t = null;
        for (FlowBook.TocEntry e : b.toc) {
            if (e.chapter >= 0 && e.chapter <= ch) t = e.title;
            if (e.chapter > ch) break;
        }
        return t;
    }

    // ---- position ---------------------------------------------------------------------------------

    private final Runnable saveLater = this::savePosition;

    private void scheduleSave() {
        handler.removeCallbacks(saveLater);
        handler.postDelayed(saveLater, 1500);
    }

    private void savePosition() {
        handler.removeCallbacks(saveLater);
        if (entry == null) return;
        entry.opened = System.currentTimeMillis();
        if (pageView != null && pageView.getCount() > 0) {
            int p = pageView.getPage();
            entry.pos = "p:" + p + ":" + String.format(Locale.ROOT, "%.4f", pageView.pageOffset());
            entry.progress = (p + 1f) / pageView.getCount();
            library.put(entry);
        } else if (flowView != null && !flowView.isLoading() && flowView.chapter() >= 0) {
            float frac = flowView.pages() > 0 ? flowView.page() / (float) flowView.pages() : 0;
            entry.pos = "c:" + flowView.chapter() + ":" + flowView.textPos() + ":" + String.format(Locale.ROOT, "%.4f", frac);
            entry.progress = flowView.progress();
            library.put(entry);
        }
    }

    // ---- listeners from the views -------------------------------------------------------------

    @Override public void onPageChanged(int page) {
        updateLabels();
        scheduleSave();
        loadLinks(page);
        pageView.highlight(-1, null);
    }

    @Override public void onCenterTap() { showUi(!uiShown); }

    @Override public boolean onPageTap(int page, float x, float y) {
        List<Object[]> links = pdfLinks.get(page);
        if (links == null) return false;
        for (Object[] l : links) {
            if (!((RectF) l[0]).contains(x, y)) continue;
            if (l[1] instanceof Integer) pageView.goTo((Integer) l[1], 0);
            else onExternalLink((String) l[1]);
            return true;
        }
        return false;
    }

    /** PDF links (Android 15+) are fetched per page in the background so taps never block. */
    private void loadLinks(int page) {
        if (doc == null || !(doc.pages instanceof PdfSource) || !PdfSource.canSearch()) return;
        final PdfSource pdf = (PdfSource) doc.pages;
        for (int p = page; p <= page + 1; p++) {
            if (pdfLinks.containsKey(p) || p >= pdf.count()) continue;
            final int fp = p;
            pdfLinks.put(fp, new ArrayList<>());
            linkLoader.execute(() -> {
                try {
                    List<Object[]> l = pdf.links(fp);
                    handler.post(() -> pdfLinks.put(fp, l));
                } catch (Throwable ignored) {}
            });
        }
    }

    @Override public void onFlowPosition() {
        updateLabels();
        scheduleSave();
        updateBack();
    }

    @Override public void onExternalLink(String url) {
        dialog().setTitle(R.string.open_link).setMessage(url)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (ActivityNotFoundException | SecurityException e) {
                        Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    @Override public void onActionModeStarted(ActionMode mode) {
        super.onActionModeStarted(mode);
        if (flowView != null) flowView.setSelecting(true);
    }

    @Override public void onActionModeFinished(ActionMode mode) {
        super.onActionModeFinished(mode);
        if (flowView != null) flowView.setSelecting(false);
    }

    // ---- navigation & keys ---------------------------------------------------------------------

    private void next() {
        if (pageView != null) pageView.next();
        else if (flowView != null) flowView.next();
    }

    private void prev() {
        if (pageView != null) pageView.prev();
        else if (flowView != null) flowView.prev();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean vol = code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_UP;
        if (doc != null && vol && prefs.volumeKeys()) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (code == KeyEvent.KEYCODE_VOLUME_DOWN) next(); else prev();
            }
            return true;
        }
        if (doc != null && !panelVisible() && e.getAction() == KeyEvent.ACTION_DOWN) {
            boolean rtl = pageView != null ? fixedRtl() : flowView != null && flowView.book() != null && flowView.book().rtl;
            switch (code) {
                case KeyEvent.KEYCODE_PAGE_DOWN: case KeyEvent.KEYCODE_SPACE: case KeyEvent.KEYCODE_DPAD_DOWN:
                    next();
                    return true;
                case KeyEvent.KEYCODE_PAGE_UP: case KeyEvent.KEYCODE_DPAD_UP:
                    prev();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (rtl) prev(); else next();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (rtl) next(); else prev();
                    return true;
                case KeyEvent.KEYCODE_MENU:
                    showUi(!uiShown);
                    return true;
                default: break;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private boolean handleBack() {
        if (panelVisible()) {
            hidePanel();
            return true;
        }
        if (flowView != null && flowView.canGoBack()) {
            flowView.goBack();
            return true;
        }
        return false;
    }

    /** Back before Android 13 (newer versions use {@link #updateBack()}). */
    @SuppressLint("GestureBackNavigation")
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (!handleBack()) super.onBackPressed();
    }

    /** Predictive back (Android 13+): only intercept while there is something to go back to. */
    private void updateBack() {
        if (Build.VERSION.SDK_INT < 33) return;
        boolean need = panelVisible() || (flowView != null && flowView.canGoBack());
        if (need && backCallback == null) {
            backCallback = Compat.V33.registerBack(this, () -> {
                handleBack();
                updateBack();
            });
        } else if (!need && backCallback != null) {
            Compat.V33.unregisterBack(this, backCallback);
            backCallback = null;
        }
    }

    // ---- settings panel ---------------------------------------------------------------------------

    private void buildPanel() {
        panel.removeAllViews();
        if (flowView != null) {
            choices(R.string.theme, new int[]{R.string.theme_auto, R.string.theme_light, R.string.theme_sepia, R.string.theme_dark, R.string.theme_black},
                    prefs.readerTheme(), i -> { prefs.put("readerTheme", i); applyFlowStyle(); });
            stepper(R.string.font_size, prefs.fontSize() + "%", d -> {
                prefs.put("fontSize", Math.max(50, Math.min(300, prefs.fontSize() + d * 10)));
                applyFlowStyle();
                return prefs.fontSize() + "%";
            });
            choices(R.string.font, new int[]{R.string.font_book, R.string.font_serif, R.string.font_sans, R.string.font_mono},
                    prefs.font(), i -> { prefs.put("font", i); applyFlowStyle(); });
            final int[] lh = {0, 12, 14, 16, 18, 20};
            int cur = 0;
            for (int i = 0; i < lh.length; i++) if (lh[i] == prefs.lineHeight()) cur = i;
            choicesText(R.string.line_spacing, new String[]{getString(R.string.font_book), "1.2", "1.4", "1.6", "1.8", "2.0"}, cur,
                    i -> { prefs.put("lineHeight", lh[i]); applyFlowStyle(); });
            stepper(R.string.margins, prefs.margin() + "", d -> {
                prefs.put("margin", Math.max(0, Math.min(64, prefs.margin() + d * 4)));
                applyFlowStyle();
                return prefs.margin() + "";
            });
            choices(R.string.alignment, new int[]{R.string.font_book, R.string.justify, R.string.left},
                    prefs.textAlign(), i -> { prefs.put("align", i); applyFlowStyle(); });
            choices(R.string.layout, new int[]{R.string.pages, R.string.scroll},
                    prefs.scrollMode() ? 1 : 0, i -> { prefs.put("scroll", i == 1); applyFlowStyle(); });
            toggle(R.string.hyphenation, prefs.hyphens(), v -> { prefs.put("hyphens", v); applyFlowStyle(); });
        } else if (pageView != null) {
            boolean comic = doc.comic;
            int mode = entry.mode >= 0 ? entry.mode : prefs.i(comic ? "comicMode" : "docMode", comic ? MODE_PAGED : MODE_CONTINUOUS);
            choices(R.string.layout, new int[]{R.string.pages, R.string.continuous}, mode, i -> { entry.mode = i; applyFixedOptions(); });
            choices(R.string.direction, new int[]{R.string.ltr, R.string.rtl}, fixedRtl() ? 1 : 0, i -> {
                entry.dir = i;
                applyFixedOptions();
                seek.setLayoutDirection(i == 1 ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
            });
            boolean fw = entry.fit >= 0 ? entry.fit == 1 : prefs.b(comic ? "comicFitWidth" : "docFitWidth", false);
            choices(R.string.fit, new int[]{R.string.fit_page, R.string.fit_width}, fw ? 1 : 0, i -> { entry.fit = i; applyFixedOptions(); });
            boolean inv = entry.invert >= 0 ? entry.invert == 1 : (!comic && isNight() && prefs.b("autoInvert", true));
            choices(R.string.colors, new int[]{R.string.colors_normal, R.string.colors_invert}, inv ? 1 : 0, i -> { entry.invert = i; applyFixedOptions(); });
            TextView def = new TextView(this);
            def.setText(comic ? R.string.default_comics : R.string.default_docs);
            def.setTextColor(0xff8ccfbb);
            def.setTextSize(14);
            def.setPadding(0, dp(14), 0, dp(6));
            def.setOnClickListener(v -> {
                String k = comic ? "comic" : "doc";
                if (entry.mode >= 0) prefs.put(k + "Mode", entry.mode);
                if (entry.fit >= 0) prefs.put(k + "FitWidth", entry.fit == 1);
                if (comic && entry.dir >= 0) prefs.put("comicRtl", entry.dir == 1);
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
            });
            panel.addView(def);
        }
        choices(R.string.rotation, new int[]{R.string.rotation_auto, R.string.rotation_portrait, R.string.rotation_landscape},
                prefs.orientation(), i -> { prefs.put("orientation", i); applyOrientation(); });
    }

    private TextView label(int res) {
        TextView t = new TextView(this);
        t.setText(res);
        t.setTextColor(0xb3ffffff);
        t.setTextSize(12);
        t.setAllCaps(true);
        t.setPadding(0, dp(12), 0, dp(6));
        panel.addView(t);
        return t;
    }

    private GradientDrawable chip(boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(18));
        if (on) g.setColor(0xff8ccfbb);
        else g.setStroke(dp(1), 0x66ffffff);
        return g;
    }

    private void choices(int labelRes, int[] options, int selected, IntPick pick) {
        String[] s = new String[options.length];
        for (int i = 0; i < s.length; i++) s[i] = getString(options[i]);
        choicesText(labelRes, s, selected, pick);
    }

    private void choicesText(int labelRes, String[] options, int selected, IntPick pick) {
        label(labelRes);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        List<TextView> views = new ArrayList<>();
        for (int i = 0; i < options.length; i++) {
            TextView t = new TextView(this);
            t.setText(options[i]);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(13);
            t.setMaxLines(1);
            t.setPadding(dp(6), dp(8), dp(6), dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(36), 1);
            lp.setMarginEnd(i < options.length - 1 ? dp(6) : 0);
            row.addView(t, lp);
            views.add(t);
            final int idx = i;
            t.setOnClickListener(v -> {
                for (int k = 0; k < views.size(); k++) styleChip(views.get(k), k == idx);
                pick.pick(idx);
            });
            styleChip(t, i == selected);
        }
        panel.addView(row);
    }

    private void styleChip(TextView t, boolean on) {
        t.setBackground(chip(on));
        t.setTextColor(on ? 0xff10201b : 0xffffffff);
    }

    interface Step {
        String step(int delta);
    }

    private void stepper(int labelRes, String value, Step step) {
        label(labelRes);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView minus = new TextView(this), val = new TextView(this), plus = new TextView(this);
        minus.setText("−");
        plus.setText("+");
        val.setText(value);
        for (TextView t : new TextView[]{minus, plus}) {
            t.setTextSize(20);
            t.setGravity(Gravity.CENTER);
            styleChip(t, false);
        }
        val.setTextColor(Color.WHITE);
        val.setTextSize(15);
        val.setGravity(Gravity.CENTER);
        row.addView(minus, new LinearLayout.LayoutParams(dp(72), dp(36)));
        row.addView(val, new LinearLayout.LayoutParams(0, dp(36), 1));
        row.addView(plus, new LinearLayout.LayoutParams(dp(72), dp(36)));
        minus.setOnClickListener(v -> val.setText(step.step(-1)));
        plus.setOnClickListener(v -> val.setText(step.step(1)));
        panel.addView(row);
    }

    interface BoolPick {
        void pick(boolean v);
    }

    private void toggle(int labelRes, boolean on, BoolPick pick) {
        Switch s = new Switch(this);
        s.setText(labelRes);
        s.setTextColor(Color.WHITE);
        s.setTextSize(15);
        s.setChecked(on);
        s.setPadding(0, dp(12), 0, dp(4));
        s.setOnCheckedChangeListener((b, v) -> pick.pick(v));
        panel.addView(s);
    }

    // ---- dialogs ----------------------------------------------------------------------------------

    private void showMore(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, pageView != null ? R.string.go_to_page : R.string.go_to_percent);
        m.getMenu().add(0, 2, 0, R.string.book_info);
        m.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) showGoTo();
            else showInfo();
            return true;
        });
        m.show();
    }

    private void showGoTo() {
        AlertDialog.Builder b = dialog();
        EditText input = new EditText(b.getContext());
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(pageView != null ? "1 - " + pageView.getCount() : "0 - 100");
        FrameLayout box = new FrameLayout(b.getContext());
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input);
        AlertDialog d = b.setTitle(pageView != null ? R.string.go_to_page : R.string.go_to_percent).setView(box)
                .setPositiveButton(android.R.string.ok, (dlg, w) -> {
                    try {
                        int v = Integer.parseInt(input.getText().toString().trim());
                        if (pageView != null) pageView.goTo(v - 1, 0);
                        else if (flowView != null) flowView.seek(Math.max(0, Math.min(100, v)) / 100f);
                    } catch (NumberFormatException ignored) {}
                })
                .setNegativeButton(android.R.string.cancel, null).create();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }

    private void showInfo() {
        if (doc == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.info_title)).append(": ").append(doc.title).append('\n');
        if (doc.author != null) sb.append(getString(R.string.info_author)).append(": ").append(doc.author).append('\n');
        sb.append(getString(R.string.info_format)).append(": ").append(doc.format).append('\n');
        if (pageView != null) sb.append(getString(R.string.info_pages)).append(": ").append(pageView.getCount()).append('\n');
        if (flowView != null) sb.append(getString(R.string.info_chapters)).append(": ").append(doc.book.chapters.size()).append('\n');
        sb.append(getString(R.string.info_file)).append(": ").append(doc.name).append('\n');
        sb.append(getString(R.string.info_size)).append(": ").append(Formatter.formatShortFileSize(this, doc.size));
        dialog().setTitle(R.string.book_info).setMessage(sb).setPositiveButton(android.R.string.ok, null).show();
    }

    private void showToc() {
        if (flowView == null || flowView.book() == null) return;
        final List<FlowBook.TocEntry> toc = flowView.book().toc;
        if (toc.isEmpty()) {
            Toast.makeText(this, R.string.no_toc, Toast.LENGTH_SHORT).show();
            return;
        }
        int current = 0;
        for (int i = 0; i < toc.size(); i++) if (toc.get(i).chapter >= 0 && toc.get(i).chapter <= flowView.chapter()) current = i;
        final int sel = current;
        AlertDialog.Builder b = dialog();
        ArrayAdapter<FlowBook.TocEntry> a = new ArrayAdapter<FlowBook.TocEntry>(b.getContext(), android.R.layout.simple_list_item_1, toc) {
            @Override public View getView(int pos, View v, ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, v, parent);
                FlowBook.TocEntry e = getItem(pos);
                t.setText(e.title);
                t.setPadding(dp(20 + 16 * Math.min(e.depth, 5)), dp(4), dp(16), dp(4));
                t.setTypeface(null, pos == sel ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                t.setAlpha(e.chapter < 0 ? 0.6f : 1f);
                return t;
            }
        };
        AlertDialog d = b.setTitle(R.string.contents).setAdapter(a, (dlg, which) -> {
            FlowBook.TocEntry e = toc.get(which);
            int ch = e.chapter;
            for (int k = which + 1; ch < 0 && k < toc.size(); k++) ch = toc.get(k).chapter;
            if (ch >= 0) {
                flowView.goChapter(ch, e.chapter >= 0 ? e.anchor : null);
                showUi(false);
            }
        }).setNegativeButton(android.R.string.cancel, null).create();
        d.show();
        d.getListView().setSelection(Math.max(0, sel - 3));
    }

    private void showSearch() {
        AlertDialog.Builder b = dialog();
        android.content.Context c = b.getContext();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);
        EditText input = new EditText(c);
        input.setSingleLine(true);
        input.setHint(R.string.search_hint);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        ProgressBar spinner = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        spinner.setIndeterminate(true);
        spinner.setVisibility(View.INVISIBLE);
        ListView list = new ListView(c);
        final List<Object[]> hits = new ArrayList<>();
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(c, android.R.layout.simple_list_item_1, new ArrayList<>());
        list.setAdapter(adapter);
        box.addView(input);
        box.addView(spinner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));
        box.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)));
        final AlertDialog d = b.setTitle(R.string.search).setView(box).setNegativeButton(android.R.string.cancel, null).create();
        final boolean[] cancel = {false};
        final String[] query = {null};
        d.setOnDismissListener(x -> cancel[0] = true);
        list.setOnItemClickListener((p, v, pos, id) -> {
            Object[] h = hits.get(pos);
            if (flowView != null) flowView.goFind((Integer) h[0], query[0], (Integer) h[1]);
            else if (pageView != null) {
                pageView.goTo((Integer) h[0], 0);
                @SuppressWarnings("unchecked") List<RectF> rects = (List<RectF>) h[1];
                pageView.highlight((Integer) h[0], rects);
            }
            d.dismiss();
            showUi(false);
        });
        input.setOnEditorActionListener((v, action, ev) -> {
            String q = input.getText().toString().trim();
            if (q.isEmpty()) return true;
            query[0] = q;
            hits.clear();
            adapter.clear();
            spinner.setVisibility(View.VISIBLE);
            final int token = q.hashCode() ^ (int) System.nanoTime();
            cancel[0] = false;
            list.setTag(token);
            new Thread(() -> runSearch(q, hits, adapter, spinner, list, token, cancel), "search").start();
            return true;
        });
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }

    private void runSearch(String q, List<Object[]> hits, ArrayAdapter<String> adapter, ProgressBar spinner, ListView list, int token, boolean[] cancel) {
        String lq = q.toLowerCase(Locale.getDefault());
        int found = 0;
        try {
            if (flowView != null && doc != null && doc.book != null) {
                FlowBook b = doc.book;
                for (int ch = 0; ch < b.chapters.size() && !cancel[0] && found < 300; ch++) {
                    String text = b.chapterText(ch);
                    String lower = text.toLowerCase(Locale.getDefault());
                    int n = 0;
                    List<Object[]> batch = new ArrayList<>();
                    List<String> labels = new ArrayList<>();
                    for (int i = lower.indexOf(lq); i >= 0 && found < 300; i = lower.indexOf(lq, i + Math.max(1, lq.length()))) {
                        batch.add(new Object[]{ch, n++});
                        labels.add(snippet(text, i, q.length()));
                        found++;
                    }
                    if (!batch.isEmpty()) postHits(hits, adapter, list, token, batch, labels);
                }
            } else if (doc != null && doc.pages instanceof PdfSource && PdfSource.canSearch()) {
                PdfSource pdf = (PdfSource) doc.pages;
                for (int p = 0; p < pdf.count() && !cancel[0] && found < 300; p++) {
                    List<RectF> rects = pdf.search(p, q);
                    if (rects.isEmpty()) continue;
                    String text = pdf.text(p);
                    int i = text.toLowerCase(Locale.getDefault()).indexOf(lq);
                    List<Object[]> batch = new ArrayList<>();
                    List<String> labels = new ArrayList<>();
                    batch.add(new Object[]{p, rects});
                    labels.add(getString(R.string.page_n, p + 1) + "   " + (i >= 0 ? snippet(text, i, q.length()) : ""));
                    found++;
                    postHits(hits, adapter, list, token, batch, labels);
                }
            }
        } catch (Throwable ignored) {}
        final int total = found;
        handler.post(() -> {
            if (!Integer.valueOf(token).equals(list.getTag())) return;
            spinner.setVisibility(View.INVISIBLE);
            if (total == 0) Toast.makeText(this, R.string.no_results, Toast.LENGTH_SHORT).show();
        });
    }

    private void postHits(List<Object[]> hits, ArrayAdapter<String> adapter, ListView list, int token, List<Object[]> batch, List<String> labels) {
        handler.post(() -> {
            if (!Integer.valueOf(token).equals(list.getTag())) return;
            hits.addAll(batch);
            adapter.addAll(labels);
        });
    }

    private static String snippet(String text, int at, int len) {
        int s = Math.max(0, at - 40), e = Math.min(text.length(), at + len + 60);
        String t = text.substring(s, e).replaceAll("\\s+", " ").trim();
        return (s > 0 ? "…" : "") + t + (e < text.length() ? "…" : "");
    }
}
