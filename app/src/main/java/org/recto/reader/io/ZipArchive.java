package org.recto.reader.io;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Minimal ZIP reader working directly on a {@link Seekable}: reads the central directory once,
 * then inflates single entries on demand. Supports stored/deflated entries and ZIP64.
 */
public final class ZipArchive extends Archive {
    public static final class ZipEntry extends Entry {
        final int method, flags;
        final long compSize, localOffset;
        volatile long dataOffset = -1;

        ZipEntry(String name, int method, int flags, long compSize, long size, long localOffset) {
            super(name, size);
            this.method = method;
            this.flags = flags;
            this.compSize = compSize;
            this.localOffset = localOffset;
        }
    }

    private final Seekable src;
    private final List<ZipEntry> entries = new ArrayList<>();

    public ZipArchive(Seekable src) throws IOException {
        this.src = src;
        long size = src.size();
        int tail = (int) Math.min(size, 22 + 65535);
        if (tail < 22) throw new IOException("Not a ZIP archive");
        byte[] buf = new byte[tail];
        src.readFully(size - tail, buf, 0, tail);
        int eocd = -1;
        for (int i = tail - 22; i >= 0; i--) {
            if (buf[i] == 0x50 && buf[i + 1] == 0x4b && buf[i + 2] == 5 && buf[i + 3] == 6) {
                eocd = i;
                break;
            }
        }
        if (eocd < 0) throw new IOException("Not a ZIP archive");
        long count = u16(buf, eocd + 10), cdSize = u32(buf, eocd + 12), cdOffset = u32(buf, eocd + 16);
        long shift = 0;
        boolean zip64 = false;
        if (count == 0xffff || cdSize == 0xffffffffL || cdOffset == 0xffffffffL) {
            int loc = eocd - 20;
            if (loc >= 0 && u32(buf, loc) == 0x07064b50L) {
                byte[] z = new byte[56];
                src.readFully(u64(buf, loc + 8), z, 0, 56);
                if (u32(z, 0) == 0x06064b50L) {
                    count = u64(z, 32);
                    cdSize = u64(z, 40);
                    cdOffset = u64(z, 48);
                    zip64 = true;
                }
            }
        }
        if (!zip64) {
            // Tolerate data prepended to the archive (e.g. self-extractors).
            long expected = size - tail + eocd - cdSize;
            if (expected >= 0 && expected != cdOffset) shift = expected - cdOffset;
        }
        if (cdSize > 64L << 20) throw new IOException("ZIP directory too large");
        byte[] cd = new byte[(int) cdSize];
        src.readFully(cdOffset + shift, cd, 0, cd.length);
        int p = 0;
        for (long i = 0; i < count && p + 46 <= cd.length; i++) {
            if (u32(cd, p) != 0x02014b50L) break;
            int flags = u16(cd, p + 8), method = u16(cd, p + 10);
            long csize = u32(cd, p + 20), usize = u32(cd, p + 24);
            int nlen = u16(cd, p + 28), xlen = u16(cd, p + 30), clen = u16(cd, p + 32);
            long lho = u32(cd, p + 42);
            if (p + 46 + nlen > cd.length) break;
            String name = decodeName(cd, p + 46, nlen, (flags & 0x800) != 0);
            int x = p + 46 + nlen, xe = Math.min(cd.length, x + xlen);
            while (x + 4 <= xe) {
                int id = u16(cd, x), sz = u16(cd, x + 2), q = x + 4, qe = Math.min(xe, q + sz);
                if (id == 1) {
                    if (usize == 0xffffffffL && q + 8 <= qe) { usize = u64(cd, q); q += 8; }
                    if (csize == 0xffffffffL && q + 8 <= qe) { csize = u64(cd, q); q += 8; }
                    if (lho == 0xffffffffL && q + 8 <= qe) lho = u64(cd, q);
                } else if (id == 0x7075 && qe - q > 5) { // Info-ZIP Unicode path
                    name = new String(cd, q + 5, qe - q - 5, StandardCharsets.UTF_8);
                }
                x += 4 + sz;
            }
            name = name.replace('\\', '/');
            entries.add(new ZipEntry(name, method, flags, csize, usize, lho + shift));
            p += 46 + nlen + xlen + clen;
        }
    }

    private static String decodeName(byte[] b, int off, int len, boolean utf8) {
        if (utf8) return new String(b, off, len, StandardCharsets.UTF_8);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b, off, len)).toString();
        } catch (CharacterCodingException e) {
            return new String(b, off, len, StandardCharsets.ISO_8859_1);
        }
    }

    @Override public List<ZipEntry> entries() { return entries; }

    @Override public InputStream open(Entry entry) throws IOException {
        ZipEntry e = (ZipEntry) entry;
        if ((e.flags & 1) != 0) throw new IOException("Encrypted archives are not supported");
        long off = e.dataOffset;
        if (off < 0) {
            byte[] h = new byte[30];
            src.readFully(e.localOffset, h, 0, 30);
            if (u32(h, 0) != 0x04034b50L) throw new IOException("Corrupt ZIP entry: " + e.name);
            off = e.localOffset + 30 + u16(h, 26) + u16(h, 28);
            e.dataOffset = off;
        }
        SliceStream raw = new SliceStream(src, off, e.compSize);
        if (e.method == 0) return raw;
        if (e.method == 8) return new Inflating(raw);
        throw new IOException("Unsupported ZIP compression method " + e.method);
    }

    @Override public void close() throws IOException { src.close(); }

    /** Raw-deflate stream that feeds the trailing dummy byte zlib may need, like java.util.zip.ZipFile. */
    private static final class Inflating extends InflaterInputStream {
        private boolean eof, closed;

        Inflating(InputStream in) { super(in, new Inflater(true), 16384); }

        @Override protected void fill() throws IOException {
            if (eof) throw new EOFException("Unexpected end of ZIP entry");
            len = in.read(buf, 0, buf.length);
            if (len == -1) {
                buf[0] = 0;
                len = 1;
                eof = true;
            }
            inf.setInput(buf, 0, len);
        }

        @Override public int available() throws IOException { return closed || inf.finished() ? 0 : super.available(); }

        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            inf.end();
            super.close();
        }
    }
}
