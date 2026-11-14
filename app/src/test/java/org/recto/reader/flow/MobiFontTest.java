package org.recto.reader.flow;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** FONT records are decoded without allocating the size the header claims. */
public class MobiFontTest {
    private static void be32(byte[] b, int off, long v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    /** flags = zlib, dstart = 24, usize far above the font cap. */
    @Test public void hugeUncompressedSizeIsRejected() {
        byte[] r = new byte[32];
        r[0] = 'F'; r[1] = 'O'; r[2] = 'N'; r[3] = 'T';
        be32(r, 4, 1L << 30);
        be32(r, 8, 1);
        be32(r, 12, 24);
        assertNull(MobiBook.font(r));
    }

    @Test public void dataStartPastTheRecordIsRejected() {
        byte[] r = new byte[32];
        be32(r, 12, 100);
        assertNull(MobiBook.font(r));
    }

    @Test public void obfuscationRangePastTheRecordIsRejected() {
        byte[] r = new byte[32];
        be32(r, 8, 2);
        be32(r, 12, 24);
        be32(r, 16, 100);
        be32(r, 20, 0);
        assertNull(MobiBook.font(r));
    }

    @Test public void plainPayloadIsReturned() {
        byte[] r = new byte[28];
        be32(r, 12, 24);
        r[24] = 1; r[25] = 2; r[26] = 3; r[27] = 4;
        assertArrayEquals(new byte[]{1, 2, 3, 4}, MobiBook.font(r));
    }
}
