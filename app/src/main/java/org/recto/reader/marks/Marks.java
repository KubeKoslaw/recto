package org.recto.reader.marks;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Bookmarks for one book: a short label and the same position string the reader already stores. */
public final class Marks {
    /** Enough for a book, small enough to stay in the library file. */
    public static final int MAX = 40;

    public static final class Mark {
        public final String pos;
        public final String label;
        public final long at;

        public Mark(String pos, String label, long at) {
            this.pos = pos;
            this.label = label;
            this.at = at;
        }
    }

    private Marks() {}

    public static List<Mark> read(JSONArray a) {
        List<Mark> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String pos = o.optString("pos", "");
            if (pos.isEmpty()) continue;
            out.add(new Mark(pos, o.optString("label", ""), o.optLong("at")));
        }
        return out;
    }

    public static JSONArray write(List<Mark> marks) {
        JSONArray a = new JSONArray();
        if (marks == null) return a;
        try {
            for (Mark m : marks) {
                JSONObject o = new JSONObject();
                o.put("pos", m.pos);
                o.put("label", m.label);
                o.put("at", m.at);
                a.put(o);
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return a;
    }

    /**
     * Newest first. The same position updates the label and moves to the front instead of
     * duplicating. A blank label uses {@code fallback}, then the position itself.
     */
    public static List<Mark> add(List<Mark> marks, String pos, String label, String fallback, long at) {
        List<Mark> out = new ArrayList<>();
        if (pos == null || pos.isEmpty()) {
            if (marks != null) out.addAll(marks);
            return out;
        }
        String lab = clean(label);
        if (lab.isEmpty()) lab = clean(fallback);
        if (lab.isEmpty()) lab = pos;
        out.add(new Mark(pos, lab, at));
        if (marks != null) {
            for (Mark m : marks) {
                if (pos.equals(m.pos)) continue;
                out.add(m);
                if (out.size() == MAX) break;
            }
        }
        return out;
    }

    public static List<Mark> removeAt(List<Mark> marks, int index) {
        List<Mark> out = new ArrayList<>();
        if (marks == null) return out;
        for (int i = 0; i < marks.size(); i++) if (i != index) out.add(marks.get(i));
        return out;
    }

    static String clean(String s) {
        if (s == null) return "";
        String t = s.trim().replace('\n', ' ').replace('\r', ' ');
        return t.length() > 80 ? t.substring(0, 80) : t;
    }
}
