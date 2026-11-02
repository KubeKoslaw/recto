package org.recto.reader;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import org.recto.reader.io.FileSeekable;
import org.recto.reader.io.Seekable;
import org.recto.reader.util.Text;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Access to documents behind content:// and file:// URIs, always as seekable file descriptors. */
final class Source {
    private Source() {}

    static final class Info {
        String name = "document";
        long size = -1;
        String mime;
    }

    static Info info(Context c, Uri uri) {
        Info i = new Info();
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            File f = new File(uri.getPath());
            i.name = f.getName();
            i.size = f.length();
            return i;
        }
        try (Cursor cur = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                if (!cur.isNull(0)) i.name = cur.getString(0);
                if (!cur.isNull(1)) i.size = cur.getLong(1);
            }
        } catch (Exception ignored) {}
        try { i.mime = c.getContentResolver().getType(uri); } catch (Exception ignored) {}
        if ("document".equals(i.name) && uri.getLastPathSegment() != null) {
            String seg = uri.getLastPathSegment();
            i.name = seg.substring(seg.lastIndexOf('/') + 1);
        }
        return i;
    }

    private static ParcelFileDescriptor raw(Context c, Uri uri) throws IOException {
        ParcelFileDescriptor fd;
        if ("file".equals(uri.getScheme())) {
            if (uri.getPath() == null) throw new FileNotFoundException();
            fd = ParcelFileDescriptor.open(new File(uri.getPath()), ParcelFileDescriptor.MODE_READ_ONLY);
        } else {
            fd = c.getContentResolver().openFileDescriptor(uri, "r");
        }
        if (fd == null) throw new FileNotFoundException(uri.toString());
        return fd;
    }

    /** The document is only available as a stream and copying it was not allowed. */
    static final class NotSeekable extends IOException {
        NotSeekable() { super("not seekable"); }
    }

    /** Cached copies of streamed documents kept besides the one being opened. */
    private static final int KEEP_COPIES = 2;
    private static final Object CACHE_LOCK = new Object();

    /**
     * A seekable descriptor. Streams (pipes from cloud providers) are copied to the cache first if
     * {@code allowCopy}; otherwise {@link NotSeekable} is thrown (background work must not download).
     */
    static ParcelFileDescriptor openFd(Context c, Uri uri, boolean allowCopy) throws IOException {
        ParcelFileDescriptor fd = raw(c, uri);
        if (fd.getStatSize() >= 0) return fd;
        if (!allowCopy) {
            Text.close(fd);
            throw new NotSeekable();
        }
        File copy = cacheCopy(c, uri, fd);
        return ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    static Seekable seekable(ParcelFileDescriptor fd) throws IOException {
        return new FileSeekable(new FileInputStream(fd.getFileDescriptor()), fd);
    }

    private static File cacheCopy(Context c, Uri uri, ParcelFileDescriptor fd) throws IOException {
        // One copy at a time: concurrent copies must not delete each other's files.
        synchronized (CACHE_LOCK) {
            File dir = new File(c.getCacheDir(), "open");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                Text.close(fd);
                throw new IOException("No cache directory");
            }
            File dst = new File(dir, Text.sha1(uri.toString()));
            if (dst.isFile() && dst.length() > 0) {
                Text.close(fd);
                dst.setLastModified(System.currentTimeMillis()); // most recently used
                return dst;
            }
            File tmp = new File(dir, dst.getName() + ".part");
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            } catch (IOException e) {
                tmp.delete();
                throw e;
            }
            if (!tmp.renameTo(dst)) {
                tmp.delete();
                throw new IOException("Cannot cache document");
            }
            trim(dir, dst);
            return dst;
        }
    }

    /** Keeps the newest few copies (open descriptors stay valid even if their file is deleted). */
    private static void trim(File dir, File keep) {
        File[] files = dir.listFiles();
        if (files == null) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        int kept = 0;
        for (File f : files) {
            if (f.equals(keep)) continue;
            if (f.getName().endsWith(".part") || ++kept > KEEP_COPIES) f.delete();
        }
    }
}
