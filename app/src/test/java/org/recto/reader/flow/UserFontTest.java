package org.recto.reader.flow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;

public class UserFontTest {
    @Test public void namesAndMime() {
        assertTrue(UserFont.supported("Libre.TTF"));
        assertTrue(UserFont.supported("face.otf"));
        assertTrue(UserFont.supported("face.woff"));
        assertFalse(UserFont.supported("face.woff2"));
        assertFalse(UserFont.supported(null));
        assertEquals("font/otf", UserFont.mime("A.OTF"));
        assertEquals("font/woff", UserFont.mime("a.woff"));
        assertEquals("font/ttf", UserFont.mime("a.ttf"));
    }

    @Test public void faceStaysOnTheBookOrigin() {
        String css = UserFont.face("https://recto.invalid/abc/" + UserFont.PATH) + UserFont.family();
        assertTrue(css.contains(UserFont.PATH));
        assertTrue(css.contains(UserFont.FAMILY));
        assertFalse(css.contains("file:"));
        assertFalse(css.contains(UserFont.FILE));
    }

    @Test public void storeReplacesAndRejectsHugeFiles() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "recto-font-" + System.nanoTime());
        assertTrue(dir.mkdirs());
        File dest = UserFont.file(dir);
        byte[] first = "one".getBytes(StandardCharsets.UTF_8);
        assertEquals(3, UserFont.store(new ByteArrayInputStream(first), dest));
        assertEquals(4, UserFont.store(new ByteArrayInputStream("two!".getBytes(StandardCharsets.UTF_8)), dest));
        assertEquals("two!", new String(java.nio.file.Files.readAllBytes(dest.toPath()), StandardCharsets.UTF_8));
        byte[] huge = new byte[UserFont.MAX_BYTES + 1];
        assertEquals(-1, UserFont.store(new ByteArrayInputStream(huge), dest));
        assertEquals("two!", new String(java.nio.file.Files.readAllBytes(dest.toPath()), StandardCharsets.UTF_8));
        assertEquals(0, UserFont.store(new ByteArrayInputStream(new byte[0]), dest));
        dest.delete();
        dir.delete();
    }
}
