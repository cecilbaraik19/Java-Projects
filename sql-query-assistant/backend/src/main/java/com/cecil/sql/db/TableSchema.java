package com.cecil.sql.db;

import com.cecil.sql.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Describes a table: its columns, primary key, foreign keys (links to other tables),
 * and words people use for it ("teacher" means the faculty table).
 */
public final class TableSchema implements Json.JsonWritable {

    /** "this.column points to refTable.refColumn" – used to build JOINs automatically. */
    public record ForeignKey(String column, String refTable, String refColumn) {}

    public final String name;
    public final String alias;          // short name used in generated SQL, e.g. s for students
    public final String singular;       // "student"
    public final String plural;         // "students"
    public final String description;
    public final List<String> synonyms; // "pupil", "learner"
    public final List<Column> columns;
    public final String primaryKey;
    public final List<ForeignKey> foreignKeys;
    public final List<String> displayColumns; // shown when someone says "list the students"
    public final String nameColumn;           // the column that names a row, e.g. students.name

    public TableSchema(String name, String alias, String singular, String plural, String description,
                       List<String> synonyms, List<Column> columns, String primaryKey,
                       List<ForeignKey> foreignKeys, List<String> displayColumns, String nameColumn) {
        this.name = name;
        this.alias = alias;
        this.singular = singular;
        this.plural = plural;
        this.description = description == null ? "" : description;
        this.synonyms = List.copyOf(synonyms);
        this.columns = List.copyOf(columns);
        this.primaryKey = primaryKey;
        this.foreignKeys = List.copyOf(foreignKeys);
        this.displayColumns = List.copyOf(displayColumns);
        this.nameColumn = nameColumn;
    }

    public int indexOf(String column) {
        for (int i = 0; i < columns.size(); i++) if (columns.get(i).name.equalsIgnoreCase(column)) return i;
        return -1;
    }

    public Column column(String name) {
        int i = indexOf(name);
        return i < 0 ? null : columns.get(i);
    }

    /** Words that mean this table, lower case. */
    public List<String> allNames() {
        List<String> l = new ArrayList<>();
        l.add(name.replace('_', ' ').toLowerCase());
        l.add(singular.toLowerCase());
        l.add(plural.toLowerCase());
        for (String s : synonyms) l.add(s.toLowerCase());
        return l;
    }

    @Override public Object toJson() {
        List<Object> fks = new ArrayList<>();
        for (ForeignKey f : foreignKeys) fks.add(Json.map("column", f.column, "refTable", f.refTable, "refColumn", f.refColumn));
        return Json.map("name", name, "alias", alias, "singular", singular, "plural", plural,
                "description", description, "synonyms", synonyms, "columns", columns,
                "primaryKey", primaryKey, "foreignKeys", fks, "displayColumns", displayColumns,
                "nameColumn", nameColumn);
    }

    public static TableSchema fromJson(Map<String, Object> m) {
        List<Column> cols = new ArrayList<>();
        for (Object o : Json.list(m, "columns")) cols.add(Column.fromJson(Json.obj(o)));
        List<ForeignKey> fks = new ArrayList<>();
        for (Object o : Json.list(m, "foreignKeys")) {
            Map<String, Object> f = Json.obj(o);
            fks.add(new ForeignKey(Json.str(f, "column"), Json.str(f, "refTable"), Json.str(f, "refColumn")));
        }
        return new TableSchema(Json.str(m, "name"), Json.str(m, "alias"), Json.str(m, "singular"),
                Json.str(m, "plural"), Json.str(m, "description"), strings(Json.list(m, "synonyms")), cols,
                Json.str(m, "primaryKey"), fks, strings(Json.list(m, "displayColumns")), Json.str(m, "nameColumn"));
    }

    private static List<String> strings(List<Object> l) {
        List<String> out = new ArrayList<>();
        for (Object o : l) out.add(String.valueOf(o));
        return out;
    }
}
