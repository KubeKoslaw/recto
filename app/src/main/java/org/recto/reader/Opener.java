package org.recto.reader;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import org.recto.reader.fixed.ImageSource;
import org.recto.reader.fixed.PageSource;
import org.recto.reader.fixed.PdfSource;
import org.recto.reader.flow.EpubBook;
import org.recto.reader.flow.Fb2Book;
import org.recto.reader.flow.FlowBook;
import org.recto.reader.flow.MobiBook;
import org.recto.reader.flow.OfficeBooks;
import org.recto.reader.flow.TextBooks;
import org.recto.reader.io.Archive;
import org.recto.reader.io.RarArchive;
import org.recto.reader.io.Seekable;
import org.recto.reader.io.SliceStream;
import org.recto.reader.io.TarArchive;
import org.recto.reader.io.ZipArchive;
import org.recto.reader.util.Natural;
import org.recto.reader.util.Text;
import org.recto.reader.util.Xml;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Detects a document's format from its bytes (falling back to the name) and opens it. */
final class Opener {
    private Opener() {}

    /** User-facing problem (message resource id). */
    static final class Failure extends IOException {
        final int message;

        Failure(int message) {
            super("open failed");
            this.message = message;
        }
    }

    static final class PasswordNeeded extends IOException {
        PasswordNeeded() { super("password"); }
    }

    /** An opened document: either fixed pages or a reflowable book. */
    static final class Doc {
        String id, name, title, author, format;
        long size;
        PageSource pages;
        FlowBook book;
        boolean comic, rtl;
        float firstW = 1, firstH = 1.414f;

        void close() {
            Text.close(pages);
            Text.close(book);
        }
    }

    static final String[] EXTENSIONS = {"pdf", "epub", "kepub", "mobi", "azw", "azw3", "prc", "fb2", "fbz", "txt", "md", "markdown",
            "html", "htm", "xhtml", "docx", "odt", "cbz", "cbr", "cbt"};

    static boolean supportedName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".fb2.zip")) return true;
        String e = Text.ext(n);
        for (String x : EXTENSIONS) if (x.equals(e)) return true;
        return false;
    }

    static Doc open(Context c, Uri uri, String password) throws IOException {
        Source.Info info = Source.info(c, uri);
        Doc d = new Doc();
        d.name = info.name;
        d.size = info.size;
        d.title = Text.baseName(info.name);
        Seekable src = Source.open(c, uri);
        if (d.size < 0) d.size = src.size();
        d.id = Library.idFor(d.name, d.size);
        boolean keep = false;
        try {
            byte[] head = new byte[4096];
            int n = Math.max(0, src.read(0, head, 0, head.length));
            String ext = Text.ext(info.name);
            if (isPdf(head, n) || (n == 0 && ext.equals("pdf"))) {
                Text.close(src);
                src = null;
                openPdf(c, uri, password, d);
            } else if (n >= 4 && head[0] == 'P' && head[1] == 'K' && (head[2] == 3 || head[2] == 5)) {
                keep = openZip(new ZipArchive(src), d);
            } else if (RarArchive.isRar(head, n)) {
                RarArchive rar = new RarArchive(src);
                keep = true;
                openComic(rar, d, "CBR");
                if (rar.hasCompressed()) {
                    keep = false;
                    throw new Failure(R.string.err_rar);
                }
            } else if (TarArchive.isTar(head, n)) {
                keep = true;
                openComic(new TarArchive(src), d, "CBT");
            } else if (n >= 6 && head[0] == '7' && head[1] == 'z' && (head[2] & 0xff) == 0xbc && (head[3] & 0xff) == 0xaf) {
                throw new Failure(R.string.err_7z);
            } else if (MobiBook.isMobi(head, n)) {
                d.book = MobiBook.parse(src, info.name);
                d.format = ext.startsWith("azw") ? "AZW" : "MOBI";
                keep = true;
            } else if (Text.sniffImage(head, n) != null) {
                d.pages = new ImageSource(new ImageSource.SinglePage(src));
                d.comic = true;
                d.format = Text.ext(info.name).toUpperCase(Locale.ROOT);
                if (d.format.isEmpty()) d.format = "IMG";
                keep = true;
            } else {
                String text = new String(head, 0, n, StandardCharsets.ISO_8859_1);
                String lower = text.toLowerCase(Locale.ROOT);
                if (lower.contains("<fictionbook")) {
                    try (InputStream in = new SliceStream(src, 0, src.size())) {
                        d.book = Fb2Book.parse(in);
                    }
                    d.format = "FB2";
                } else if (ext.equals("md") || ext.equals("markdown")) {
                    d.book = TextBooks.markdown(readAll(src), info.name);
                    d.format = "MD";
                } else if (ext.equals("html") || ext.equals("htm") || ext.equals("xhtml") || lower.contains("<html")
                        || lower.contains("<!doctype html")) {
                    d.book = TextBooks.html(readAll(src), info.name);
                    d.format = "HTML";
                } else if (looksLikeText(head, n)) {
                    d.book = TextBooks.txt(readAll(src), info.name);
                    d.format = "TXT";
                } else {
                    throw new Failure(R.string.err_format);
                }
            }
            finish(d);
            return d;
        } catch (OutOfMemoryError e) {
            throw new Failure(R.string.err_memory);
        } finally {
            if (!keep) Text.close(src);
        }
    }

    private static void finish(Doc d) {
        if (d.book != null) {
            if (d.book.title != null && !d.book.title.trim().isEmpty()) d.title = d.book.title.trim();
            d.author = d.book.author;
            d.rtl |= d.book.rtl;
        }
    }

    private static byte[] readAll(Seekable s) throws IOException {
        long n = s.size();
        if (n > 64L << 20) throw new Failure(R.string.err_too_large);
        byte[] b = new byte[(int) n];
        s.readFully(0, b, 0, b.length);
        return b;
    }

    private static boolean isPdf(byte[] h, int n) {
        for (int i = 0; i + 4 < Math.min(n, 1024); i++)
            if (h[i] == '%' && h[i + 1] == 'P' && h[i + 2] == 'D' && h[i + 3] == 'F' && h[i + 4] == '-') return true;
        return false;
    }

    private static boolean looksLikeText(byte[] h, int n) {
        if (n == 0) return true;
        if (n >= 2 && ((h[0] & 0xff) == 0xff && (h[1] & 0xff) == 0xfe || (h[0] & 0xff) == 0xfe && (h[1] & 0xff) == 0xff)) return true;
        int ctrl = 0;
        for (int i = 0; i < n; i++) {
            int b = h[i] & 0xff;
            if (b == 0) return false;
            if (b < 9 || (b > 13 && b < 32)) ctrl++;
        }
        return ctrl < n / 50 + 1;
    }

    private static void openPdf(Context c, Uri uri, String password, Doc d) throws IOException {
        ParcelFileDescriptor fd = Source.openFd(c, uri);
        try {
            d.pages = new PdfSource(fd, password);
        } catch (SecurityException e) {
            Text.close(fd);
            if (!PdfSource.canOpenEncrypted()) throw new Failure(R.string.err_pdf_password);
            throw new PasswordNeeded();
        } catch (IOException e) {
            Text.close(fd);
            throw new Failure(R.string.err_corrupt);
        }
        d.format = "PDF";
    }

    /** Returns true if the archive stays open (owned by the document). */
    private static boolean openZip(ZipArchive zip, Doc d) throws IOException {
        Archive.Entry mt = zip.find("mimetype");
        String mime = "";
        if (mt != null && mt.size < 256) mime = new String(zip.read(mt, 256), StandardCharsets.US_ASCII).trim();
        if (mime.equals("application/epub+zip") || zip.find("META-INF/container.xml") != null) {
            EpubBook epub = new EpubBook(zip);
            List<Archive.Entry> images = epub.imagePages();
            d.format = "EPUB";
            if (images != null) {
                d.title = epub.title != null ? epub.title : d.title;
                d.author = epub.author;
                d.pages = new ImageSource(new ImageSource.ArchivePages(zip, images));
                d.comic = true;
                d.rtl = epub.rtl;
            } else {
                d.book = epub;
            }
            return true;
        }
        if (zip.find("word/document.xml") != null) {
            d.book = OfficeBooks.docx(zip, d.name);
            d.format = "DOCX";
            return true;
        }
        if (mime.startsWith("application/vnd.oasis.opendocument.text") || (zip.find("content.xml") != null && zip.find("META-INF/manifest.xml") != null)) {
            d.book = OfficeBooks.odt(zip, d.name);
            d.format = "ODT";
            return true;
        }
        Archive.Entry fb2 = null;
        int images = 0;
        for (Archive.Entry e : zip.entries()) {
            if (Text.isImageName(e.name)) images++;
            else if (Text.ext(e.name).equals("fb2") && fb2 == null) fb2 = e;
        }
        if (fb2 != null && images == 0) {
            try (InputStream in = zip.open(fb2)) {
                d.book = Fb2Book.parse(in);
            }
            d.format = "FB2";
            zip.close();
            return false;
        }
        openComic(zip, d, "CBZ");
        return true;
    }

    private static void openComic(Archive a, Doc d, String format) throws IOException {
        List<Archive.Entry> pages = new ArrayList<>();
        Archive.Entry info = null;
        for (Archive.Entry e : a.entries()) {
            String lower = e.name.toLowerCase(Locale.ROOT);
            if (e.isDirectory() || lower.startsWith("__macosx/") || lower.contains("/.") || lower.startsWith(".")) continue;
            if (Text.isImageName(e.name)) pages.add(e);
            else if (lower.endsWith("comicinfo.xml")) info = e;
        }
        if (pages.isEmpty()) {
            Text.close(a);
            throw new Failure(R.string.err_no_images);
        }
        Collections.sort(pages, (x, y) -> Natural.INSTANCE.compare(x.name, y.name));
        if (info != null) comicInfo(a, info, d);
        d.pages = new ImageSource(new ImageSource.ArchivePages(a, pages));
        d.comic = true;
        d.format = format;
    }

    private static void comicInfo(Archive a, Archive.Entry e, Doc d) {
        try {
            XmlPullParser p = Xml.parser();
            p.setInput(new ByteArrayInputStream(a.read(e, 1 << 20)), null);
            String title = null, series = null, number = null, writer = null;
            for (int t = p.getEventType(); t != XmlPullParser.END_DOCUMENT; t = p.next()) {
                if (t != XmlPullParser.START_TAG) continue;
                switch (Xml.name(p)) {
                    case "title": title = Xml.text(p); break;
                    case "series": series = Xml.text(p); break;
                    case "number": number = Xml.text(p); break;
                    case "writer": writer = Xml.text(p); break;
                    case "manga": d.rtl = Xml.text(p).equalsIgnoreCase("YesAndRightToLeft"); break;
                    default: break;
                }
            }
            if (series != null && !series.isEmpty()) {
                d.title = series + (number != null && !number.isEmpty() ? " #" + number : "");
                if (title != null && !title.isEmpty()) d.title += " - " + title;
            } else if (title != null && !title.isEmpty()) d.title = title;
            if (writer != null && !writer.isEmpty()) d.author = writer;
        } catch (Exception ignored) {}
    }
}
