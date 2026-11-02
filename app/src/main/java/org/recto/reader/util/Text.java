package org.recto.reader.util;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Small text / IO helpers shared by the converters. */
public final class Text {
    private Text() {}

    public static String esc(CharSequence s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        esc(sb, s);
        return sb.toString();
    }

    public static void esc(StringBuilder sb, CharSequence s) {
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '&': sb.append("&amp;"); break;
                case '"': sb.append("&quot;"); break;
                default: sb.append(c);
            }
        }
    }

    public static byte[] readAll(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[16384];
        long total = 0;
        for (int n; (n = in.read(buf)) > 0; ) {
            total += n;
            if (total > limit) throw new IOException("File too large");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    public static void close(Closeable c) {
        if (c != null) try { c.close(); } catch (Exception ignored) {}
    }

    public static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) sb.append(String.format(Locale.ROOT, "%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    public static boolean isUtf8(byte[] b, int off, int len) { return strictUtf8(b, off, len) != null; }

    /** Decodes strictly as UTF-8; null if the bytes are not valid UTF-8. */
    public static String strictUtf8(byte[] b, int off, int len) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b, off, len)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /**
     * Decodes a plain-text file: BOM, then strict UTF-8, then the legacy 8-bit code page most
     * likely for the device language (windows-1250/1251/1252...).
     */
    public static String decode(byte[] b) {
        int n = b.length;
        if (n >= 3 && (b[0] & 0xff) == 0xef && (b[1] & 0xff) == 0xbb && (b[2] & 0xff) == 0xbf)
            return new String(b, 3, n - 3, StandardCharsets.UTF_8);
        if (n >= 2 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xfe) return new String(b, 2, n - 2, StandardCharsets.UTF_16LE);
        if (n >= 2 && (b[0] & 0xff) == 0xfe && (b[1] & 0xff) == 0xff) return new String(b, 2, n - 2, StandardCharsets.UTF_16BE);
        // Truncated multi-byte sequence at the very end must not make a UTF-8 file look legacy.
        int check = n;
        for (int k = 0; k < 3 && check > 0 && (b[check - 1] & 0xc0) == 0x80; k++) check--;
        if (check > 0 && (b[check - 1] & 0xc0) == 0xc0) check--;
        // Validate and decode in one pass (the text is not decoded twice).
        String utf8 = strictUtf8(b, 0, check);
        if (utf8 != null) return check == n ? utf8 : new String(b, StandardCharsets.UTF_8);
        return new String(b, guessLegacy(b));
    }

    /**
     * Picks an 8-bit code page from the content: Cyrillic text is mostly non-ASCII letters, and
     * Central European letters (ą ł ż ś...) become symbols (¹ ³ ¿ œ) when misread as windows-1252.
     * Ties go to the code page of the device language.
     */
    public static Charset guessLegacy(byte[] b) {
        Charset loc = legacyCharset();
        int n = Math.min(b.length, 1 << 16);
        try {
            Charset w1250 = Charset.forName("windows-1250"), w1251 = Charset.forName("windows-1251"), w1252 = Charset.forName("windows-1252");
            String s = new String(b, 0, n, w1252);
            int letters = 0, nonAscii = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (Character.isLetter(c)) {
                    letters++;
                    if (c > 127) nonAscii++;
                }
            }
            String name = loc.name();
            if (letters > 0 && nonAscii * 2 > letters) {
                boolean single = name.equals("windows-1250") || name.equals("windows-1252") || name.equals("windows-1254") || name.equals("windows-1257");
                return single ? w1251 : loc;
            }
            double a = letterScore(b, n, w1250), c = letterScore(b, n, w1252);
            if (Math.abs(a - c) < 0.02) return name.equals("windows-1250") || name.equals("windows-1252") ? loc : w1252;
            return a > c ? w1250 : w1252;
        } catch (Exception e) {
            return loc;
        }
    }

    /** Share of decoded non-ASCII characters that are letters. */
    private static double letterScore(byte[] b, int n, Charset cs) {
        String s = new String(b, 0, n, cs);
        int total = 0, letters = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 127) continue;
            total++;
            if (Character.isLetter(c)) letters++;
        }
        return total == 0 ? 1 : letters / (double) total;
    }

    public static Charset legacyCharset() {
        String lang = Locale.getDefault().getLanguage();
        String cs;
        switch (lang) {
            case "pl": case "cs": case "sk": case "hu": case "hr": case "sl": case "ro": case "bs": case "sq":
                cs = "windows-1250"; break;
            case "ru": case "uk": case "be": case "bg": case "sr": case "mk":
                cs = "windows-1251"; break;
            case "el": cs = "windows-1253"; break;
            case "tr": cs = "windows-1254"; break;
            case "he": case "iw": cs = "windows-1255"; break;
            case "ar": case "fa": cs = "windows-1256"; break;
            case "lt": case "lv": case "et": cs = "windows-1257"; break;
            case "ja": cs = "Shift_JIS"; break;
            case "zh": cs = "GBK"; break;
            case "ko": cs = "EUC-KR"; break;
            default: cs = "windows-1252";
        }
        try {
            return Charset.forName(cs);
        } catch (Exception e) {
            return StandardCharsets.ISO_8859_1;
        }
    }

    /** Lenient base64 decoder (skips whitespace and junk). */
    public static byte[] base64(CharSequence s) {
        byte[] out = new byte[s.length() * 3 / 4 + 3];
        int n = 0, acc = 0, bits = 0;
        for (int i = 0, len = s.length(); i < len; i++) {
            char c = s.charAt(i);
            int v;
            if (c >= 'A' && c <= 'Z') v = c - 'A';
            else if (c >= 'a' && c <= 'z') v = c - 'a' + 26;
            else if (c >= '0' && c <= '9') v = c - '0' + 52;
            else if (c == '+' || c == '-') v = 62;
            else if (c == '/' || c == '_') v = 63;
            else if (c == '=') break;
            else continue;
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[n++] = (byte) (acc >> bits);
            }
        }
        byte[] r = new byte[n];
        System.arraycopy(out, 0, r, 0, n);
        return r;
    }

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    public static String base64Encode(byte[] d) {
        StringBuilder sb = new StringBuilder((d.length + 2) / 3 * 4);
        for (int i = 0; i < d.length; i += 3) {
            int n = (d[i] & 0xff) << 16 | (i + 1 < d.length ? (d[i + 1] & 0xff) << 8 : 0) | (i + 2 < d.length ? d[i + 2] & 0xff : 0);
            sb.append(B64[n >> 18 & 63]).append(B64[n >> 12 & 63]);
            sb.append(i + 1 < d.length ? B64[n >> 6 & 63] : '=').append(i + 2 < d.length ? B64[n & 63] : '=');
        }
        return sb.toString();
    }

    /** Extension of a file name, lower-case, without the dot ("" if none). */
    public static String ext(String name) {
        if (name == null) return "";
        int slash = name.lastIndexOf('/'), dot = name.lastIndexOf('.');
        return dot > slash && dot < name.length() - 1 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    public static String baseName(String name) {
        if (name == null) return "";
        int slash = name.lastIndexOf('/');
        String b = name.substring(slash + 1);
        int dot = b.lastIndexOf('.');
        if (b.toLowerCase(Locale.ROOT).endsWith(".fb2.zip")) dot = b.length() - 8;
        return dot > 0 ? b.substring(0, dot) : b;
    }

    public static boolean isImageName(String name) {
        switch (ext(name)) {
            case "jpg": case "jpeg": case "jpe": case "jfif": case "png": case "gif": case "webp":
            case "bmp": case "heic": case "heif": case "avif":
                return true;
            default:
                return false;
        }
    }

    public static String imageMime(String name) {
        switch (ext(name)) {
            case "png": return "image/png";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "bmp": return "image/bmp";
            case "svg": return "image/svg+xml";
            case "heic": case "heif": return "image/heif";
            case "avif": return "image/avif";
            default: return "image/jpeg";
        }
    }

    /** Sniffs an image MIME type from its first bytes (null if unknown). */
    public static String sniffImage(byte[] b, int n) {
        if (n >= 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff) return "image/jpeg";
        if (n >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (n >= 4 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return "image/gif";
        if (n >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P')
            return "image/webp";
        if (n >= 2 && b[0] == 'B' && b[1] == 'M') return "image/bmp";
        if (n >= 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') {
            String brand = new String(b, 8, 4, StandardCharsets.ISO_8859_1);
            if (brand.startsWith("avi")) return "image/avif";
            if (brand.startsWith("hei") || brand.startsWith("mif") || brand.startsWith("msf") || brand.startsWith("hev")) return "image/heif";
        }
        return null;
    }
}
