package org.recto.reader.util;

import java.util.Comparator;

/** "Natural" file-name order: page2 &lt; page10, case-insensitive; "cover*" sorts first in its folder. */
public final class Natural implements Comparator<String> {
    public static final Natural INSTANCE = new Natural();

    private Natural() {}

    @Override public int compare(String a, String b) {
        int da = a.lastIndexOf('/'), db = b.lastIndexOf('/');
        if (da == db && a.regionMatches(0, b, 0, da + 1)) {
            boolean ca = a.regionMatches(true, da + 1, "cover", 0, 5), cb = b.regionMatches(true, db + 1, "cover", 0, 5);
            if (ca != cb) return ca ? -1 : 1;
        }
        return cmp(a, b);
    }

    static boolean digit(char c) { return c >= '0' && c <= '9'; }

    public static int cmp(String a, String b) {
        int i = 0, j = 0, na = a.length(), nb = b.length();
        while (i < na && j < nb) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (digit(ca) && digit(cb)) {
                int si = i, sj = j;
                while (si < na && a.charAt(si) == '0') si++;
                while (sj < nb && b.charAt(sj) == '0') sj++;
                int ei = si, ej = sj;
                while (ei < na && digit(a.charAt(ei))) ei++;
                while (ej < nb && digit(b.charAt(ej))) ej++;
                int la = ei - si, lb = ej - sj;
                if (la != lb) return la - lb;
                for (int k = 0; k < la; k++) {
                    int d = a.charAt(si + k) - b.charAt(sj + k);
                    if (d != 0) return d;
                }
                i = ei;
                j = ej;
            } else {
                if (ca != cb) {
                    int d = Character.toLowerCase(ca) - Character.toLowerCase(cb);
                    if (d != 0) return d;
                }
                i++;
                j++;
            }
        }
        int r = (na - i) - (nb - j);
        return r != 0 ? r : a.compareTo(b);
    }
}
