package org.recto.reader;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Test;
import org.recto.reader.io.Archive;
import org.recto.reader.io.BytesSeekable;
import org.recto.reader.io.FileSeekable;
import org.recto.reader.io.RarArchive;
import org.recto.reader.io.TarArchive;
import org.recto.reader.io.ZipArchive;
import org.recto.reader.util.Natural;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ArchiveTest {
    static byte[] zip(Object... nameData) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bo)) {
            for (int i = 0; i < nameData.length; i += 2) {
                byte[] d = nameData[i + 1] instanceof String ? ((String) nameData[i + 1]).getBytes(StandardCharsets.UTF_8) : (byte[]) nameData[i + 1];
                ZipEntry e = new ZipEntry((String) nameData[i]);
                if (i % 4 == 2) { // every second entry stored
                    e.setMethod(ZipEntry.STORED);
                    CRC32 c = new CRC32();
                    c.update(d);
                    e.setCrc(c.getValue());
                    e.setSize(d.length);
                }
                z.putNextEntry(e);
                z.write(d);
                z.closeEntry();
            }
        }
        return bo.toByteArray();
    }

    @Test public void zipRoundTrip() throws Exception {
        byte[] big = new byte[1 << 20];
        new Random(1).nextBytes(big);
        Arrays.fill(big, 1000, 300000, (byte) 'x');
        byte[] data = zip("a.txt", "hello", "dir/zażółć.jpg", "stored", "big.bin", big, "empty", "");
        ZipArchive z = new ZipArchive(new BytesSeekable(data));
        assertEquals(4, z.entries().size());
        assertEquals("dir/zażółć.jpg", z.entries().get(1).name);
        assertEquals("hello", new String(z.read(z.find("a.txt")), StandardCharsets.UTF_8));
        assertEquals("stored", new String(z.read(z.find("DIR/ZAŻÓŁĆ.JPG")), StandardCharsets.UTF_8));
        assertArrayEquals(big, z.read(z.find("big.bin")));
        assertEquals(0, z.read(z.find("empty")).length);
    }

    @Test public void zipWithPrependedData() throws Exception {
        byte[] data = zip("x/1.png", "one", "x/2.png", "two");
        byte[] shifted = new byte[data.length + 777];
        System.arraycopy(data, 0, shifted, 777, data.length);
        ZipArchive z = new ZipArchive(new BytesSeekable(shifted));
        assertEquals("two", new String(z.read(z.find("x/2.png")), StandardCharsets.UTF_8));
    }

    @Test(expected = java.io.IOException.class)
    public void notAZip() throws Exception {
        new ZipArchive(new BytesSeekable("definitely not a zip file at all, sorry".getBytes()));
    }

    static void tarEntry(ByteArrayOutputStream out, String name, byte[] data) {
        byte[] h = new byte[512];
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(n, 0, h, 0, n.length);
        put(h, 100, "0000644");
        put(h, 108, "0000000");
        put(h, 116, "0000000");
        put(h, 124, String.format("%011o", data.length));
        put(h, 136, "00000000000");
        h[156] = '0';
        put(h, 257, "ustar");
        h[263] = '0';
        h[264] = '0';
        Arrays.fill(h, 148, 156, (byte) ' ');
        int sum = 0;
        for (byte b : h) sum += b & 0xff;
        put(h, 148, String.format("%06o", sum));
        h[154] = 0;
        out.write(h, 0, 512);
        out.write(data, 0, data.length);
        out.write(new byte[(512 - data.length % 512) % 512], 0, (512 - data.length % 512) % 512);
    }

    static void put(byte[] h, int off, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, h, off, b.length);
    }

    @Test public void tarRoundTrip() throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        tarEntry(bo, "p/1.jpg", "first".getBytes());
        tarEntry(bo, "p/2.jpg", new byte[1500]);
        bo.write(new byte[1024], 0, 1024);
        byte[] t = bo.toByteArray();
        assertTrue(TarArchive.isTar(t, t.length));
        TarArchive a = new TarArchive(new BytesSeekable(t));
        assertEquals(2, a.entries().size());
        assertEquals("first", new String(a.read(a.find("p/1.jpg"))));
        assertEquals(1500, a.read(a.find("p/2.jpg")).length);
    }

    @Test public void naturalOrder() {
        List<String> l = new ArrayList<>(Arrays.asList("p10.jpg", "p2.jpg", "P1.jpg", "p02b.jpg", "a/cover.jpg", "a/001.jpg", "ch10/1.jpg", "ch9/1.jpg"));
        Collections.sort(l, Natural.INSTANCE);
        assertEquals(Arrays.asList("a/cover.jpg", "a/001.jpg", "ch9/1.jpg", "ch10/1.jpg", "P1.jpg", "p2.jpg", "p02b.jpg", "p10.jpg"), l);
    }

    static File sample(String name) {
        String dir = System.getProperty("recto.samples", "");
        File f = new File(dir, name);
        Assume.assumeTrue("sample " + name + " not available", !dir.isEmpty() && f.isFile());
        return f;
    }

    static FileSeekable open(File f) throws Exception { return new FileSeekable(new RandomAccessFile(f, "r")); }

    @Test public void rarStored() throws Exception {
        for (String name : new String[]{"store4.cbr", "store5.cbr"}) {
            RarArchive r = new RarArchive(open(sample(name)));
            assertEquals(name, 4, r.entries().size());
            assertFalse(r.hasCompressed());
            assertEquals("Issue 1/01.jpg", r.entries().get(0).name);
            for (Archive.Entry e : r.entries()) {
                byte[] d = r.read(e);
                assertEquals(e.size, d.length);
                assertEquals((byte) 0xff, d[0]);
                assertEquals((byte) 0xd8, d[1]);
            }
            r.close();
        }
    }

    @Test public void findIsIndexedAndCaseInsensitive() throws Exception {
        ZipArchive z = new ZipArchive(new BytesSeekable(zip("A/Page.jpg", "1", "a/page.JPG", "2", "b.txt", "3")));
        assertEquals("A/Page.jpg", z.find("A/Page.jpg").name);
        assertEquals("a/page.JPG", z.find("a/page.JPG").name);
        assertEquals("A/Page.jpg", z.find("a/PAGE.jpg").name); // first case-insensitive match wins
        assertEquals(null, z.find("missing"));
        assertEquals(null, z.find(null));
        assertArrayEquals("3".getBytes(StandardCharsets.UTF_8), z.read(z.find("B.TXT")));
    }

    @Test public void readKnownSizeExactly() throws Exception {
        byte[] big = new byte[300_000];
        new Random(3).nextBytes(big);
        ZipArchive z = new ZipArchive(new BytesSeekable(zip("x.bin", "x", "big.bin", big)));
        assertArrayEquals(big, z.read(z.find("big.bin")));
    }

    /** One RAR 4 file header and no payload. {@code flags} bit 0 marks a volume split. */
    static byte[] rar4(String name, int flags) {
        byte[] nb = name.getBytes(StandardCharsets.US_ASCII);
        int hsize = 32 + nb.length;
        byte[] rar = new byte[7 + hsize];
        System.arraycopy(new byte[]{'R', 'a', 'r', '!', 0x1a, 7, 0}, 0, rar, 0, 7);
        int o = 7;
        rar[o + 2] = 0x74;
        rar[o + 3] = (byte) flags;
        rar[o + 5] = (byte) hsize;
        rar[o + 25] = 0x30; // stored
        rar[o + 26] = (byte) nb.length;
        System.arraycopy(nb, 0, rar, o + 32, nb.length);
        return rar;
    }

    @Test public void rarSplitImageIsReported() throws Exception {
        RarArchive split = new RarArchive(new BytesSeekable(rar4("01.jpg", 0x01)));
        assertTrue(split.hasSplitImage());
        assertEquals(0, split.entries().size());
        assertFalse(split.hasCompressed());

        RarArchive note = new RarArchive(new BytesSeekable(rar4("note.txt", 0x01)));
        assertFalse(note.hasSplitImage());
        assertEquals(0, note.entries().size());

        RarArchive whole = new RarArchive(new BytesSeekable(rar4("01.jpg", 0)));
        assertFalse(whole.hasSplitImage());
        assertFalse(whole.hasCompressed());
        assertEquals(1, whole.entries().size());
        assertEquals("01.jpg", whole.entries().get(0).name);
    }

    @Test(expected = java.io.IOException.class)
    public void rarShortFileHeaderIsCorrupt() throws Exception {
        byte[] rar = new byte[64];
        byte[] sig = {'R', 'a', 'r', '!', 0x1a, 7, 0};
        System.arraycopy(sig, 0, rar, 0, 7);
        rar[7 + 2] = 0x74; // file header ...
        rar[7 + 5] = 10;   // ... claiming only 10 bytes
        new RarArchive(new BytesSeekable(rar));
    }

    @Test public void zipTruncatedUnicodeExtraIsIgnored() throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bo)) {
            ZipEntry e = new ZipEntry("a.txt");
            e.setExtra(new byte[]{0x75, 0x70, 40, 0, 1, 0}); // Unicode path claiming 40 bytes, 2 present
            z.putNextEntry(e);
            z.write('x');
            z.closeEntry();
        }
        ZipArchive z = new ZipArchive(new BytesSeekable(bo.toByteArray()));
        assertEquals("a.txt", z.entries().get(0).name);
    }
}
