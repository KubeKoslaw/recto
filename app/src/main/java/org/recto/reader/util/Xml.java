package org.recto.reader.util;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;

/** XmlPullParser helpers (works with the platform kXML parser and with kxml2 in unit tests). */
public final class Xml {
    private Xml() {}

    private static XmlPullParserFactory factory;

    public static synchronized XmlPullParser parser() throws XmlPullParserException {
        if (factory == null) {
            factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
        }
        XmlPullParser p = factory.newPullParser();
        try {
            // Tolerate HTML entities and minor errors in real-world books.
            p.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true);
        } catch (Exception ignored) {}
        return p;
    }

    /** Local name of the current tag, lower-cased, without namespace prefix. */
    public static String name(XmlPullParser p) {
        String n = p.getName();
        if (n == null) return "";
        int c = n.indexOf(':');
        return (c >= 0 ? n.substring(c + 1) : n).toLowerCase(java.util.Locale.ROOT);
    }

    /** Attribute by local name, ignoring namespace / prefix. */
    public static String attr(XmlPullParser p, String local) {
        for (int i = 0, n = p.getAttributeCount(); i < n; i++) {
            String a = p.getAttributeName(i);
            int c = a.indexOf(':');
            if ((c >= 0 ? a.substring(c + 1) : a).equalsIgnoreCase(local)) return p.getAttributeValue(i);
        }
        return null;
    }

    /** Like {@link #text} but keeps whitespace exactly (for DOCX runs). */
    public static String text0(XmlPullParser p) throws XmlPullParserException, IOException {
        StringBuilder sb = new StringBuilder();
        int depth = 1;
        while (depth > 0) {
            int t = p.next();
            if (t == XmlPullParser.START_TAG) depth++;
            else if (t == XmlPullParser.END_TAG) depth--;
            else if (t == XmlPullParser.TEXT) sb.append(p.getText());
            else if (t == XmlPullParser.END_DOCUMENT) break;
        }
        return sb.toString();
    }

    /** Concatenated text inside the current element; leaves the parser on its END_TAG. */
    public static String text(XmlPullParser p) throws XmlPullParserException, IOException {
        StringBuilder sb = new StringBuilder();
        int depth = 1;
        while (depth > 0) {
            int t = p.next();
            if (t == XmlPullParser.START_TAG) depth++;
            else if (t == XmlPullParser.END_TAG) depth--;
            else if (t == XmlPullParser.TEXT || t == XmlPullParser.ENTITY_REF) sb.append(p.getText());
            else if (t == XmlPullParser.END_DOCUMENT) break;
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }
}
