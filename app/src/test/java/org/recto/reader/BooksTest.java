package org.recto.reader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.recto.reader.flow.EpubBook;
import org.recto.reader.flow.Fb2Book;
import org.recto.reader.flow.FlowBook;
import org.recto.reader.flow.MemBook;
import org.recto.reader.flow.MobiBook;
import org.recto.reader.flow.OfficeBooks;
import org.recto.reader.flow.TextBooks;
import org.recto.reader.io.Archive;
import org.recto.reader.io.BytesSeekable;
import org.recto.reader.io.ZipArchive;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

public class BooksTest {
    // ---- helpers ------------------------------------------------------------------------------

    @Test public void htmlHelpers() {
        assertEquals("OEBPS/Text/ch 1.xhtml", FlowBook.resolve("OEBPS/", "Text/ch%201.xhtml#frag"));
        assertEquals("OEBPS/img/a.png", FlowBook.resolve("OEBPS/Text/", "../img/a.png"));
        assertEquals("frag", FlowBook.fragment("x.html#frag"));
        assertEquals("<div class=\"a\"></div><br/><a id=\"x\"></a><img src=\"i\"/>",
                FlowBook.xhtmlToHtml("<div class=\"a\"/><br/><a id=\"x\"/><img src=\"i\"/>"));
        assertEquals("ą — & <x>", FlowBook.decodeEntities("&#261; &mdash; &amp; &lt;x&gt;"));
        assertEquals("  Hi there ", FlowBook.htmlToText("<head><title>T</title></head><body> Hi <b>there</b> </body>"));
        byte[] cp = "<?xml version=\"1.0\" encoding=\"windows-1251\"?><p>Привет</p>".getBytes(Charset.forName("windows-1251"));
        assertTrue(FlowBook.decodeMarkup(cp).contains("Привет"));
    }

    // ---- EPUB ---------------------------------------------------------------------------------

    static byte[] epub(boolean withNav) throws Exception {
        String opf = "<?xml version='1.0'?><package xmlns='http://www.idpf.org/2007/opf' version='3.0' unique-identifier='uid'>"
                + "<metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:identifier id='uid'>urn:uuid:0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0</dc:identifier>"
                + "<dc:title>My Book</dc:title><dc:creator>A. Writer</dc:creator><dc:language>en</dc:language><meta name='cover' content='cov'/></metadata>"
                + "<manifest><item id='c1' href='Text/c1.xhtml' media-type='application/xhtml+xml'/>"
                + "<item id='c2' href='Text/c%202.xhtml' media-type='application/xhtml+xml'/>"
                + "<item id='note' href='Text/note.xhtml' media-type='application/xhtml+xml'/>"
                + (withNav ? "<item id='nav' href='nav.xhtml' properties='nav' media-type='application/xhtml+xml'/>" : "")
                + "<item id='ncx' href='toc.ncx' media-type='application/x-dtbncx+xml'/>"
                + "<item id='cov' href='img/cover.jpg' media-type='image/jpeg'/>"
                + "<item id='f' href='fonts/f.otf' media-type='font/otf'/></manifest>"
                + "<spine toc='ncx'><itemref idref='c1'/><itemref idref='note' linear='no'/><itemref idref='c2'/></spine></package>";
        String nav = "<html xmlns='http://www.w3.org/1999/xhtml' xmlns:epub='http://www.idpf.org/2007/ops'><body>"
                + "<nav epub:type='landmarks'><ol><li><a href='Text/c2.xhtml'>Skip</a></li></ol></nav>"
                + "<nav epub:type='toc'><ol><li><a href='Text/c1.xhtml'>One</a><ol><li><a href='Text/c1.xhtml#s2'>One &amp; a half</a></li></ol></li>"
                + "<li><span>Part</span><ol><li><a href='Text/c%202.xhtml'>Two</a></li></ol></li></ol></nav></body></html>";
        String ncx = "<ncx xmlns='http://www.daisy.org/z3986/2005/ncx/'><navMap><navPoint><navLabel><text>N1</text></navLabel><content src='Text/c1.xhtml'/>"
                + "<navPoint><navLabel><text>N1.1</text></navLabel><content src='Text/c1.xhtml#s2'/></navPoint></navPoint>"
                + "<navPoint><navLabel><text>N2</text></navLabel><content src='Text/c%202.xhtml'/></navPoint></navMap></ncx>";
        byte[] font = new byte[2000];
        for (int i = 0; i < font.length; i++) font[i] = (byte) i;
        byte[] obf = font.clone();
        byte[] key = new byte[16];
        String hex = "0f1e2d3c4b5a69788796a5b4c3d2e1f0";
        for (int i = 0; i < 16; i++) key[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        for (int i = 0; i < 1024; i++) obf[i] ^= key[i % 16];
        String enc = "<encryption xmlns='urn:oasis:names:tc:opendocument:xmlns:container' xmlns:enc='http://www.w3.org/2001/04/xmlenc#'>"
                + "<enc:EncryptedData><enc:EncryptionMethod Algorithm='http://ns.adobe.com/pdf/enc#RC'/><enc:CipherData><enc:CipherReference URI='OEBPS/fonts/f.otf'/></enc:CipherData></enc:EncryptedData></encryption>";
        return ArchiveTest.zip("mimetype", "application/epub+zip",
                "META-INF/container.xml", "<container><rootfiles><rootfile full-path='OEBPS/content.opf'/></rootfiles></container>",
                "META-INF/encryption.xml", enc,
                "OEBPS/content.opf", opf, "OEBPS/nav.xhtml", nav, "OEBPS/toc.ncx", ncx,
                "OEBPS/Text/c1.xhtml", "<?xml version='1.0' encoding='utf-8'?><html xmlns='http://www.w3.org/1999/xhtml'><head><title>c1</title></head><body><p>First <a href='note.xhtml#n'>note</a></p><div id='s2'/><p>Second</p></body></html>",
                "OEBPS/Text/c 2.xhtml", "<html><body><p>Chapter two</p></body></html>",
                "OEBPS/Text/note.xhtml", "<html><body><p id='n'>A note</p></body></html>",
                "OEBPS/img/cover.jpg", new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1, 2, 3},
                "OEBPS/fonts/f.otf", obf);
    }

    @Test public void epub3() throws Exception {
        EpubBook b = new EpubBook(new ZipArchive(new BytesSeekable(epub(true))));
        assertEquals("My Book", b.title);
        assertEquals("A. Writer", b.author);
        assertEquals("en", b.language);
        assertEquals(3, b.chapters.size());
        assertEquals("OEBPS/Text/c1.xhtml", b.chapters.get(0).path);
        assertEquals("OEBPS/Text/c 2.xhtml", b.chapters.get(1).path);
        assertEquals("OEBPS/Text/note.xhtml", b.chapters.get(2).path); // non-linear last
        assertEquals(4, b.toc.size());
        assertEquals("One & a half", b.toc.get(1).title);
        assertEquals(1, b.toc.get(1).depth);
        assertEquals("s2", b.toc.get(1).anchor);
        assertEquals(-1, b.toc.get(2).chapter); // heading without link
        assertEquals(1, b.toc.get(3).chapter);
        String html = b.chapterHtml(0);
        assertTrue(html, html.contains("<div id='s2'></div>"));
        assertEquals(6, b.cover().length);
        FlowBook.Resource f = b.resource("OEBPS/fonts/f.otf");
        byte[] font = f.data.readAllBytes();
        for (int i = 0; i < font.length; i++) assertEquals((byte) i, font[i]);
        assertNull(b.imagePages());
    }

    @Test public void epubNcxFallback() throws Exception {
        EpubBook b = new EpubBook(new ZipArchive(new BytesSeekable(epub(false))));
        assertEquals(3, b.toc.size());
        assertEquals("N1.1", b.toc.get(1).title);
        assertEquals("s2", b.toc.get(1).anchor);
        assertEquals(1, b.toc.get(2).chapter);
    }

    // ---- FB2 ----------------------------------------------------------------------------------

    static final String FB2 = "<?xml version='1.0' encoding='utf-8'?><FictionBook xmlns='http://www.gribuser.ru/xml/fictionbook/2.0' xmlns:l='http://www.w3.org/1999/xlink'>"
            + "<description><title-info><author><first-name>Jan</first-name><last-name>Nowak</last-name></author><book-title>Książka</book-title><lang>pl</lang>"
            + "<coverpage><image l:href='#c.png'/></coverpage></title-info></description>"
            + "<body><section><title><p>Jeden</p></title><p>Tekst <emphasis>ważny</emphasis> <strong>bardzo</strong><a l:href='#n1' type='note'>1</a></p>"
            + "<section><title><p>Pod</p></title><p>x</p></section></section>"
            + "<section><title><p>Dwa</p></title><p>y &amp; z</p></section></body>"
            + "<body name='notes'><section id='n1'><p>Przypis</p></section></body>"
            + "<binary id='c.png' content-type='image/png'>iVBORw0KGgo=</binary></FictionBook>";

    @Test public void fb2() throws Exception {
        MemBook b = Fb2Book.parse(new ByteArrayInputStream(FB2.getBytes(StandardCharsets.UTF_8)));
        assertEquals("Książka", b.title);
        assertEquals("Jan Nowak", b.author);
        assertEquals(3, b.chapters.size());
        assertEquals("Jeden", b.toc.get(0).title);
        assertEquals("Pod", b.toc.get(1).title);
        assertEquals(1, b.toc.get(1).depth);
        assertNotNull(b.toc.get(1).anchor);
        String c0 = b.chapterHtml(0);
        assertTrue(c0, c0.contains("<em>ważny</em> <strong>bardzo</strong>")); // whitespace between inline elements kept
        assertTrue(c0, c0.contains("href=\"c2.html#n1\""));
        assertTrue(b.chapterHtml(1).contains("y &amp; z"));
        assertNotNull(b.resource("img/c.png"));
        assertEquals((byte) 0x89, b.cover()[0]);
    }

    // ---- text formats -------------------------------------------------------------------------

    @Test public void txtParagraphsAndChapters() throws Exception {
        StringBuilder sb = new StringBuilder("Title line\r\n\r\nChapter 1\r\n\r\n");
        for (int i = 0; i < 50; i++) sb.append("A hard wrapped line ").append(i).append("\r\ncontinues here.\r\n\r\n");
        sb.append("Chapter 2\r\n\r\nLast words.\r\n");
        MemBook b = TextBooks.txt(sb.toString().getBytes(StandardCharsets.UTF_8), "book.txt");
        assertEquals(2, b.toc.size());
        assertEquals("Chapter 2", b.toc.get(1).title);
        String html = b.chapterHtml(b.toc.get(0).chapter);
        assertTrue(html, html.contains("<p>A hard wrapped line 3 continues here.</p>"));
    }

    @Test public void txtLegacyEncoding() throws Exception {
        Locale old = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("pl", "PL"));
            MemBook b = TextBooks.txt("Zażółć gęślą jaźń".getBytes("windows-1250"), "x.txt");
            assertTrue(b.chapterHtml(0).contains("Zażółć gęślą jaźń"));
        } finally {
            Locale.setDefault(old);
        }
    }

    @Test public void txtEncodingFromContent() throws Exception {
        Locale old = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            String pl = "Zażółć gęślą jaźń. Pchnąć w tę łódź jeża lub ośm skrzyń fig.";
            assertTrue(TextBooks.txt(pl.getBytes("windows-1250"), "a.txt").chapterHtml(0).contains(pl));
            String ru = "Война и мир. Генуя и Лукка стали не больше, как поместьями фамилии Бонапарте.";
            assertTrue(TextBooks.txt(ru.getBytes("windows-1251"), "b.txt").chapterHtml(0).contains(ru));
            String fr = "Le cœur a ses raisons que la raison ne connaît point. Où êtes-vous, déjà ?";
            assertTrue(TextBooks.txt(fr.getBytes("windows-1252"), "c.txt").chapterHtml(0).contains(fr));
        } finally {
            Locale.setDefault(old);
        }
    }

    @Test public void markdown() throws Exception {
        String md = "# Title\n\nSome *em* and **strong** and `a<b`.\n\n## Sub\n\n- one\n- two\n\n```\nx < y\n```\n";
        MemBook b = TextBooks.markdown(md.getBytes(StandardCharsets.UTF_8), "n.md");
        String h = b.chapterHtml(0);
        assertEquals("Title", b.title);
        assertTrue(h, h.contains("<em>em</em>") && h.contains("<strong>strong</strong>") && h.contains("<code>a&lt;b</code>"));
        assertTrue(h, h.contains("<ul><li>one</li><li>two</li></ul>"));
        assertTrue(h, h.contains("<pre><code>x &lt; y\n</code></pre>"));
        assertEquals(2, b.toc.size());
    }

    // ---- MOBI ---------------------------------------------------------------------------------

    @Test public void palmdocLiterals() throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        // "ab" literal, 0xC0^0x80 = space+'@', a back-reference (dist 2, len 3) and a 2-byte raw run.
        byte[] in = {'a', 'b', (byte) 0xc0, (byte) 0x80, 0x10, 2, (byte) 0x90, (byte) 0xff};
        java.lang.reflect.Method m = MobiBook.class.getDeclaredMethod("palmdoc", byte[].class, int.class, java.io.ByteArrayOutputStream.class);
        m.setAccessible(true);
        m.invoke(null, in, in.length, out);
        assertEquals("ab @ @ ", out.toString("ISO-8859-1").substring(0, 7));
    }

    // ---- real files (RECTO_SAMPLES) ---------------------------------------------------------------

    static FlowBook mobi(String name) throws Exception {
        return MobiBook.parse(ArchiveTest.open(ArchiveTest.sample(name)), name);
    }

    @Test public void kf8Sample() throws Exception {
        FlowBook b = mobi("alice.azw3");
        assertTrue(b.title, b.title.startsWith("Alice"));
        assertTrue(b.chapters.size() > 3);
        assertTrue("toc " + b.toc.size(), b.toc.size() >= 10);
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < b.chapters.size(); i++) all.append(b.chapterText(i));
        assertTrue(all.indexOf("Down the Rabbit-Hole") >= 0);
        assertTrue(all.indexOf("rabbit") >= 0);
        assertFalse(all.indexOf("kindle:") >= 0);
        for (FlowBook.TocEntry e : b.toc) assertTrue(e.title, e.chapter >= 0 && e.chapter < b.chapters.size());
        String h = b.chapterHtml(b.toc.get(b.toc.size() / 2).chapter);
        assertTrue(h.contains("id=\"" + b.toc.get(b.toc.size() / 2).anchor + "\""));
        assertNotNull(b.cover());
    }

    @Test public void mobiJointSample() throws Exception {
        FlowBook b = mobi("alice.mobi");
        assertTrue(b.title.startsWith("Alice"));
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < b.chapters.size(); i++) all.append(b.chapterText(i));
        assertTrue(all.indexOf("Down the Rabbit-Hole") >= 0);
        assertTrue(b.toc.size() >= 10);
    }

    @Test public void bigKf8Sample() throws Exception {
        FlowBook b = mobi("pride.azw3");
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < b.chapters.size(); i++) all.append(b.chapterText(i));
        int at = all.indexOf("truth universally acknowledged");
        assertTrue("chapters " + b.chapters.size() + " text " + all.length() + ": " + all.substring(0, Math.min(400, all.length())), at >= 0);
        int imgs = 0;
        for (int i = 1; i < 400; i++) if (b.resource("img/" + i) != null) imgs++;
        assertTrue("images " + imgs, imgs > 10);
        // The cover is an SVG flow: its picture must be inlined to display inside <img>.
        String cover = b.chapterHtml(0);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("src=\"(flow/\\d+)\"").matcher(cover);
        assertTrue(cover, m.find());
        String svg = new String(b.resource(m.group(1)).data.readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(svg.substring(0, Math.min(300, svg.length())), svg.contains("data:image/"));
        assertEquals("QUJD", org.recto.reader.util.Text.base64Encode("ABC".getBytes()));
        assertEquals("QUI=", org.recto.reader.util.Text.base64Encode("AB".getBytes()));
    }

    @Test public void epubSamples() throws Exception {
        for (String n : new String[]{"alice.epub", "war_and_peace.epub"}) {
            EpubBook b = new EpubBook(new ZipArchive(ArchiveTest.open(ArchiveTest.sample(n))));
            assertNotNull(n, b.title);
            assertTrue(n, b.chapters.size() > 3);
            assertTrue(n + " toc", b.toc.size() > 3);
            for (int i = 0; i < b.chapters.size(); i++) assertFalse(b.chapterHtml(i).isEmpty());
        }
    }

    @Test public void generatedSamples() throws Exception {
        MemBook fb2 = Fb2Book.parse(new FileInputStream(ArchiveTest.sample("book.fb2")));
        assertEquals("Zażółć gęślą jaźń", fb2.title);
        MemBook cyr = Fb2Book.parse(new FileInputStream(ArchiveTest.sample("cyr.fb2")));
        assertEquals("Война и мир", cyr.title);
        assertTrue(cyr.chapterHtml(0).contains("Генуя"));

        Archive docx = new ZipArchive(ArchiveTest.open(ArchiveTest.sample("doc.docx")));
        MemBook d = OfficeBooks.docx(docx, "doc.docx");
        assertEquals("Sample DOCX", d.title);
        String h = d.chapterHtml(0);
        assertTrue(h, h.contains("<b>bold</b>") && h.contains("<i>italic</i>") && h.contains("href=\"https://example.org/\""));
        assertTrue(h, h.contains("<table>") && h.contains("word/media/image1.png") && h.contains("<li>Bullet two</li>"));
        assertTrue(h, h.contains("text-align:center"));
        assertEquals(4, d.toc.size());
        assertNotNull(d.resource("word/media/image1.png"));

        MemBook o = OfficeBooks.odt(new ZipArchive(ArchiveTest.open(ArchiveTest.sample("doc.odt"))), "doc.odt");
        String oh = o.chapterHtml(0);
        assertEquals("Sample ODT", o.title);
        assertTrue(oh, oh.contains("<b>bold</b>") && oh.contains("<i>italic</i>") && oh.contains("&#160;&#160;&#160;spaces"));
        assertTrue(oh, oh.contains("Pictures/pic.png") && oh.contains("<td>") && oh.contains("text-align:center"));
        assertEquals(2, o.toc.size());

        EpubBook fxl = new EpubBook(new ZipArchive(ArchiveTest.open(ArchiveTest.sample("fxl-manga.epub"))));
        assertTrue(fxl.fixedLayout);
        assertTrue(fxl.rtl);
        List<Archive.Entry> pages = fxl.imagePages();
        assertNotNull(pages);
        assertEquals(5, pages.size());
        assertEquals("OEBPS/images/1.jpg", pages.get(0).name);

        MemBook md = TextBooks.markdown(Files.readAllBytes(ArchiveTest.sample("notes.md").toPath()), "notes.md");
        assertTrue(md.chapterHtml(0).contains("<blockquote>"));
        File txt = ArchiveTest.sample("alice.txt");
        MemBook t = TextBooks.txt(Files.readAllBytes(txt.toPath()), txt.getName());
        assertTrue("txt toc " + t.toc.size(), t.toc.size() >= 12);
    }
}
