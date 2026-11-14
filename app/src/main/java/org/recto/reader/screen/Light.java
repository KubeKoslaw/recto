package org.recto.reader.screen;

/** Reading brightness as a window override. The first step is the system brightness. */
public final class Light {
    /** {@code WindowManager.LayoutParams.screenBrightness} value that follows the system. */
    public static final float SYSTEM = -1f;

    /** Index 0 is {@link #SYSTEM}. Later steps are fractions of full window brightness. */
    private static final float[] STEPS = {SYSTEM, 0.05f, 0.2f, 0.4f, 0.6f, 0.8f, 1f};

    private Light() {}

    public static int index(float value) {
        if (value < 0) return 0;
        int best = 1;
        float bestD = Float.MAX_VALUE;
        for (int i = 1; i < STEPS.length; i++) {
            float d = Math.abs(STEPS[i] - value);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** Moves one step. The low end restores system brightness. */
    public static float step(float value, int delta) {
        int i = index(value) + delta;
        if (i < 0) i = 0;
        if (i >= STEPS.length) i = STEPS.length - 1;
        return STEPS[i];
    }

    public static boolean system(float value) { return value < 0; }

    /** Clamped value for {@code LayoutParams.screenBrightness}. */
    public static float window(float value) {
        if (value < 0) return SYSTEM;
        if (value > 1f) return 1f;
        return value;
    }

    public static int percent(float value) {
        float v = value < 0 ? 0 : (value > 1f ? 1f : value);
        return Math.round(v * 100);
    }
}
