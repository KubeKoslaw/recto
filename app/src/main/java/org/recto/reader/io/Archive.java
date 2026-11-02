package org.recto.reader.io;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

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

    private volatile HashMap<String, Entry> exact, folded;

    /** Entry by exact name, else case-insensitive match, else null. O(1) after the first call. */
    public Entry find(String name) {
        if (name == null) return null;
        HashMap<String, Entry> ex = exact, fo = folded;
        if (ex == null) {
            synchronized (this) {
                if (exact == null) {
                    List<? extends Entry> all = entries();
                    HashMap<String, Entry> e2 = new HashMap<>(all.size() * 2), f2 = new HashMap<>(all.size() * 2);
                    // First occurrence wins, like the linear scan did.
                    for (Entry e : all) {
                        if (!e2.containsKey(e.name)) e2.put(e.name, e);
                        String k = fold(e.name);
                        if (!f2.containsKey(k)) f2.put(k, e);
                    }
                    folded = f2;
                    exact = e2;
                }
                ex = exact;
                fo = folded;
            }
        }
        Entry e = ex.get(name);
        return e != null ? e : fo.get(fold(name));
    }

    private static String fold(String s) { return s.toLowerCase(Locale.ROOT).toUpperCase(Locale.ROOT); }

    public byte[] read(Entry e) throws IOException {
        return read(e, 512L << 20);
    }

    public byte[] read(Entry e, long limit) throws IOException {
        if (e.size > limit) throw new IOException("Entry too large: " + e.name);
        try (InputStream in = open(e)) {
            if (e.size > 0 && e.size < Integer.MAX_VALUE - 16) {
                // Known size: read straight into an exact array (no 2x peak from ByteArrayOutputStream).
                byte[] b = new byte[(int) e.size];
                int n = 0;
                for (int k; n < b.length && (k = in.read(b, n, b.length - n)) > 0; ) n += k;
                return n == b.length ? b : Arrays.copyOf(b, n);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
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
