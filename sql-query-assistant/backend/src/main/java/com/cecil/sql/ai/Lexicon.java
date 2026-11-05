package com.cecil.sql.ai;

import com.cecil.sql.db.*;

import java.util.*;

/**
 * Step 2 of understanding English: the "schema dictionary" of one database.
 * It knows every phrase that can point to something in the database:
 *   TABLE  – "students", "pupil", "teacher" (= faculty)
 *   COLUMN – "cgpa", "gpa", "salary", "hometown" (= city)
 *   VALUE  – "Ranchi" (a city), "Computer Science" or "CSE" (a department), "girls" (= gender Female)
 * Values are read from the data itself, so a newly uploaded table is understood without extra work.
 */
public final class Lexicon {

    public enum Kind { TABLE, COLUMN, VALUE, NAME_START }

    /** One phrase and what it means. */
    public record Entry(Kind kind, TableSchema table, Column column, Object value, List<String> stems,
                        boolean requireUpper, String phrase) {
        public String key() { return String.join(" ", stems); }
        public String describe() {
            return switch (kind) {
                case TABLE -> "table " + table.name;
                case COLUMN -> table.name + "." + column.name;
                case VALUE -> table.name + "." + column.name + " = '" + value + "'";
                case NAME_START -> table.name + "." + column.name + " starts with '" + value + "'";
            };
        }
    }

    public static final int MAX_WORDS = 6;
    private static final int MAX_DISTINCT_VALUES = 600;

    private final Map<String, List<Entry>> byKey = new HashMap<>();
    public final Database db;

    public Lexicon(Database db) {
        this.db = db;
        for (Table t : db.tables()) {
            TableSchema s = t.schema;
            for (String n : s.allNames()) add(new Entry(Kind.TABLE, s, null, null, NlTokenizer.stems(n), false, n));
            for (int ci = 0; ci < s.columns.size(); ci++) {
                Column c = s.columns.get(ci);
                for (String n : c.allNames()) add(new Entry(Kind.COLUMN, s, c, null, NlTokenizer.stems(n), false, n));
                if (c.type != ColumnType.TEXT) continue;
                // values found in the data
                Set<String> distinct = new LinkedHashSet<>();
                for (Object[] r : t.rows()) {
                    if (r[ci] != null) distinct.add(String.valueOf(r[ci]));
                    if (distinct.size() > MAX_DISTINCT_VALUES) break;
                }
                if (c.sensitive || distinct.size() > MAX_DISTINCT_VALUES) {
                    distinct.clear();   // private or free text (emails, phone numbers): not used as words
                }
                boolean isName = c.name.equalsIgnoreCase(s.nameColumn);
                for (String v : distinct) {
                    if (v.isBlank() || v.length() > 60) continue;
                    List<String> st = NlTokenizer.stems(v);
                    if (st.isEmpty()) continue;
                    boolean shortCode = v.length() <= 3;
                    add(new Entry(Kind.VALUE, s, c, v, st, shortCode, v));
                    if (v.startsWith("Dr. ") && v.length() > 4) add(new Entry(Kind.VALUE, s, c, v, NlTokenizer.stems(v.substring(4)), false, v));
                    if (isName && st.size() >= 2) {
                        String first = v.startsWith("Dr. ") ? v.substring(4).split(" ")[0] : v.split(" ")[0];
                        add(new Entry(Kind.NAME_START, s, c, first, List.of(NlTokenizer.stem(first.toLowerCase())), true, first));
                    }
                }
                // other words for stored values ("girls" = Female)
                for (Map.Entry<String, List<String>> e : c.valueWords.entrySet())
                    for (String w : e.getValue()) add(new Entry(Kind.VALUE, s, c, e.getKey(), NlTokenizer.stems(w), false, w));
            }
        }
    }

    private void add(Entry e) {
        if (e.stems.isEmpty() || e.stems.size() > MAX_WORDS) return;
        List<Entry> l = byKey.computeIfAbsent(e.key(), k -> new ArrayList<>());
        for (Entry x : l)   // no exact duplicates
            if (x.kind == e.kind && x.table == e.table && x.column == e.column && Objects.equals(x.value, e.value)) return;
        if (e.kind == Kind.NAME_START) {   // same first name in many rows: keep one entry per column
            for (Entry x : l) if (x.kind == Kind.NAME_START && x.column == e.column) return;
        }
        l.add(e);
    }

    public List<Entry> lookup(String key) { return byKey.getOrDefault(key, List.of()); }

    /** The first non-private DECIMAL column, else the first useful INT column: what "best" or "top" ranks by. */
    public static Column defaultMeasure(TableSchema t) {
        for (Column c : t.columns) if (c.type == ColumnType.DECIMAL && !c.sensitive) return c;
        for (Column c : t.columns) if (c.type == ColumnType.INT && !c.sensitive && !isKey(t, c)) return c;
        return null;
    }

    public static Column dateColumn(TableSchema t) {
        for (Column c : t.columns) if (c.type == ColumnType.DATE) return c;
        return null;
    }

    public static Column yearColumn(TableSchema t) {
        for (Column c : t.columns) if (c.type == ColumnType.INT && c.name.toLowerCase().contains("year")) return c;
        return null;
    }

    public static boolean isKey(TableSchema t, Column c) {
        if (c.name.equalsIgnoreCase(t.primaryKey)) return true;
        for (TableSchema.ForeignKey f : t.foreignKeys) if (f.column().equalsIgnoreCase(c.name)) return true;
        return c.name.toLowerCase().endsWith("_id");
    }
}
