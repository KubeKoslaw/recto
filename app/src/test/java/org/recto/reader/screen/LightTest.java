package org.recto.reader.screen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LightTest {
    @Test public void bottomStepIsSystem() {
        assertTrue(Light.system(Light.step(0.05f, -1)));
        assertEquals(Light.SYSTEM, Light.window(-0.4f), 0f);
        assertEquals(Light.SYSTEM, Light.step(Light.SYSTEM, -1), 0f);
    }

    @Test public void stepsUpAndClamps() {
        float v = Light.SYSTEM;
        for (int i = 0; i < 20; i++) v = Light.step(v, 1);
        assertEquals(1f, v, 0f);
        assertEquals(1f, Light.window(3f), 0f);
        assertFalse(Light.system(Light.step(Light.SYSTEM, 1)));
        assertEquals(20, Light.percent(0.2f));
        assertEquals(100, Light.percent(1f));
    }
}
