package org.recto.reader.flow;

import org.recto.reader.io.Seekable;
import org.recto.reader.util.Text;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

/**
 * Mobipocket / Kindle books without DRM: MOBI 6 (PalmDOC or HUFF/CDIC compressed HTML) and
 * KF8 / AZW3 (reassembled from skeleton and fragment tables), converted to HTML chapters.
 */
public final class MobiBook {
    private MobiBook() {}

    public static final class DrmException extends IOException {
        DrmException() { super("DRM"); }
    }

    private static final int NULL = 0xffffffff;
    private static final String CSS = "img{max-width:100%;height:auto}";

    private Seekable src;
    private int[] offsets;
    private long fileSize;

    // ---- PDB records ---------------------------------------------------------------------------

    private byte[] record(int i) throws IOException { return recordHead(i, Integer.MAX_VALUE); }

    /** The first {@code max} bytes of a record (the whole record if it is shorter). */
    private byte[] recordHead(int i, int max) throws IOException {
        if (i < 0 || i >= offsets.length) throw new IOException("Bad record " + i);
        long start = offsets[i] & 0xffffffffL, end = i + 1 < offsets.length ? offsets[i + 1] & 0xffffffffL : fileSize;
        if (end < start || end > fileSize) throw new IOException("Corrupt record table");
        byte[] b = new byte[(int) Math.min(max, end - start)];
        src.readFully(start, b, 0, b.length);
        return b;
    }

    private void readTable() throws IOException {
        fileSize = src.size();
        byte[] pdb = new byte[78];
        src.readFully(0, pdb, 0, 78);
        int n = u16(pdb, 76);
        if (n <= 0) throw new IOException("Invalid MOBI file");
        byte[] table = new byte[n * 8];
        src.readFully(78, table, 0, table.length);
        offsets = new int[n];
        for (int i = 0; i < n; i++) offsets[i] = u32(table, i * 8);
        this.pdb = pdb;
    }

    private byte[] pdb;

    static int u16(byte[] b, int p) { return p + 1 < b.length ? (b[p] & 0xff) << 8 | (b[p + 1] & 0xff) : 0; }

    static int u32(byte[] b, int p) {
        return p + 3 < b.length ? (b[p] & 0xff) << 24 | (b[p + 1] & 0xff) << 16 | (b[p + 2] & 0xff) << 8 | (b[p + 3] & 0xff) : NULL;
    }

    private static boolean magic(byte[] b, int p, String m) {
        if (p + m.length() > b.length) return false;
        for (int i = 0; i < m.length(); i++) if (b[p + i] != m.charAt(i)) return false;
        return true;
    }

    public static boolean isMobi(byte[] head, int n) {
        return n >= 68 && (magic(head, 60, "BOOKMOBI") || magic(head, 60, "TEXtREAd"));
    }

    /** Header of one book section (MOBI 6 or KF8). */
    private static final class Header {
        int start, compression, textLength, textRecords, encryption, version, firstImage, huffOffset, huffCount,
                extraFlags, fdst = NULL, skel = NULL, frag = NULL, ncx = NULL, codepage;
        final Map<Integer, byte[]> exth = new HashMap<>();
        String fullName;

        Charset charset() { return codepage == 65001 ? StandardCharsets.UTF_8 : Charset.forName("windows-1252"); }

        int abs(int rel) { return rel == NULL ? NULL : rel + start; }
    }

    private Header header(int index) throws IOException {
        byte[] r = record(index);
        Header h = new Header();
        h.start = index;
        h.compression = u16(r, 0);
        h.textLength = u32(r, 4);
        h.textRecords = u16(r, 8);
        h.encryption = u16(r, 12);
        if (!magic(r, 16, "MOBI")) {
            h.codepage = 1252;
            return h; // plain PalmDOC
        }
        int len = u32(r, 20);
        h.codepage = u32(r, 28);
        h.version = u32(r, 36);
        int nameOff = u32(r, 84), nameLen = u32(r, 88);
        h.firstImage = u32(r, 108);
        h.huffOffset = u32(r, 112);
        h.huffCount = u32(r, 116);
        if (len >= 0xe4) h.extraFlags = u16(r, 0xf2);
        h.ncx = u32(r, 0xf4);
        if (h.version >= 8) {
            h.fdst = u32(r, 0xc0);
            h.frag = u32(r, 0xf8);
            h.skel = u32(r, 0xfc);
        }
        if ((u32(r, 128) & 0x40) != 0) {
            int p = 16 + len;
            if (magic(r, p, "EXTH")) {
                int count = u32(r, p + 8);
                p += 12;
                for (int i = 0; i < count && p + 8 <= r.length; i++) {
                    int type = u32(r, p), l = u32(r, p + 4);
                    if (l < 8 || p + l > r.length) break;
                    byte[] v = new byte[l - 8];
                    System.arraycopy(r, p + 8, v, 0, v.length);
                    h.exth.put(type, v);
                    p += l;
                }
            }
        }
        if (nameOff > 0 && nameLen > 0 && nameOff + nameLen <= r.length)
            h.fullName = new String(r, nameOff, nameLen, h.charset());
        return h;
    }

    // ---- text decompression ----------------------------------------------------------------

    private static int trailing(byte[] d, int size, int flags) {
        int num = 0;
        for (int f = flags >> 1; f != 0; f >>= 1) {
            if ((f & 1) == 0) continue;
            int pos = size - num, v = 0, shift = 0;
            // Backward-encoded variable width integer.
            while (pos > 0) {
                int c = d[--pos] & 0xff;
                v |= (c & 0x7f) << shift;
                shift += 7;
                if ((c & 0x80) != 0 || shift >= 28) break;
            }
            num += v;
        }
        if ((flags & 1) != 0 && size - num - 1 >= 0) num += (d[size - num - 1] & 3) + 1;
        return num;
    }

    static void palmdoc(byte[] in, int len, ByteArrayOutputStream out) {
        byte[] buf = new byte[len * 8 + 16];
        int o = 0;
        for (int i = 0; i < len; ) {
            int c = in[i++] & 0xff;
            if (c >= 1 && c <= 8) {
                for (int k = 0; k < c && i < len; k++) buf[o++] = in[i++];
            } else if (c < 0x80) {
                buf[o++] = (byte) c;
            } else if (c >= 0xc0) {
                buf[o++] = ' ';
                buf[o++] = (byte) (c ^ 0x80);
            } else if (i < len) {
                int x = (c << 8 | (in[i++] & 0xff)) & 0x3fff;
                int dist = x >> 3, n = (x & 7) + 3;
                if (dist == 0 || dist > o) continue;
                for (int k = 0; k < n; k++, o++) buf[o] = buf[o - dist];
            }
            if (o > buf.length - 16) break;
        }
        out.write(buf, 0, o);
    }

    /** HUFF/CDIC decompressor used by some Kindle publisher files. */
    private static final class Huff {
        final long[] dict1 = new long[256 * 3];
        final long[] mincode = new long[33], maxcode = new long[33];
        final List<byte[]> phrases = new ArrayList<>();
        final List<Boolean> done = new ArrayList<>();

        Huff(List<byte[]> recs) throws IOException {
            byte[] h = recs.get(0);
            if (!magic(h, 0, "HUFF")) throw new IOException("Bad HUFF record");
            int off1 = u32(h, 8), off2 = u32(h, 12);
            for (int i = 0; i < 256; i++) {
                long v = u32(h, off1 + i * 4) & 0xffffffffL;
                long codelen = v & 0x1f, term = v & 0x80, max = v >>> 8;
                if (codelen == 0) throw new IOException("Bad HUFF table");
                dict1[i * 3] = codelen;
                dict1[i * 3 + 1] = term;
                dict1[i * 3 + 2] = ((max + 1) << (32 - codelen)) - 1;
            }
            for (int len = 1; len <= 32; len++) {
                mincode[len] = (u32(h, off2 + (len - 1) * 8) & 0xffffffffL) << (32 - len);
                maxcode[len] = (((u32(h, off2 + (len - 1) * 8 + 4) & 0xffffffffL) + 1) << (32 - len)) - 1;
            }
            for (int r = 1; r < recs.size(); r++) {
                byte[] c = recs.get(r);
                if (!magic(c, 0, "CDIC")) throw new IOException("Bad CDIC record");
                int count = u32(c, 8), bits = u32(c, 12);
                int n = Math.min(1 << bits, count - phrases.size());
                for (int i = 0; i < n; i++) {
                    int off = u16(c, 16 + i * 2);
                    int blen = u16(c, 16 + off);
                    int l = blen & 0x7fff, s = 18 + off;
                    byte[] ph = new byte[Math.max(0, Math.min(l, c.length - s))];
                    System.arraycopy(c, s, ph, 0, ph.length);
                    phrases.add(ph);
                    done.add((blen & 0x8000) != 0);
                }
            }
        }

        void unpack(byte[] data, int len, ByteArrayOutputStream out, int depth) throws IOException {
            if (depth > 32) throw new IOException("HUFF recursion");
            byte[] d = new byte[len + 8];
            System.arraycopy(data, 0, d, 0, len);
            long bitsLeft = len * 8L;
            int pos = 0, n = 32;
            long x = getQ(d, 0);
            while (true) {
                if (n <= 0) {
                    pos += 4;
                    x = getQ(d, pos);
                    n += 32;
                }
                long code = (x >>> n) & 0xffffffffL;
                int idx = (int) (code >>> 24) * 3;
                int codelen = (int) dict1[idx];
                long term = dict1[idx + 1], max = dict1[idx + 2];
                if (term == 0) {
                    while (codelen < 32 && code < mincode[codelen]) codelen++;
                    max = maxcode[codelen];
                }
                n -= codelen;
                bitsLeft -= codelen;
                if (bitsLeft < 0) break;
                int r = (int) ((max - code) >>> (32 - codelen));
                if (r < 0 || r >= phrases.size()) throw new IOException("Bad HUFF code");
                byte[] ph = phrases.get(r);
                if (!done.get(r)) {
                    ByteArrayOutputStream sub = new ByteArrayOutputStream();
                    unpack(ph, ph.length, sub, depth + 1);
                    ph = sub.toByteArray();
                    phrases.set(r, ph);
                    done.set(r, true);
                }
                out.write(ph, 0, ph.length);
            }
        }

        private static long getQ(byte[] d, int p) {
            long v = 0;
            for (int i = 0; i < 8; i++) v = (v << 8) | (p + i < d.length ? d[p + i] & 0xff : 0);
            return v;
        }
    }

    private byte[] text(Header h) throws IOException {
        if (h.encryption != 0) throw new DrmException();
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(0, Math.min(h.textLength, 64 << 20)));
        Huff huff = null;
        if (h.compression == 17480) {
            List<byte[]> recs = new ArrayList<>();
            int first = h.abs(h.huffOffset);
            for (int i = 0; i < h.huffCount; i++) recs.add(record(first + i));
            huff = new Huff(recs);
        }
        for (int i = 1; i <= h.textRecords; i++) {
            byte[] r = record(h.start + i);
            int size = r.length - trailing(r, r.length, h.extraFlags);
            if (size <= 0) continue;
            if (h.compression == 2) palmdoc(r, size, out);
            else if (h.compression == 17480) huff.unpack(r, size, out, 0);
            else out.write(r, 0, size);
        }
        byte[] t = out.toByteArray();
        if (h.textLength > 0 && h.textLength < t.length) {
            byte[] c = new byte[h.textLength];
            System.arraycopy(t, 0, c, 0, c.length);
            t = c;
        }
        return t;
    }

    // ---- index (INDX / TAGX / CNCX) -----------------------------------------------------------

    static final class Index {
        final List<String> names = new ArrayList<>();
        final List<Map<Integer, int[]>> tags = new ArrayList<>();
        final Map<Integer, String> cncx = new HashMap<>();
    }

    /** Forward variable-width integer: value in [0], bytes consumed in [1]. */
    private static int[] decint(byte[] b, int p) {
        int v = 0, n = 0;
        while (p + n < b.length && n < 5) {
            int c = b[p + n++] & 0xff;
            v = (v << 7) | (c & 0x7f);
            if ((c & 0x80) != 0) break;
        }
        return new int[]{v, n};
    }

    private Index index(int idx, Charset cs) throws IOException {
        Index out = new Index();
        byte[] h = record(idx);
        if (!magic(h, 0, "INDX")) throw new IOException("Bad INDX");
        int count = u32(h, 24), ncncx = u32(h, 52);
        int tagx = -1;
        for (int i = 0; i + 4 <= h.length; i++) if (magic(h, i, "TAGX")) { tagx = i; break; }
        if (tagx < 0) throw new IOException("No TAGX");
        int firstEntry = u32(h, tagx + 4), controlBytes = u32(h, tagx + 8);
        List<int[]> tagDefs = new ArrayList<>(); // tag, values per entry, mask, eof
        for (int i = tagx + 12; i + 4 <= tagx + firstEntry && i + 4 <= h.length; i += 4)
            tagDefs.add(new int[]{h[i] & 0xff, h[i + 1] & 0xff, h[i + 2] & 0xff, h[i + 3] & 0xff});
        for (int c = 0; c < ncncx; c++) {
            byte[] r = record(idx + count + 1 + c);
            int pos = 0;
            while (pos < r.length) {
                int[] l = decint(r, pos);
                if (l[1] == 0) break;
                int s = pos + l[1];
                if (l[0] > 0 && s + l[0] <= r.length) out.cncx.put(c * 0x10000 + pos, new String(r, s, l[0], cs));
                pos = s + l[0];
            }
        }
        for (int i = idx + 1; i <= idx + count; i++) {
            byte[] d = record(i);
            if (!magic(d, 0, "INDX")) continue;
            int idxt = u32(d, 20), n = u32(d, 24);
            int[] starts = new int[n + 1];
            for (int j = 0; j < n; j++) starts[j] = u16(d, idxt + 4 + 2 * j);
            starts[n] = idxt;
            for (int j = 0; j < n; j++) {
                int p = starts[j], end = starts[j + 1];
                if (p >= d.length || end > d.length || p >= end) continue;
                int nl = d[p] & 0xff;
                String name = new String(d, p + 1, Math.min(nl, end - p - 1), cs);
                p += 1 + nl;
                int[] ctrl = new int[controlBytes];
                for (int k = 0; k < controlBytes && p + k < d.length; k++) ctrl[k] = d[p + k] & 0xff;
                p += controlBytes;
                int ci = 0;
                List<int[]> ptags = new ArrayList<>(); // tag, count, bytes, perEntry
                for (int[] t : tagDefs) {
                    if (t[3] == 1) {
                        ci++;
                        continue;
                    }
                    if (ci >= ctrl.length) break;
                    int value = ctrl[ci] & t[2];
                    if (value == 0) continue;
                    if (value == t[2]) {
                        if (Integer.bitCount(t[2]) > 1) {
                            int[] vb = decint(d, p);
                            p += vb[1];
                            ptags.add(new int[]{t[0], -1, vb[0], t[1]});
                        } else ptags.add(new int[]{t[0], 1, 0, t[1]});
                    } else {
                        int mask = t[2];
                        while ((mask & 1) == 0) {
                            mask >>= 1;
                            value >>= 1;
                        }
                        ptags.add(new int[]{t[0], value, 0, t[1]});
                    }
                }
                Map<Integer, int[]> map = new HashMap<>();
                for (int[] pt : ptags) {
                    List<Integer> vals = new ArrayList<>();
                    if (pt[1] >= 0) {
                        for (int k = 0; k < pt[1] * pt[3] && p < end; k++) {
                            int[] v = decint(d, p);
                            p += v[1];
                            vals.add(v[0]);
                        }
                    } else {
                        int consumed = 0;
                        while (consumed < pt[2] && p < end) {
                            int[] v = decint(d, p);
                            p += v[1];
                            consumed += v[1];
                            vals.add(v[0]);
                        }
                    }
                    int[] a = new int[vals.size()];
                    for (int k = 0; k < a.length; k++) a[k] = vals.get(k);
                    map.put(pt[0], a);
                }
                out.names.add(name);
                out.tags.add(map);
            }
        }
        return out;
    }

    private static int tag(Map<Integer, int[]> m, int tag, int i, int def) {
        int[] v = m.get(tag);
        return v != null && v.length > i ? v[i] : def;
    }

    // ---- entry point ---------------------------------------------------------------------------

    public static FlowBook parse(Seekable src, String name) throws IOException {
        MobiBook m = new MobiBook();
        m.src = src;
        try {
            return m.run(name);
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("Invalid MOBI file", e);
        }
    }

    /**
     * Only the cover picture (EXTH 201), without decompressing the text: used for library
     * thumbnails. Returns null if the book has no cover.
     */
    public static byte[] cover(Seekable src) throws IOException {
        MobiBook m = new MobiBook();
        m.src = src;
        try {
            m.readTable();
            Header h = m.header(0);
            byte[] c = h.exth.get(201);
            if (c == null || c.length < 4 || u32(c, 0) == NULL || h.firstImage == NULL || h.firstImage <= 0) return null;
            byte[] img = m.record(h.firstImage + u32(c, 0));
            return Text.sniffImage(img, img.length) != null ? img : null;
        } catch (RuntimeException e) {
            throw new IOException("Invalid MOBI file", e);
        }
    }

    private FlowBook run(String name) throws IOException {
        readTable();
        int n = offsets.length;
        Header h = header(0);
        if (!magic(pdb, 60, "BOOKMOBI")) {
            MemBook b = TextBooks.txt(text(h), name);
            b.title = new String(pdb, 0, 32, StandardCharsets.ISO_8859_1).trim().replace("\u0000", "");
            b.source = src;
            return b;
        }
        Header k8 = null;
        if (h.version >= 8) k8 = h;
        else {
            byte[] b = h.exth.get(121);
            if (b != null && b.length >= 4) {
                int boundary = u32(b, 0);
                if (boundary != NULL && boundary > 0 && boundary < n) {
                    try {
                        Header k = header(boundary);
                        if (k.version >= 8) k8 = k;
                    } catch (IOException ignored) {}
                }
            }
        }
        MemBook book;
        if (k8 != null) {
            try {
                book = kf8(k8, h);
            } catch (DrmException e) {
                throw e;
            } catch (IOException e) {
                if (k8 == h) throw e;
                book = mobi6(h); // joint file: the old-format part is a complete fallback
            }
        } else {
            book = mobi6(h);
        }
        Header meta = k8 != null ? k8 : h;
        book.title = exthString(meta, 503, exthString(h, 503, meta.fullName != null ? meta.fullName : h.fullName));
        if (book.title == null || book.title.isEmpty()) book.title = Text.baseName(name);
        book.author = exthString(meta, 100, exthString(h, 100, null));
        book.language = exthString(meta, 524, null);
        byte[] cover = h.exth.get(201);
        if (cover != null && cover.length >= 4 && u32(cover, 0) != NULL) {
            String p = "img/" + (u32(cover, 0) + 1);
            if (book.hasResource(p)) book.coverPath = p;
        }
        book.source = src; // pictures and fonts are read from the file on demand
        return book;
    }

    private static String exthString(Header h, int type, String def) {
        byte[] v = h.exth.get(type);
        if (v == null) return def;
        String s = new String(v, h.charset()).trim();
        return s.isEmpty() ? def : s;
    }

    /**
     * Images and fonts referenced as 1-based resource numbers from {@code first}. Only the record
     * headers are read here; the data is loaded from the file when the page asks for it, so
     * picture-heavy books (comics converted to AZW3) do not have to fit in memory.
     */
    private void addResources(MemBook b, int first) {
        if (first == NULL || first <= 0) return;
        for (int i = first, k = 1; i < offsets.length; i++, k++) {
            byte[] r;
            try {
                r = recordHead(i, 16);
            } catch (IOException e) {
                break;
            }
            if (r.length < 4) continue;
            if (magic(r, 0, "FLIS") || magic(r, 0, "FCIS") || magic(r, 0, "BOUNDARY") || magic(r, 0, "DATP")
                    || magic(r, 0, "SRCS") || magic(r, 0, "INDX") || (r.length == 4 && (r[0] & 0xff) == 0xe9)) {
                if (magic(r, 0, "BOUNDARY")) break;
                continue;
            }
            final int rec = i;
            if (magic(r, 0, "FONT")) {
                b.addResource("img/" + k, "font/ttf", () -> {
                    byte[] f = font(record(rec));
                    if (f == null) throw new IOException("Bad font record");
                    return f;
                });
                continue;
            }
            String mime = Text.sniffImage(r, r.length);
            if (mime != null) b.addResource("img/" + k, mime, () -> record(rec));
        }
    }

    private static byte[] font(byte[] r) {
        try {
            int usize = u32(r, 4), flags = u32(r, 8), dstart = u32(r, 12), xlen = u32(r, 16), xstart = u32(r, 20);
            byte[] d = new byte[r.length - dstart];
            System.arraycopy(r, dstart, d, 0, d.length);
            if ((flags & 2) != 0 && xlen > 0) {
                for (int i = 0; i < Math.min(1040, d.length); i++) d[i] ^= r[xstart + (i % xlen)];
            }
            if ((flags & 1) != 0) {
                Inflater inf = new Inflater();
                inf.setInput(d);
                byte[] out = new byte[usize];
                int got = 0;
                while (!inf.finished() && got < out.length) {
                    int k = inf.inflate(out, got, out.length - got);
                    if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                    got += k;
                }
                inf.end();
                return out;
            }
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- MOBI 6 ------------------------------------------------------------------------------------

    private static final Pattern FILEPOS = Pattern.compile("filepos\\s*=\\s*[\"']?0*(\\d+)[\"']?", Pattern.CASE_INSENSITIVE);
    private static final Pattern RECINDEX = Pattern.compile("(?:hi|lo)?recindex\\s*=\\s*[\"']?0*(\\d+)[\"']?", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAGEBREAK = Pattern.compile("<mbp:pagebreak\\s*/?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANCHOR_ID = Pattern.compile("<a id=\"fp(\\d+)\"");
    private static final Pattern HREF_FP = Pattern.compile("href=\"#fp(\\d+)\"");

    private MemBook mobi6(Header h) throws IOException {
        MemBook b = new MemBook();
        byte[] raw = text(h);
        String s = new String(raw, StandardCharsets.ISO_8859_1); // byte-preserving for filepos offsets
        TreeMap<Integer, Boolean> targets = new TreeMap<>();
        Matcher m = FILEPOS.matcher(s);
        while (m.find()) {
            try { targets.put(Integer.parseInt(m.group(1)), true); } catch (NumberFormatException ignored) {}
        }
        // NCX entries (if present) carry offsets too.
        List<Object[]> ncx = new ArrayList<>();
        if (h.ncx != NULL && h.ncx > 0) {
            try {
                Index idx = index(h.ncx, h.charset());
                for (Map<Integer, int[]> t : idx.tags) {
                    int pos = tag(t, 1, 0, -1), label = tag(t, 3, 0, -1), depth = tag(t, 4, 0, 0);
                    if (pos < 0) continue;
                    String title = idx.cncx.get(label);
                    if (title == null) continue;
                    ncx.add(new Object[]{title, depth, pos});
                    targets.put(pos, true);
                }
            } catch (IOException ignored) {}
        }
        // Insert anchors at every target offset in one forward pass (never splitting a tag).
        StringBuilder sb = new StringBuilder(s.length() + targets.size() * 24);
        int from = 0;
        for (Integer pos : targets.keySet()) {
            int p = Math.max(from, outsideTag(s, Math.min(pos, s.length())));
            sb.append(s, from, p).append("<a id=\"fp").append(pos).append("\"></a>");
            from = p;
        }
        sb.append(s, from, s.length());
        // Anchors placed just before a page break belong to the next page.
        s = sb.toString().replaceAll("(?i)((?:<a id=\"fp\\d+\"></a>\\s*)+)(<mbp:pagebreak\\s*/?>)", "$2$1");
        s = FILEPOS.matcher(s).replaceAll("href=\"#fp$1\"");
        s = RECINDEX.matcher(s).replaceAll("src=\"img/$1\"");
        s = new String(s.getBytes(StandardCharsets.ISO_8859_1), h.charset());
        s = s.replaceAll("(?is)<head>.*?</head>|</?html[^>]*>|</?body[^>]*>|<guide>.*?</guide>", "");
        String[] parts = PAGEBREAK.split(s);
        HashMap<String, Integer> where = new HashMap<>();
        List<String> chunks = new ArrayList<>();
        for (String part : parts) {
            if (part.trim().isEmpty() && !chunks.isEmpty()) continue;
            Matcher a = ANCHOR_ID.matcher(part);
            while (a.find()) where.put(a.group(1), chunks.size());
            chunks.add(part);
        }
        for (int i = 0; i < chunks.size(); i++) {
            final int ci = i;
            Matcher hm = HREF_FP.matcher(chunks.get(i));
            StringBuffer out = new StringBuffer();
            while (hm.find()) {
                Integer c = where.get(hm.group(1));
                String href = c == null || c == ci ? "#fp" + hm.group(1) : "c" + c + ".html#fp" + hm.group(1);
                hm.appendReplacement(out, Matcher.quoteReplacement("href=\"" + href + "\""));
            }
            hm.appendTail(out);
            b.addChapter(MemBook.page(null, null, CSS, out));
        }
        for (Object[] e : ncx) {
            Integer c = where.get(String.valueOf(e[2]));
            if (c != null) b.toc.add(new FlowBook.TocEntry((String) e[0], (Integer) e[1], c, "fp" + e[2]));
        }
        addResources(b, h.firstImage);
        return b;
    }

    // ---- KF8 -----------------------------------------------------------------------------------

    private static final Pattern KINDLE_POS = Pattern.compile("kindle:pos:fid:([0-9A-Va-v]+):off:([0-9A-Va-v]+)");
    private static final Pattern KINDLE_EMBED = Pattern.compile("kindle:embed:([0-9A-Va-v]+)(\\?mime=[\\w/+.-]*)?");
    private static final Pattern KINDLE_FLOW = Pattern.compile("kindle:flow:([0-9A-Va-v]+)(\\?mime=[\\w/+.-]*)?");

    private static int b32(String s) {
        try { return Integer.parseInt(s, 32); } catch (NumberFormatException e) { return -1; }
    }

    private MemBook kf8(Header k, Header mobi6) throws IOException {
        Charset cs = k.charset();
        byte[] raw = text(k);
        // Flow table: flow 0 is the markup, the others are CSS / SVG.
        List<int[]> flows = new ArrayList<>();
        int fdst = k.abs(k.fdst);
        if (k.fdst != NULL && fdst < offsets.length) {
            byte[] f = record(fdst);
            if (magic(f, 0, "FDST")) {
                int cnt = u32(f, 8);
                for (int i = 0; i < cnt; i++) flows.add(new int[]{u32(f, 12 + i * 8), u32(f, 16 + i * 8)});
            }
        }
        if (flows.isEmpty()) flows.add(new int[]{0, raw.length});
        int[] f0 = flows.get(0);
        byte[] ml = slice(raw, f0[0], f0[1]);

        if (k.skel == NULL || k.frag == NULL) throw new IOException("KF8 without skeleton");
        Index skel = index(k.abs(k.skel), cs), frag = index(k.abs(k.frag), cs);
        int fragPtr = 0;
        List<byte[]> parts = new ArrayList<>();
        List<int[]> partPos = new ArrayList<>(); // start, length in insert-position space
        for (int i = 0; i < skel.names.size(); i++) {
            Map<Integer, int[]> t = skel.tags.get(i);
            int fragCount = tag(t, 1, 0, 0), skelPos = tag(t, 6, 0, 0), skelLen = tag(t, 6, 1, 0);
            byte[] skeleton = slice(ml, skelPos, skelPos + skelLen);
            int base = skelPos + skelLen;
            for (int j = 0; j < fragCount && fragPtr < frag.names.size(); j++, fragPtr++) {
                Map<Integer, int[]> ft = frag.tags.get(fragPtr);
                int insert;
                try {
                    insert = Integer.parseInt(frag.names.get(fragPtr).trim()) - skelPos;
                } catch (NumberFormatException e) {
                    insert = skeleton.length;
                }
                int len = tag(ft, 6, 1, 0);
                insert = Math.max(0, Math.min(skeleton.length, insert));
                insert = outsideTag(skeleton, insert);
                byte[] piece = slice(ml, base, base + len);
                byte[] joined = new byte[skeleton.length + piece.length];
                System.arraycopy(skeleton, 0, joined, 0, insert);
                System.arraycopy(piece, 0, joined, insert, piece.length);
                System.arraycopy(skeleton, insert, joined, insert + piece.length, skeleton.length - insert);
                skeleton = joined;
                base += len;
            }
            parts.add(skeleton);
            partPos.add(new int[]{skelPos, skeleton.length});
        }
        if (parts.isEmpty()) throw new IOException("Empty KF8 book");

        // Positions referenced by links and the NCX: kindle:pos:fid:F:off:O = frag[F].insert + O.
        int[] fragInsert = new int[frag.names.size()];
        for (int i = 0; i < fragInsert.length; i++) {
            try { fragInsert[i] = Integer.parseInt(frag.names.get(i).trim()); } catch (NumberFormatException e) { fragInsert[i] = -1; }
        }
        List<TreeMap<Integer, String>> anchors = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) anchors.add(new TreeMap<>());
        List<Object[]> ncx = new ArrayList<>();
        for (byte[] p : parts) {
            Matcher m = KINDLE_POS.matcher(new String(p, StandardCharsets.ISO_8859_1));
            while (m.find()) anchorFor(fragInsert, partPos, anchors, b32(m.group(1)), b32(m.group(2)));
        }
        if (k.ncx != NULL) {
            try {
                Index idx = index(k.abs(k.ncx), cs);
                for (Map<Integer, int[]> t : idx.tags) {
                    String title = idx.cncx.get(tag(t, 3, 0, -1));
                    int depth = tag(t, 4, 0, 0), fid = tag(t, 6, 0, -1), off = tag(t, 6, 1, 0);
                    if (title == null || fid < 0) continue;
                    String[] a = anchorFor(fragInsert, partPos, anchors, fid, off);
                    if (a != null) ncx.add(new Object[]{title, depth, Integer.parseInt(a[0]), a[1]});
                }
            } catch (IOException | RuntimeException ignored) {}
        }

        MemBook b = new MemBook();
        for (int i = 0; i < parts.size(); i++) {
            byte[] p = parts.get(i);
            // Insert anchors back to front so earlier offsets stay valid.
            StringBuilder sb = new StringBuilder(new String(p, StandardCharsets.ISO_8859_1));
            for (Map.Entry<Integer, String> e : anchors.get(i).descendingMap().entrySet())
                sb.insert(outsideTag(sb, Math.min(e.getKey(), sb.length())), "<a id=\"" + e.getValue() + "\"></a>");
            String html = new String(sb.toString().getBytes(StandardCharsets.ISO_8859_1), cs);
            html = rewriteKindle(html, fragInsert, partPos, anchors);
            b.addChapter(FlowBook.xhtmlToHtml(html));
        }
        for (Object[] e : ncx) b.toc.add(new FlowBook.TocEntry((String) e[0], (Integer) e[1], (Integer) e[2], (String) e[3]));
        // Resources are shared with the MOBI 6 part in joint files.
        int first = k.firstImage != NULL ? k.abs(k.firstImage) : NULL;
        if (k != mobi6 && (first == NULL || first >= offsets.length || sniffRecord(first) == null))
            first = mobi6.firstImage;
        addResources(b, first);
        for (int i = 1; i < flows.size(); i++) {
            int[] f = flows.get(i);
            String flow = new String(slice(raw, f[0], f[1]), cs);
            if (flow.trim().startsWith("<")) {
                // SVG flows are shown through <img>, which cannot load external files: inline their pictures.
                Matcher m = KINDLE_EMBED.matcher(flow);
                StringBuffer out = new StringBuffer();
                while (m.find()) {
                    String path = "img/" + b32(m.group(1));
                    byte[] data = b.resourceBytes(path);
                    String uri = data == null ? "" : "data:" + b.resourceMime(path) + ";base64," + Text.base64Encode(data);
                    m.appendReplacement(out, Matcher.quoteReplacement(uri));
                }
                m.appendTail(out);
                b.addResource("flow/" + i, "image/svg+xml", out.toString().getBytes(StandardCharsets.UTF_8));
            } else {
                flow = resolveEmbeds(KINDLE_EMBED.matcher(flow).replaceAll("../img/__$1__"), "../img/");
                b.addResource("flow/" + i, "text/css", flow.getBytes(StandardCharsets.UTF_8));
            }
        }
        return b;
    }

    private String sniffRecord(int i) throws IOException {
        byte[] r = recordHead(i, 16);
        return Text.sniffImage(r, r.length);
    }

    private static String resolveEmbeds(String s, String prefix) {
        Matcher m = Pattern.compile(Pattern.quote(prefix) + "__([0-9A-Va-v]+)__").matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(prefix + b32(m.group(1))));
        m.appendTail(out);
        return out.toString();
    }

    private String rewriteKindle(String html, int[] fragInsert, List<int[]> partPos, List<TreeMap<Integer, String>> anchors) {
        Matcher m = KINDLE_POS.matcher(html);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String[] a = anchorFor(fragInsert, partPos, anchors, b32(m.group(1)), b32(m.group(2)));
            m.appendReplacement(out, Matcher.quoteReplacement(a == null ? "#" : "c" + a[0] + ".html#" + a[1]));
        }
        m.appendTail(out);
        html = out.toString();
        m = KINDLE_EMBED.matcher(html);
        out = new StringBuffer();
        while (m.find()) m.appendReplacement(out, "img/" + b32(m.group(1)));
        m.appendTail(out);
        html = out.toString();
        m = KINDLE_FLOW.matcher(html);
        out = new StringBuffer();
        while (m.find()) m.appendReplacement(out, "flow/" + b32(m.group(1)));
        m.appendTail(out);
        return out.toString();
    }

    /** Registers (or finds) an anchor for a fid/offset pair; returns {part index, anchor id}. */
    private static String[] anchorFor(int[] fragInsert, List<int[]> partPos, List<TreeMap<Integer, String>> anchors, int fid, int off) {
        if (fid < 0 || fid >= fragInsert.length || fragInsert[fid] < 0 || off < 0) return null;
        int pos = fragInsert[fid] + off;
        for (int i = 0; i < partPos.size(); i++) {
            int[] pp = partPos.get(i);
            if (pos >= pp[0] && pos < pp[0] + pp[1] || (i == partPos.size() - 1 && pos >= pp[0])) {
                int local = Math.min(pos - pp[0], pp[1]);
                String id = anchors.get(i).get(local);
                if (id == null) {
                    id = "kp" + pos;
                    anchors.get(i).put(local, id);
                }
                return new String[]{String.valueOf(i), id};
            }
        }
        return null;
    }

    /** Moves an insertion point that falls inside a tag to just after that tag. */
    private static int outsideTag(byte[] s, int p) {
        int lt = -1, gt = -1;
        for (int i = p - 1; i >= 0; i--) {
            if (s[i] == '>') { gt = i; break; }
            if (s[i] == '<') { lt = i; break; }
        }
        if (lt < 0 || gt >= 0) return p;
        for (int i = p; i < s.length; i++) if (s[i] == '>') return i + 1;
        return p;
    }

    private static int outsideTag(CharSequence s, int p) {
        for (int i = p - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == '>') return p;
            if (c == '<') {
                for (int k = p, n = s.length(); k < n; k++) if (s.charAt(k) == '>') return k + 1;
                return p;
            }
        }
        return p;
    }

    private static byte[] slice(byte[] b, int from, int to) {
        from = Math.max(0, Math.min(from, b.length));
        to = Math.max(from, Math.min(to, b.length));
        byte[] r = new byte[to - from];
        System.arraycopy(b, from, r, 0, r.length);
        return r;
    }
}
