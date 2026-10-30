package org.recto.reader.io;

import java.io.Closeable;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** {@link Seekable} over a {@link FileChannel}; positional reads are safe from any thread. */
public final class FileSeekable implements Seekable {
    private final FileChannel ch;
    private final Closeable owner;
    private final long size;

    public FileSeekable(FileInputStream in, Closeable owner) throws IOException {
        this.ch = in.getChannel();
        this.owner = owner;
        this.size = ch.size();
    }

    public FileSeekable(RandomAccessFile f) throws IOException {
        this.ch = f.getChannel();
        this.owner = f;
        this.size = ch.size();
    }

    @Override public long size() { return size; }

    @Override public int read(long pos, byte[] b, int off, int len) throws IOException {
        if (pos >= size) return -1;
        return ch.read(ByteBuffer.wrap(b, off, len), pos);
    }

    @Override public void close() throws IOException {
        try { ch.close(); } finally { if (owner != null) owner.close(); }
    }
}
