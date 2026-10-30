package org.recto.reader.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Uncompressed TAR reader (ustar, GNU long names, pax paths) - used for .cbt comics. */
public final class TarArchive extends Archive {
    static final class TarEntry extends Entry {
        final long offset;

        TarEntry(String name, long size, long offset) {
            super(name, size);
            this.offset = offset;
        }
    }

    private final Seekable src;
    private final List<TarEntry> entries = new ArrayList<>();

    public static boolean isTar(byte[] head, int n) {
        return n >= 262 && head[257] == 'u' && head[258] == 's' && head[259] == 't' && head[260] == 'a' && head[261] == 'r';
    }

    public TarArchive(Seekable src) throws IOException {
        this.src = src;
        long size = src.size(), pos = 0;
        byte[] h = new byte[512];
        String longName = null;
        while (pos + 512 <= size) {
            src.readFully(pos, h, 0, 512);
            if (h[0] == 0) break; // end-of-archive marker
            long len = number(h, 124, 12);
            char type = (char) h[156];
            long data = pos + 512;
            if (len < 0 || data + len > size) throw new IOException("Corrupt TAR archive");
            if (type == 'L' || type == 'x') {
                byte[] meta = new byte[(int) Math.min(len, 65536)];
                src.readFully(data, meta, 0, meta.length);
                longName = type == 'L' ? cstr(meta, 0, meta.length) : paxPath(meta);
            } else {
                String name = longName;
                if (name == null) {
                    name = cstr(h, 0, 100);
                    String prefix = cstr(h, 345, 155);
                    if (isTar(h, 512) && !prefix.isEmpty()) name = prefix + "/" + name;
                }
                longName = null;
                if (type == '0' || type == 0 || type == '7') entries.add(new TarEntry(name, len, data));
            }
            pos = data + ((len + 511) & ~511L);
        }
        if (entries.isEmpty() && !isTar(h, 512)) throw new IOException("Not a TAR archive");
    }

    private static String paxPath(byte[] m) {
        String s = new String(m, StandardCharsets.UTF_8);
        for (String rec : s.split("\n")) {
            int sp = rec.indexOf(' ');
            if (sp > 0 && rec.startsWith("path=", sp + 1)) return rec.substring(sp + 6);
        }
        return null;
    }

    private static String cstr(byte[] b, int off, int max) {
        int n = 0;
        while (n < max && b[off + n] != 0) n++;
        return new String(b, off, n, StandardCharsets.UTF_8);
    }

    private static long number(byte[] b, int off, int len) {
        if ((b[off] & 0x80) != 0) { // GNU base-256
            long v = b[off] & 0x7f;
            for (int i = 1; i < len; i++) v = (v << 8) | (b[off + i] & 0xff);
            return v;
        }
        long v = 0;
        int i = 0;
        while (i < len && b[off + i] == ' ') i++;
        for (; i < len; i++) {
            int c = b[off + i];
            if (c < '0' || c > '7') break;
            v = v * 8 + (c - '0');
        }
        return v;
    }

    @Override public List<? extends Entry> entries() { return entries; }

    @Override public InputStream open(Entry e) {
        return new SliceStream(src, ((TarEntry) e).offset, e.size);
    }

    @Override public void close() throws IOException { src.close(); }
}
