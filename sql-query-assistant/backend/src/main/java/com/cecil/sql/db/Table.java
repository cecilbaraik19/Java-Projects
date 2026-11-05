package com.cecil.sql.db;

import com.cecil.sql.util.Csv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A table = its schema + its rows. Each row is an Object[] in column order (Long, Double, String or null). */
public final class Table {
    public final TableSchema schema;
    private final List<Object[]> rows;

    public Table(TableSchema schema, List<Object[]> rows) {
        this.schema = schema;
        this.rows = new ArrayList<>(rows);
    }

    public List<Object[]> rows() { return Collections.unmodifiableList(rows); }
    public int size() { return rows.size(); }

    /** Reads the CSV text for this table. The header must contain the schema's column names. */
    public static Table fromCsv(TableSchema schema, String csv) {
        List<List<String>> lines = Csv.parse(csv);
        List<Object[]> out = new ArrayList<>();
        if (lines.isEmpty()) return new Table(schema, out);
        List<String> header = lines.get(0);
        int[] map = new int[schema.columns.size()];
        for (int c = 0; c < map.length; c++) {
            map[c] = -1;
            for (int h = 0; h < header.size(); h++)
                if (header.get(h).trim().equalsIgnoreCase(schema.columns.get(c).name)) map[c] = h;
        }
        for (int r = 1; r < lines.size(); r++) {
            List<String> line = lines.get(r);
            Object[] row = new Object[map.length];
            for (int c = 0; c < map.length; c++) {
                String raw = map[c] >= 0 && map[c] < line.size() ? line.get(map[c]) : null;
                try {
                    row[c] = schema.columns.get(c).type.parse(raw);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Row " + (r + 1) + ", column " + schema.columns.get(c).name + ": " + e.getMessage());
                }
            }
            out.add(row);
        }
        return new Table(schema, out);
    }

    public String toCsv() {
        StringBuilder sb = new StringBuilder();
        List<String> header = new ArrayList<>();
        for (Column c : schema.columns) header.add(c.name);
        sb.append(Csv.line(header));
        for (Object[] r : rows) sb.append(Csv.line(java.util.Arrays.asList(r)));
        return sb.toString();
    }
}
