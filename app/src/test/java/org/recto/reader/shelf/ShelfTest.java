package org.recto.reader.shelf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ShelfTest {
    @Test public void filtersByReadingState() {
        assertTrue(Shelf.visible(Shelf.FILTER_ALL, 0, 0));
        assertTrue(Shelf.visible(Shelf.FILTER_NEW, 0, 0));
        assertFalse(Shelf.visible(Shelf.FILTER_NEW, 5, 0.1f));
        assertTrue(Shelf.visible(Shelf.FILTER_READING, 5, 0.4f));
        assertFalse(Shelf.visible(Shelf.FILTER_READING, 5, 0.98f));
        assertFalse(Shelf.visible(Shelf.FILTER_READING, 0, 0));
        assertTrue(Shelf.visible(Shelf.FILTER_FINISHED, 5, 1f));
        assertTrue(Shelf.visible(Shelf.FILTER_FINISHED, 5, Shelf.FINISHED));
        assertFalse(Shelf.visible(Shelf.FILTER_FINISHED, 5, 0.97f));
        assertTrue(Shelf.visible(99, 0, 0));
    }

    @Test public void recentMatchesTheOldLibraryOrder() {
        assertTrue(Shelf.compare(Shelf.SORT_RECENT, "B", null, 20, 0, "A", null, 10, 0) < 0);
        assertTrue(Shelf.compare(Shelf.SORT_RECENT, "B", null, 10, 0, "A", null, 10, 0) > 0);
    }

    @Test public void titleAuthorAndProgress() {
        assertTrue(Shelf.compare(Shelf.SORT_TITLE, "page2", null, 0, 0, "page10", null, 0, 0) < 0);
        assertTrue(Shelf.compare(Shelf.SORT_AUTHOR, "B", "", 0, 0, "A", "Zola", 0, 0) > 0);
        assertTrue(Shelf.compare(Shelf.SORT_AUTHOR, "B", "Austen", 0, 0, "A", "Zola", 0, 0) < 0);
        assertEquals(0, Shelf.compare(Shelf.SORT_AUTHOR, "Same", "Austen", 1, 0, "Same", "Austen", 9, 1));
        assertTrue(Shelf.compare(Shelf.SORT_PROGRESS, "A", null, 1, 0.2f, "B", null, 1, 0.9f) > 0);
    }

    @Test public void coversFaceForwardAndNovelsAreSpines() {
        assertTrue(Shelf.face("CBZ", false));
        assertTrue(Shelf.face("cbr", false));
        assertTrue(Shelf.face("jpg", false));
        assertTrue(Shelf.face("EPUB", true));
        assertFalse(Shelf.face("EPUB", false));
        assertFalse(Shelf.face("PDF", false));
        assertFalse(Shelf.face("MOBI", false));
        assertFalse(Shelf.face(null, false));
        assertEquals(Shelf.spineColor("same"), Shelf.spineColor("same"));
    }
}
