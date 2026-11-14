package org.recto.reader.marks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class MarksTest {
    @Test public void newestFirstAndBlankLabelFallsBack() {
        List<Marks.Mark> a = Marks.add(null, "p:3:0.0000", "  ", "Page 4", 10);
        assertEquals(1, a.size());
        assertEquals("Page 4", a.get(0).label);
        assertEquals("p:3:0.0000", a.get(0).pos);

        List<Marks.Mark> b = Marks.add(a, "c:1:20:0.5000", "Chapter", null, 20);
        assertEquals("c:1:20:0.5000", b.get(0).pos);
        assertEquals("p:3:0.0000", b.get(1).pos);
    }

    @Test public void samePositionMovesToFront() {
        List<Marks.Mark> a = Marks.add(null, "p:1:0", "One", null, 1);
        a = Marks.add(a, "p:2:0", "Two", null, 2);
        a = Marks.add(a, "p:1:0", "One again", null, 3);
        assertEquals(2, a.size());
        assertEquals("p:1:0", a.get(0).pos);
        assertEquals("One again", a.get(0).label);
        assertEquals("p:2:0", a.get(1).pos);
    }

    @Test public void capsTheList() {
        List<Marks.Mark> a = new ArrayList<>();
        for (int i = 0; i < Marks.MAX + 5; i++) a = Marks.add(a, "p:" + i + ":0", "L" + i, null, i);
        assertEquals(Marks.MAX, a.size());
        assertEquals("p:" + (Marks.MAX + 4) + ":0", a.get(0).pos);
        assertEquals("p:5:0", a.get(a.size() - 1).pos);
    }

    @Test public void removeKeepsTheRest() {
        List<Marks.Mark> a = Marks.add(null, "p:1:0.2500", "Cover", null, 5);
        a = Marks.add(a, "p:9:0", "End", null, 6);
        a = Marks.removeAt(a, 0);
        assertEquals(1, a.size());
        assertEquals("Cover", a.get(0).label);
        assertEquals("p:1:0.2500", a.get(0).pos);
        assertEquals(5, a.get(0).at);
        assertTrue(Marks.read(null).isEmpty());
        assertTrue(Marks.removeAt(null, 0).isEmpty());
    }
}
