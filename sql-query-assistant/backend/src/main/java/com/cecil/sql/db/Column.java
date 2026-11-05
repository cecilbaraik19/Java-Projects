package com.cecil.sql.db;

import com.cecil.sql.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One column of a table, plus the extra facts the English-to-SQL engine needs:
 * other words people use for it (synonyms), whether it is private (sensitive) and its unit.
 */
public final class Column implements Json.JsonWritable {
    public final String name;
    public final ColumnType type;
    public final String label;            // shown to people, e.g. "CGPA"
    public final List<String> synonyms;   // e.g. "gpa", "grade point"
    public final boolean sensitive;       // hidden from Viewers (salary, email, phone)
    public final String unit;             // "rupees", "percent" or null
    /** Other words for a stored value, e.g. "Female" can be said as "girls", "women". */
    public final Map<String, List<String>> valueWords;

    public Column(String name, ColumnType type, String label, List<String> synonyms, boolean sensitive, String unit) {
        this(name, type, label, synonyms, sensitive, unit, Map.of());
    }

    public Column(String name, ColumnType type, String label, List<String> synonyms, boolean sensitive, String unit,
                  Map<String, List<String>> valueWords) {
        this.valueWords = valueWords == null ? Map.of() : Map.copyOf(valueWords);
        this.name = name;
        this.type = type;
        this.label = label == null ? name.replace('_', ' ') : label;
        this.synonyms = synonyms == null ? List.of() : List.copyOf(synonyms);
        this.sensitive = sensitive;
        this.unit = unit;
    }

    public static Column of(String name, ColumnType type, String label, String... synonyms) {
        return new Column(name, type, label, List.of(synonyms), false, null);
    }

    public Column privateColumn() { return new Column(name, type, label, synonyms, true, unit, valueWords); }
    public Column withUnit(String u) { return new Column(name, type, label, synonyms, sensitive, u, valueWords); }
    public Column withValueWords(Map<String, List<String>> w) { return new Column(name, type, label, synonyms, sensitive, unit, w); }

    /** All the words that can mean this column (name, label and synonyms), lower case. */
    public List<String> allNames() {
        List<String> l = new ArrayList<>();
        l.add(name.replace('_', ' ').toLowerCase());
        l.add(label.toLowerCase());
        for (String s : synonyms) l.add(s.toLowerCase());
        return l;
    }

    @Override public Object toJson() {
        return Json.map("name", name, "type", type, "label", label, "synonyms", synonyms,
                "sensitive", sensitive, "unit", unit, "valueWords", valueWords);
    }

    public static Column fromJson(Map<String, Object> m) {
        List<String> syn = new ArrayList<>();
        for (Object o : Json.list(m, "synonyms")) syn.add(String.valueOf(o));
        Map<String, List<String>> vw = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : Json.obj(m.get("valueWords")).entrySet()) {
            List<String> words = new ArrayList<>();
            if (e.getValue() instanceof List<?> l) for (Object o : l) words.add(String.valueOf(o));
            vw.put(e.getKey(), words);
        }
        return new Column(Json.str(m, "name"), ColumnType.valueOf(Json.str(m, "type", "TEXT")),
                Json.str(m, "label"), syn, Json.bool(m, "sensitive", false), Json.str(m, "unit"), vw);
    }

    @Override public String toString() { return name + " " + type; }
}
