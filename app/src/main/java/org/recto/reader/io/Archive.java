package org.recto.reader.io;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Read-only archive with random access to entries. */
public abstract class Archive implements Closeable {
    public static class Entry {
        public final String name;
        public final long size;

        protected Entry(String name, long size) {
            this.name = name;
            this.size = size;
        }

        public boolean isDirectory() { return name.endsWith("/"); }
    }

    public abstract List<? extends Entry> entries();

    public abstract InputStream open(Entry e) throws IOException;

    /** Entry by exact name, else case-insensitive match, else null. */
    public Entry find(String name) {
        for (Entry e : entries()) if (e.name.equals(name)) return e;
        for (Entry e : entries()) if (e.name.equalsIgnoreCase(name)) return e;
        return null;
    }

    public byte[] read(Entry e) throws IOException {
        return read(e, 512L << 20);
    }

    public byte[] read(Entry e, long limit) throws IOException {
        if (e.size > limit) throw new IOException("Entry too large: " + e.name);
        try (InputStream in = open(e)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(e.size > 0 ? (int) e.size : 8192);
            byte[] buf = new byte[16384];
            long total = 0;
            for (int n; (n = in.read(buf)) > 0; ) {
                total += n;
                if (total > limit) throw new IOException("Entry too large: " + e.name);
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    static int u16(byte[] b, int p) { return (b[p] & 0xff) | (b[p + 1] & 0xff) << 8; }

    static long u32(byte[] b, int p) { return (u16(b, p) | (long) u16(b, p + 2) << 16) & 0xffffffffL; }

    static long u64(byte[] b, int p) { return u32(b, p) | u32(b, p + 4) << 32; }
}
