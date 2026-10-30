package org.recto.reader;

import android.annotation.TargetApi;
import android.app.Activity;
import android.graphics.Insets;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

/**
 * Code for newer Android versions, kept in nested classes that are only loaded on devices that
 * have those APIs, so old devices never see unresolvable classes (and keep full ART optimization).
 */
final class Compat {
    private Compat() {}

    @TargetApi(30)
    static final class V30 {
        private V30() {}

        /** Fills {left, top, right, bottom} for system bars (stable) and display cutout. */
        static void insets(WindowInsets ins, int[] bars, int[] cut, boolean ime) {
            int types = WindowInsets.Type.systemBars() | (ime ? WindowInsets.Type.ime() | WindowInsets.Type.displayCutout() : 0);
            Insets b = ime ? ins.getInsets(types) : ins.getInsetsIgnoringVisibility(types);
            bars[0] = b.left; bars[1] = b.top; bars[2] = b.right; bars[3] = b.bottom;
            if (cut != null) {
                Insets c = ins.getInsets(WindowInsets.Type.displayCutout());
                cut[0] = c.left; cut[1] = c.top; cut[2] = c.right; cut[3] = c.bottom;
            }
        }

        static void edgeToEdge(Window w, boolean lightBars) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            if (c != null) c.setSystemBarsAppearance(lightBars ? light : 0, light);
        }

        static void systemBars(Window w, boolean show) {
            WindowInsetsController c = w.getInsetsController();
            if (c == null) return;
            if (show) c.show(WindowInsets.Type.systemBars());
            else {
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsets.Type.systemBars());
            }
        }
    }

    @TargetApi(33)
    static final class V33 {
        private V33() {}

        /** Registers a predictive-back callback; returns a handle for {@link #unregisterBack}. */
        static Object registerBack(Activity a, Runnable onBack) {
            OnBackInvokedCallback cb = onBack::run;
            a.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
            return cb;
        }

        static void unregisterBack(Activity a, Object handle) {
            a.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((OnBackInvokedCallback) handle);
        }
    }
}
