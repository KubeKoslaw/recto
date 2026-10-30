package org.recto.reader.io;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;

/** Random-access, thread-safe byte source (positional reads only). */
public interface Seekable extends Closeable {
    long size() throws IOException;

    /** Reads up to {@code len} bytes at {@code pos}. Returns the count read, or -1 at end. */
    int read(long pos, byte[] b, int off, int len) throws IOException;

    default void readFully(long pos, byte[] b, int off, int len) throws IOException {
        while (len > 0) {
            int n = read(pos, b, off, len);
            if (n < 0) throw new EOFException();
            pos += n;
            off += n;
            len -= n;
        }
    }
}
