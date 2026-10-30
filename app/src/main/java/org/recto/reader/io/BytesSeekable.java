package org.recto.reader.io;

/** In-memory {@link Seekable}. */
public final class BytesSeekable implements Seekable {
    private final byte[] data;

    public BytesSeekable(byte[] data) { this.data = data; }

    @Override public long size() { return data.length; }

    @Override public int read(long pos, byte[] b, int off, int len) {
        if (pos >= data.length) return -1;
        int n = (int) Math.min(len, data.length - pos);
        System.arraycopy(data, (int) pos, b, off, n);
        return n;
    }

    @Override public void close() {}
}
