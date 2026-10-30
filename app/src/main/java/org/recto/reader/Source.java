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

    /** A seekable descriptor; streams (pipes from cloud providers) are copied to the cache first. */
    static ParcelFileDescriptor openFd(Context c, Uri uri) throws IOException {
        ParcelFileDescriptor fd = raw(c, uri);
        if (fd.getStatSize() >= 0) return fd;
        File copy = cacheCopy(c, uri, fd);
        return ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    static Seekable open(Context c, Uri uri) throws IOException {
        ParcelFileDescriptor fd = openFd(c, uri);
        return new FileSeekable(new FileInputStream(fd.getFileDescriptor()), fd);
    }

    private static File cacheCopy(Context c, Uri uri, ParcelFileDescriptor fd) throws IOException {
        File dir = new File(c.getCacheDir(), "open");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("No cache directory");
        File dst = new File(dir, Text.sha1(uri.toString()));
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) if (!f.equals(dst)) f.delete();
        if (dst.isFile() && dst.length() > 0) {
            fd.close();
            return dst;
        }
        File tmp = new File(dir, dst.getName() + ".part");
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        }
        if (!tmp.renameTo(dst)) throw new IOException("Cannot cache document");
        return dst;
    }
}
