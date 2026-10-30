package org.recto.reader.io;

import java.io.IOException;
import java.io.InputStream;

/** Sequential stream over a window of a {@link Seekable}. */
public final class SliceStream extends InputStream {
    private final Seekable src;
    private long pos;
    private final long end;

    public SliceStream(Seekable src, long start, long length) {
        this.src = src;
        this.pos = start;
        this.end = start + length;
    }

    @Override public int read() throws IOException {
        byte[] one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
    }

    @Override public int read(byte[] b, int off, int len) throws IOException {
        if (pos >= end) return -1;
        int n = src.read(pos, b, off, (int) Math.min(len, end - pos));
        if (n > 0) pos += n;
        return n;
    }

    @Override public long skip(long n) {
        long k = Math.max(0, Math.min(n, end - pos));
        pos += k;
        return k;
    }

    @Override public int available() { return (int) Math.min(Integer.MAX_VALUE, end - pos); }
}
