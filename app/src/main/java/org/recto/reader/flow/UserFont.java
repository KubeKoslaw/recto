package org.recto.reader.flow;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * One typeface chosen by the reader, copied into app files and served to the page as
 * {@code @font-face}. The page only ever sees a URL under the book origin, never a file path.
 */
public final class UserFont {
    /** Index beside Book / Serif / Sans / Mono. */
    public static final int SLOT = 4;
    /** Path under the intercepted book origin. */
    public static final String PATH = "recto-user-font";
    public static final String FILE = "user.font";
    public static final int MAX_BYTES = 8 << 20;
    public static final String FAMILY = "RectoUser";

    private UserFont() {}

    public static File file(File dir) { return new File(dir, FILE); }

    public static boolean supported(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".woff");
    }

    public static String mime(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".otf")) return "font/otf";
        if (n.endsWith(".woff")) return "font/woff";
        return "font/ttf";
    }

    public static String face(String url) {
        return "@font-face{font-family:'" + FAMILY + "';src:url('" + url + "')}";
    }

    public static String family() {
        return "body,body *:not(pre):not(code):not(kbd):not(samp):not(tt){font-family:'" + FAMILY + "'!important}";
    }

    /**
     * Copies the stream onto {@code dest}, replacing it. Returns the number of bytes written,
     * 0 when the stream is empty, or -1 when it is larger than {@link #MAX_BYTES} (dest is left
     * unchanged in that case).
     */
    public static long store(InputStream in, File dest) throws IOException {
        File tmp = new File(dest.getPath() + ".tmp");
        long n = 0;
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) >= 0) {
                n += r;
                if (n > MAX_BYTES) {
                    out.close();
                    tmp.delete();
                    return -1;
                }
                out.write(buf, 0, r);
            }
            out.getFD().sync();
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
        if (n == 0 || !tmp.renameTo(dest)) {
            tmp.delete();
            return 0;
        }
        return n;
    }
}
