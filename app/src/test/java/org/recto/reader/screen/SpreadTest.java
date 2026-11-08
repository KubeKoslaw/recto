package org.recto.reader.screen;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SpreadTest {
    @Test public void unfoldedFoldableShowsTwoPages() {
        // Honor Magic V3 inner screen 2156 x 2344 px at ~2.5 density, both orientations.
        assertTrue(Spread.twoPages(Spread.AUTO, 2156, 2344, 2.5f));
        assertTrue(Spread.twoPages(Spread.AUTO, 2344, 2156, 2.5f));
        // Galaxy Z Fold inner screen ~ 1812 x 2176 px at 2.625.
        assertTrue(Spread.twoPages(Spread.AUTO, 1812, 2176, 2.625f));
    }

    @Test public void foldedOrPhoneShowsOnePage() {
        // Magic V3 outer screen 1060 x 2376 px at ~2.75 density.
        assertFalse(Spread.twoPages(Spread.AUTO, 1060, 2376, 2.75f));
        assertFalse(Spread.twoPages(Spread.AUTO, 2376, 1060, 2.75f)); // phone landscape
        assertFalse(Spread.twoPages(Spread.AUTO, 430, 860)); // split screen half
    }

    @Test public void tabletPortraitStaysSingle() {
        assertFalse(Spread.twoPages(Spread.AUTO, 800, 1280));
        assertTrue(Spread.twoPages(Spread.AUTO, 1280, 800));
    }

    @Test public void modes() {
        assertFalse(Spread.twoPages(Spread.OFF, 900, 900));
        assertTrue(Spread.twoPages(Spread.ON, 860, 390)); // phone landscape
        assertFalse(Spread.twoPages(Spread.ON, 390, 860));
        assertFalse(Spread.twoPages(Spread.ON, 0, 0));
        assertFalse(Spread.twoPages(Spread.AUTO, 1000, 1000, 0f));
    }

    @Test public void widePages() {
        assertTrue(Spread.isWide(1600, 1100));
        assertFalse(Spread.isWide(1100, 1600));
        assertFalse(Spread.isWide(1000, 1000));
    }
}
