package org.recto.reader.flow;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;
import org.recto.reader.screen.Spread;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reflowable book viewer: one WebView showing one chapter at a time, paginated with CSS columns
 * (or scrolled). On large book-shaped windows (unfolded foldables, tablets) a screen shows two
 * columns side by side like an open book (see {@link Spread}). Content is served from the book through request interception only - no network,
 * no file access, book scripts disabled by a Content-Security-Policy.
 */
@SuppressLint("ViewConstructor")
public final class FlowView extends FrameLayout {
    public interface Listener {
        void onFlowPosition();

        void onCenterTap();

        void onExternalLink(String url);
    }

    /** Typography and layout settings. Margins and insets in px. */
    public static final class Style {
        public int fontPercent = 100, font, lineHeight, align, marginH, marginV, insetTop, insetBottom, insetLeft, insetRight;
        public int bg = 0xffffffff, fg = 0xff1a1a1a, link = 0xff1a5fb4;
        public boolean forceBg, forceFg, hyphens, scroll;
        /** Absolute URL of a user font on the book origin, or null. Never a file path. */
        public String userFontUrl;

        public Style copy() {
            Style s = new Style();
            s.fontPercent = fontPercent; s.font = font; s.lineHeight = lineHeight; s.align = align;
            s.marginH = marginH; s.marginV = marginV; s.insetTop = insetTop; s.insetBottom = insetBottom;
            s.insetLeft = insetLeft; s.insetRight = insetRight; s.bg = bg; s.fg = fg; s.link = link;
            s.forceBg = forceBg; s.forceFg = forceFg; s.hyphens = hyphens; s.scroll = scroll;
            s.userFontUrl = userFontUrl;
            return s;
        }
    }

    private static final String[] FONTS = {null, "serif", "sans-serif", "monospace"};
    private static final Pattern HEAD_OPEN = Pattern.compile("<head\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEAD_CLOSE = Pattern.compile("</head\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern BODY_OPEN = Pattern.compile("<body\\b", Pattern.CASE_INSENSITIVE);
    private static final String CSP = "script-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; "
            + "frame-src 'none'; worker-src 'none'; connect-src 'none'; manifest-src 'none'";
    private static String readerJs;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final float density;
    private final GestureDetector swipes;
    private final String base;
    private WebView web;
    private Listener listener;
    private FlowBook book;
    private Style style = new Style();
    private int chapter = -1, page, pages = 1, textPos = -1;
    private boolean loading, selecting, tapZones = true, destroyed;
    private int spreadMode = Spread.OFF;
    private File userFontFile;
    private String userFontMime = "font/ttf";
    private String target;
    private final List<int[]> back = new ArrayList<>(); // chapter, text position
    private long[] cumulative;

    public FlowView(Context c) {
        super(c);
        density = getResources().getDisplayMetrics().density;
        base = "https://recto.invalid/" + Long.toHexString(System.nanoTime()) + "/";
        swipes = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (style.scroll || selecting || e1 == null) return false;
                float dx = e2.getX() - e1.getX(), dy = e2.getY() - e1.getY();
                if (Math.abs(dx) < 40 * density || Math.abs(dx) < Math.abs(dy) * 1.2f) return false;
                if ((dx < 0) != (book != null && book.rtl)) next(); else prev();
                return true;
            }
        });
        if (readerJs == null) {
            try (InputStream in = c.getAssets().open("reader.js")) {
                readerJs = new String(org.recto.reader.util.Text.readAll(in, 1 << 20), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        createWeb();
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface", "ClickableViewAccessibility"})
    @SuppressWarnings("deprecation")
    private void createWeb() {
        if ((getContext().getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            WebView.setWebContentsDebuggingEnabled(true); // debug builds only
        web = new WebView(getContext());
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setDomStorageEnabled(false);
        ws.setDatabaseEnabled(false);
        ws.setGeolocationEnabled(false);
        ws.setSupportZoom(false);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        ws.setUseWideViewPort(false);
        ws.setLoadWithOverviewMode(false);
        ws.setMediaPlaybackRequiresUserGesture(true);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
        ws.setDefaultTextEncodingName("utf-8");
        ws.setTextZoom(style.fontPercent);
        ws.setSupportMultipleWindows(false);
        ws.setJavaScriptCanOpenWindowsAutomatically(false);
        if (Build.VERSION.SDK_INT >= 26) ws.setSafeBrowsingEnabled(false);
        if (Build.VERSION.SDK_INT >= 33) ws.setAlgorithmicDarkeningAllowed(false);
        else if (Build.VERSION.SDK_INT >= 29) ws.setForceDark(WebSettings.FORCE_DARK_OFF);
        web.setBackgroundColor(style.bg);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(OVER_SCROLL_NEVER);
        web.addJavascriptInterface(new Bridge(), "Recto");
        web.setWebViewClient(Build.VERSION.SDK_INT >= 26 ? new Client26() : new Client());
        web.setOnTouchListener((v, e) -> {
            swipes.onTouchEvent(e);
            return false;
        });
        addView(web, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    public void setListener(Listener l) { listener = l; }

    public void setSelecting(boolean s) { selecting = s; }

    /** Side taps turn pages (true) or every tap opens the menu (false). */
    public void setTapZones(boolean on) { tapZones = on; }

    /** Two pages per screen: {@link Spread#AUTO}, {@link Spread#OFF} or {@link Spread#ON}. */
    public void setSpreadMode(int mode) {
        if (spreadMode == mode) return;
        boolean was = isSpread();
        spreadMode = mode;
        if (isSpread() != was && !loading && chapter >= 0)
            web.evaluateJavascript("__R.apply(null," + layoutOpts(style) + ")", null);
    }

    /** True while the current window shows two pages per screen. */
    public boolean isSpread() { return !style.scroll && Spread.twoPages(spreadMode, getWidth(), getHeight(), density); }

    public FlowBook book() { return book; }

    public int chapter() { return chapter; }

    public int page() { return page; }

    public int pages() { return pages; }

    public boolean isLoading() { return loading; }

    /** User typeface copied into app files. The page fetches it by the intercepted path, not this file. */
    public void setUserFont(File file, String mime) {
        userFontFile = file != null && file.isFile() ? file : null;
        if (mime != null && !mime.isEmpty()) userFontMime = mime;
    }

    private Style adopt(Style s) {
        Style c = s.copy();
        if (c.font == UserFont.SLOT && userFontFile != null) c.userFontUrl = base + UserFont.PATH;
        else c.userFontUrl = null;
        return c;
    }

    /** Opens a book at a chapter with a target: text offset (&gt;= 0) or fraction of the chapter. */
    public void open(FlowBook b, Style s, int ch, int textPos, float frac) {
        book = b;
        style = adopt(s);
        web.getSettings().setTextZoom(style.fontPercent);
        web.setBackgroundColor(style.bg);
        cumulative = new long[b.chapters.size() + 1];
        for (int i = 0; i < b.chapters.size(); i++) cumulative[i + 1] = cumulative[i] + b.chapters.get(i).weight;
        ch = Math.max(0, Math.min(b.chapters.size() - 1, ch));
        load(ch, textPos >= 0 ? textTarget(textPos) : "{\"t\":\"frac\",\"v\":" + frac + "}");
    }

    private static String textTarget(int pos) { return "{\"t\":\"text\",\"v\":" + pos + "}"; }

    private void load(int ch, String t) {
        chapter = ch;
        target = t;
        loading = true;
        page = 0;
        pages = 1;
        textPos = -1;
        web.setAlpha(0f);
        web.loadUrl(base + encodePath(book.chapters.get(ch).path));
        if (listener != null) listener.onFlowPosition();
    }

    private static String encodePath(String path) {
        StringBuilder sb = new StringBuilder();
        for (String seg : path.split("/", -1)) {
            if (sb.length() > 0) sb.append('/');
            sb.append(Uri.encode(seg));
        }
        return sb.toString();
    }

    public void next() {
        if (loading || book == null) return;
        web.evaluateJavascript("__R.next()", r -> {
            if ("0".equals(r) && !loading && chapter + 1 < book.chapters.size()) load(chapter + 1, "{\"t\":\"page\",\"v\":0}");
        });
    }

    public void prev() {
        if (loading || book == null) return;
        web.evaluateJavascript("__R.prev()", r -> {
            if ("0".equals(r) && !loading && chapter > 0) load(chapter - 1, "{\"t\":\"last\"}");
        });
    }

    /** Jumps to a stored position: chapter, character offset, and fraction of that chapter. */
    public void goPos(int ch, int textPos, float frac) {
        if (book == null || book.chapters.isEmpty()) return;
        ch = Math.max(0, Math.min(book.chapters.size() - 1, ch));
        load(ch, textPos >= 0 ? textTarget(textPos) : "{\"t\":\"frac\",\"v\":" + frac + "}");
    }

    public void goChapter(int ch, String anchor) {
        if (book == null || ch < 0 || ch >= book.chapters.size()) return;
        String t = anchor != null ? "{\"t\":\"anchor\",\"v\":" + JSONObject.quote(anchor) + "}" : "{\"t\":\"page\",\"v\":0}";
        if (ch == chapter && !loading) {
            web.evaluateJavascript(anchor != null ? "__R.goAnchor(" + JSONObject.quote(anchor) + ")" : "__R.go(0)", null);
        } else load(ch, t);
    }

    /**
     * Shows the n-th match of {@code query} in chapter {@code ch}. {@code ctx} is
     * {@link #matchContext} of that match, used to find it even if the page counts differently.
     */
    public void goFind(int ch, String query, int n, String ctx) {
        if (book == null) return;
        String q = JSONObject.quote(query), c = JSONObject.quote(ctx == null ? "" : ctx);
        if (ch == chapter && !loading) {
            web.evaluateJavascript("__R.find(" + q + "," + n + "," + c + ")", null);
        } else load(ch, "{\"t\":\"find\",\"v\":" + q + ",\"n\":" + n + ",\"c\":" + c + "}");
    }

    /**
     * Text just before a match (lower-cased chapter text, whitespace removed, at most 24 chars).
     * Must stay in sync with {@code squash} / {@code follows} in reader.js.
     */
    public static String matchContext(String lower, int at) {
        StringBuilder sb = new StringBuilder(24);
        for (int i = at - 1; i >= Math.max(0, at - 200) && sb.length() < 24; i--) {
            char c = lower.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\u200b' || c == '\ufeff') continue;
            sb.append(c);
        }
        return sb.reverse().toString();
    }

    /** Global position 0..1 for the progress slider. */
    public float progress() {
        if (book == null || cumulative == null || chapter < 0) return 0;
        long total = cumulative[cumulative.length - 1];
        float w = book.chapters.get(chapter).weight;
        return (cumulative[chapter] + w * (pages <= 1 ? 0 : page / (float) (pages - 1))) / total;
    }

    public void seek(float f) {
        if (book == null || cumulative == null) return;
        long total = cumulative[cumulative.length - 1];
        long t = (long) (Math.max(0, Math.min(1, f)) * total);
        int ch = 0;
        while (ch < book.chapters.size() - 1 && cumulative[ch + 1] <= t) ch++;
        float frac = Math.max(0, Math.min(0.999f, (t - cumulative[ch]) / (float) book.chapters.get(ch).weight));
        if (ch == chapter && !loading) web.evaluateJavascript("__R.goFrac(" + frac + ")", null);
        else load(ch, "{\"t\":\"frac\",\"v\":" + frac + "}");
    }

    /** Reading position inside the chapter as a character offset (-1 while loading). */
    public int textPos() { return loading ? -1 : textPos; }

    public boolean canGoBack() { return !back.isEmpty(); }

    public void goBack() {
        if (back.isEmpty()) return;
        int[] p = back.remove(back.size() - 1);
        load(p[0], textTarget(p[1]));
    }

    public void setStyle(Style s) {
        boolean zoom = s.fontPercent != style.fontPercent;
        style = adopt(s);
        web.setBackgroundColor(style.bg);
        if (loading || chapter < 0) {
            web.getSettings().setTextZoom(style.fontPercent);
            return;
        }
        final String css = JSONObject.quote(themeCss(style)), o = layoutOpts(style);
        if (zoom) {
            web.evaluateJavascript("__R.mark()", r -> {
                web.getSettings().setTextZoom(style.fontPercent);
                web.evaluateJavascript("__R.restore(" + css + "," + o + ")", null);
            });
        } else {
            web.evaluateJavascript("__R.apply(" + css + "," + o + ")", null);
        }
    }

    private String layoutOpts(Style s) {
        float d = density;
        int t = Math.round((s.marginV + s.insetTop) / d), b = Math.round((s.marginV + s.insetBottom) / d);
        int l = Math.round((s.marginH + s.insetLeft) / d), r = Math.round((s.marginH + s.insetRight) / d);
        return "{\"m\":[" + t + "," + r + "," + b + "," + l + "],\"scroll\":" + s.scroll + sizeOpts() + "}";
    }

    /** Exact viewport size in CSS px (the WebView fills this view) and the two-page flag. */
    private String sizeOpts() {
        if (getWidth() == 0 || getHeight() == 0) return "";
        return String.format(Locale.ROOT, ",\"w\":%.4f,\"h\":%.4f,\"spread\":%b", getWidth() / density, getHeight() / density, isSpread());
    }

    /** Rotation, split screen, folding / unfolding: re-layout, switching between one and two pages. */
    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (ow != 0 && oh != 0 && !loading && chapter >= 0)
            web.evaluateJavascript(String.format(Locale.ROOT, "__R.resize(%.4f,%.4f,%b)", w / density, h / density, isSpread()), null);
    }

    static String hex(int c) { return String.format(Locale.ROOT, "#%06x", c & 0xffffff); }

    static String themeCss(Style s) {
        StringBuilder c = new StringBuilder(512);
        c.append("html{background-color:").append(hex(s.bg)).append("!important;-webkit-text-size-adjust:none}");
        c.append("body{background-color:transparent!important;color:").append(hex(s.fg)).append(s.forceFg ? "!important}" : "}");
        if (s.forceBg) c.append("body *{background-color:transparent!important;border-color:currentColor!important}");
        if (s.forceFg) c.append("body *{color:inherit!important}a,a *{color:").append(hex(s.link)).append("!important}");
        c.append("body *{max-width:100%}pre{white-space:pre-wrap!important;word-wrap:break-word}");
        if (s.font == UserFont.SLOT && s.userFontUrl != null) {
            c.append(UserFont.face(s.userFontUrl));
            c.append(UserFont.family());
        } else if (s.font > 0 && s.font < FONTS.length)
            c.append("body,body *:not(pre):not(code):not(kbd):not(samp):not(tt){font-family:").append(FONTS[s.font]).append("!important}");
        if (s.lineHeight > 0)
            c.append("body,body p,body div,body li,body span,body blockquote,body dd,body td{line-height:")
                    .append(String.format(Locale.ROOT, "%.2f", s.lineHeight / 10f)).append("!important}");
        if (s.align == 1) c.append("body p,body li{text-align:justify!important}");
        else if (s.align == 2) c.append("body p,body li{text-align:left!important}");
        if (s.hyphens) c.append("body p,body li,body div{-webkit-hyphens:auto;hyphens:auto}");
        return c.toString();
    }

    private String inject(String html) {
        String meta = "<meta http-equiv=\"Content-Security-Policy\" content=\"" + CSP + "\">";
        String theme = "<style id=\"recto-theme\">" + themeCss(style) + "</style>";
        StringBuilder sb = new StringBuilder(html.length() + 1024);
        Matcher open = HEAD_OPEN.matcher(html);
        if (open.find()) {
            Matcher close = HEAD_CLOSE.matcher(html);
            int ce = close.find(open.end()) ? close.start() : -1;
            sb.append(html, 0, open.end()).append(meta);
            if (ce >= 0) sb.append(html, open.end(), ce).append(theme).append(html, ce, html.length());
            else sb.append(theme).append(html, open.end(), html.length());
        } else {
            Matcher body = BODY_OPEN.matcher(html);
            int at = body.find() ? body.start() : 0;
            sb.append(html, 0, at).append("<head>").append(meta).append(theme).append("</head>").append(html, at, html.length());
        }
        return sb.toString();
    }

    public void onPause() { web.onPause(); }

    public void onResume() { web.onResume(); }

    public void destroy() {
        destroyed = true;
        listener = null;
        ui.removeCallbacksAndMessages(null);
        web.stopLoading();
        web.removeJavascriptInterface("Recto");
        removeView(web);
        web.destroy();
        book = null;
    }

    private void onReady(int p, int n, int tp) {
        if (destroyed) return;
        loading = false;
        page = p;
        pages = Math.max(1, n);
        textPos = tp;
        web.setAlpha(1f);
        if (listener != null) listener.onFlowPosition();
    }

    private void onLink(String url) {
        if (destroyed || book == null) return;
        if (url.startsWith(base)) {
            String rest = url.substring(base.length());
            int hash = rest.indexOf('#');
            String path = FlowBook.percentDecode(hash >= 0 ? rest.substring(0, hash) : rest);
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            String anchor = hash >= 0 ? FlowBook.percentDecode(rest.substring(hash + 1)) : null;
            int ch = book.chapterIndex(path);
            if (ch < 0) return;
            back.add(new int[]{chapter, Math.max(0, textPos)});
            if (back.size() > 20) back.remove(0);
            goChapter(ch, anchor);
        } else if (listener != null && !url.startsWith("javascript:")) {
            listener.onExternalLink(url);
        }
    }

    private final class Bridge {
        @JavascriptInterface public void ready(int p, int n, int tp) {
            ui.post(() -> { if (!destroyed) onReady(p, n, tp); });
        }

        @JavascriptInterface public void pos(int p, int n, int tp) {
            ui.post(() -> {
                if (destroyed || loading) return;
                page = p;
                pages = Math.max(1, n);
                textPos = tp;
                if (listener != null) listener.onFlowPosition();
            });
        }

        @JavascriptInterface public void tap(float x, float y) {
            ui.post(() -> {
                if (destroyed) return;
                boolean rtl = book != null && book.rtl;
                if (!tapZones) { if (listener != null) listener.onCenterTap(); }
                else if (x < 0.3f) { if (rtl) next(); else prev(); }
                else if (x > 0.7f) { if (rtl) prev(); else next(); }
                else if (listener != null) listener.onCenterTap();
            });
        }

        @JavascriptInterface public void link(String url) { ui.post(() -> { if (!destroyed) onLink(url); }); }
    }

    private class Client extends WebViewClient {
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            return serve(req.getUrl());
        }

        @SuppressWarnings("deprecation")
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            return serve(Uri.parse(url));
        }

        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            return override(req.getUrl().toString());
        }

        @SuppressWarnings("deprecation")
        @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return override(url); }

        private boolean override(String url) {
            if (book != null && chapter >= 0) {
                int hash = url.indexOf('#');
                String page = hash >= 0 ? url.substring(0, hash) : url;
                if (page.equals(base + encodePath(book.chapters.get(chapter).path))) return false; // the chapter being loaded
            }
            onLink(url);
            return true;
        }

        @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {}

        @Override public void onPageFinished(WebView view, String url) {
            if (destroyed || book == null || chapter < 0 || !loading) return;
            String lang = book.language != null ? JSONObject.quote(book.language) : "null";
            view.evaluateJavascript(readerJs + "\n__R.init({\"m\":" + layoutOptsArray() + ",\"scroll\":" + style.scroll
                    + sizeOpts() + ",\"lang\":" + lang + ",\"target\":" + target + "});", null);
        }

    }

    /** Android 8+: survive the renderer process being killed (low memory) by rebuilding the WebView. */
    @TargetApi(26)
    private final class Client26 extends Client {
        @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            if (destroyed) return true;
            // load() clears textPos, so keep the character anchor (or the page fraction) first.
            int tp = textPos;
            float frac = pages > 1 ? page / (float) pages : 0;
            String restore = tp >= 0 ? "{\"t\":\"text\",\"v\":" + tp + "}" : "{\"t\":\"frac\",\"v\":" + frac + "}";
            removeView(web);
            web.destroy();
            createWeb();
            if (book != null && chapter >= 0) load(chapter, restore);
            return true;
        }
    }

    private String layoutOptsArray() {
        String o = layoutOpts(style);
        return o.substring(o.indexOf('['), o.indexOf(']') + 1);
    }

    private WebResourceResponse serve(Uri uri) {
        String url = uri.toString();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Security-Policy", CSP);
        headers.put("Cache-Control", "no-store");
        if (book == null || !url.startsWith(base)) return notFound(headers);
        String path = uri.getPath();
        String prefix = Uri.parse(base).getPath();
        if (path == null || prefix == null || !path.startsWith(prefix)) return notFound(headers);
        path = path.substring(prefix.length());
        if (UserFont.PATH.equals(path)) {
            if (userFontFile == null) return notFound(headers);
            try {
                return new WebResourceResponse(userFontMime, null, 200, "OK", headers, new FileInputStream(userFontFile));
            } catch (IOException e) {
                return notFound(headers);
            }
        }
        try {
            int ch = book.chapterIndex(path);
            if (ch >= 0) {
                byte[] html = inject(book.chapterHtml(ch)).getBytes(StandardCharsets.UTF_8);
                return new WebResourceResponse("text/html", "utf-8", 200, "OK", headers, new ByteArrayInputStream(html));
            }
            FlowBook.Resource r = book.resource(path);
            if (r == null) return notFound(headers);
            String enc = r.mime.startsWith("text/") ? "utf-8" : null;
            return new WebResourceResponse(r.mime, enc, 200, "OK", headers, r.data);
        } catch (IOException | RuntimeException e) {
            return notFound(headers);
        }
    }

    private static WebResourceResponse notFound(Map<String, String> headers) {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", headers, new ByteArrayInputStream(new byte[0]));
    }

}
