package org.recto.reader.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * RAR 4.x / 5.x archive reader for <em>stored</em> (uncompressed) entries, which is how most
 * comic scanners pack .cbr files since JPEG/PNG data does not compress further. Compressed
 * entries are listed but cannot be opened: RAR decompression is not available under a FOSS license.
 */
public final class RarArchive extends Archive {
    static final class RarEntry extends Entry {
        final long offset;
        final boolean stored;

        RarEntry(String name, long size, long offset, boolean stored) {
            super(name, size);
            this.offset = offset;
            this.stored = stored;
        }
    }

    public static final class CompressedException extends IOException {
        CompressedException() { super("compressed RAR"); }
    }

    private final Seekable src;
    private final List<RarEntry> entries = new ArrayList<>();

    public static boolean isRar(byte[] h, int n) {
        return n >= 7 && h[0] == 'R' && h[1] == 'a' && h[2] == 'r' && h[3] == '!' && h[4] == 0x1a && h[5] == 7
                && (h[6] == 0 || (h[6] == 1 && n >= 8 && h[7] == 0));
    }

    public RarArchive(Seekable src) throws IOException {
        this.src = src;
        byte[] sig = new byte[8];
        src.readFully(0, sig, 0, 8);
        if (!isRar(sig, 8)) throw new IOException("Not a RAR archive");
        if (sig[6] == 1) readV5(); else readV4();
    }

    /** True if at least one picture needs RAR decompression. */
    public boolean hasCompressed() {
        for (RarEntry e : entries) if (!e.stored && org.recto.reader.util.Text.isImageName(e.name)) return true;
        return false;
    }

    private void readV4() throws IOException {
        long pos = 7, size = src.size();
        byte[] h = new byte[32];
        while (pos + 7 <= size) {
            src.readFully(pos, h, 0, 7);
            int type = h[2] & 0xff, flags = u16(h, 3), hsize = u16(h, 5);
            if (hsize < 7) throw new IOException("Corrupt RAR archive");
            long add = 0;
            if (type == 0x73 && (flags & 0x80) != 0) throw new IOException("Encrypted archives are not supported");
            if (type == 0x74) {
                byte[] fh = new byte[hsize];
                src.readFully(pos, fh, 0, hsize);
                long pack = u32(fh, 7), unp = u32(fh, 11);
                int method = fh[25] & 0xff, nameSize = u16(fh, 26), p = 32;
                if ((flags & 0x100) != 0) {
                    pack |= u32(fh, 32) << 32;
                    unp |= u32(fh, 36) << 32;
                    p = 40;
                }
                if (p + nameSize > hsize) throw new IOException("Corrupt RAR archive");
                String name = decodeV4Name(fh, p, nameSize, (flags & 0x200) != 0).replace('\\', '/');
                boolean dir = (flags & 0xe0) == 0xe0, enc = (flags & 0x04) != 0, split = (flags & 0x03) != 0;
                if (!dir && !split) entries.add(new RarEntry(name, unp, pos + hsize, method == 0x30 && !enc));
                add = pack;
            } else if (type == 0x7b) {
                break;
            } else if ((flags & 0x8000) != 0) {
                src.readFully(pos + 7, h, 7, 4);
                add = u32(h, 7);
            }
            pos += hsize + add;
        }
    }

    private static String decodeV4Name(byte[] b, int off, int len, boolean unicode) {
        if (!unicode) return new String(b, off, len, StandardCharsets.ISO_8859_1);
        int zero = 0;
        while (zero < len && b[off + zero] != 0) zero++;
        if (zero == len) return new String(b, off, len, StandardCharsets.UTF_8);
        // RAR's compact Unicode encoding following the ASCII name.
        StringBuilder sb = new StringBuilder();
        int enc = off + zero + 1, end = off + len, highByte = b[enc++] & 0xff, flagBits = 0, flags = 0, dst = 0;
        while (enc < end) {
            if (flagBits == 0) {
                flags = b[enc++] & 0xff;
                flagBits = 8;
                if (enc >= end) break;
            }
            switch (flags >> 6) {
                case 0: sb.append((char) (b[enc++] & 0xff)); dst++; break;
                case 1: sb.append((char) ((b[enc++] & 0xff) + (highByte << 8))); dst++; break;
                case 2:
                    if (enc + 1 >= end) return sb.toString();
                    sb.append((char) ((b[enc] & 0xff) + ((b[enc + 1] & 0xff) << 8)));
                    enc += 2;
                    dst++;
                    break;
                default: {
                    int l = b[enc++] & 0xff;
                    if ((l & 0x80) != 0) {
                        if (enc >= end) return sb.toString();
                        int correction = b[enc++] & 0xff;
                        for (l = (l & 0x7f) + 2; l > 0 && dst < zero; l--, dst++)
                            sb.append((char) ((((b[off + dst] & 0xff) + correction) & 0xff) + (highByte << 8)));
                    } else {
                        for (l += 2; l > 0 && dst < zero; l--, dst++) sb.append((char) (b[off + dst] & 0xff));
                    }
                }
            }
            flags = (flags << 2) & 0xff;
            flagBits -= 2;
        }
        return sb.toString();
    }

    private long vpos;
    private byte[] vbuf;

    private long vint() throws IOException {
        long v = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (vpos >= vbuf.length) throw new IOException("Corrupt RAR archive");
            int c = vbuf[(int) vpos++] & 0xff;
            v |= (long) (c & 0x7f) << shift;
            if ((c & 0x80) == 0) return v;
        }
        throw new IOException("Corrupt RAR archive");
    }

    private void readV5() throws IOException {
        long pos = 8, size = src.size();
        byte[] pre = new byte[16];
        while (pos + 7 <= size) {
            int n = (int) Math.min(16, size - pos);
            src.readFully(pos, pre, 0, n);
            vbuf = pre;
            vpos = 4;
            long hsize = vint();
            long hstart = pos + vpos;
            if (hsize <= 0 || hsize > (2 << 20) || hstart + hsize > size) throw new IOException("Corrupt RAR archive");
            vbuf = new byte[(int) hsize];
            src.readFully(hstart, vbuf, 0, vbuf.length);
            vpos = 0;
            long type = vint(), flags = vint(), extra = 0, data = 0;
            if ((flags & 1) != 0) extra = vint();
            if ((flags & 2) != 0) data = vint();
            if (type == 4) throw new IOException("Encrypted archives are not supported");
            if (type == 5) break;
            if (type == 2) {
                long fflags = vint(), unp = vint();
                vint(); // attributes
                if ((fflags & 2) != 0) vpos += 4;
                if ((fflags & 4) != 0) vpos += 4;
                long comp = vint();
                vint(); // host OS
                int nlen = (int) vint();
                if (vpos + nlen > vbuf.length) throw new IOException("Corrupt RAR archive");
                String name = new String(vbuf, (int) vpos, nlen, StandardCharsets.UTF_8).replace('\\', '/');
                vpos += nlen;
                boolean enc = extra > 0 && hasEncryptionRecord((int) (vbuf.length - extra), vbuf.length);
                boolean split = (flags & (0x08 | 0x10)) != 0, dir = (fflags & 1) != 0;
                boolean stored = ((comp >> 7) & 7) == 0 && !enc;
                if (!dir && !split) entries.add(new RarEntry(name, unp, hstart + hsize, stored));
            }
            pos = hstart + hsize + data;
        }
    }

    private boolean hasEncryptionRecord(int from, int to) throws IOException {
        vpos = from;
        while (vpos < to) {
            long recSize = vint();
            long next = vpos + recSize;
            if (vint() == 1) return true;
            vpos = next;
        }
        return false;
    }

    @Override public List<? extends Entry> entries() { return entries; }

    @Override public InputStream open(Entry e) throws IOException {
        RarEntry r = (RarEntry) e;
        if (!r.stored) throw new CompressedException();
        return new SliceStream(src, r.offset, r.size);
    }

    @Override public void close() throws IOException { src.close(); }
}
