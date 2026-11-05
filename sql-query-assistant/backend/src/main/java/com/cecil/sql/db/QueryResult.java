package com.cecil.sql.db;

import com.cecil.sql.util.Json;

import java.util.ArrayList;
import java.util.List;

/** What a query gives back: column headings, rows, and a few numbers about the run. */
public final class QueryResult implements Json.JsonWritable {
    public final List<String> columns;
    public final List<Boolean> numeric;     // true = right-align and allow charts
    public final List<List<Object>> rows;
    public final int totalRows;             // rows before the display cap
    public final boolean truncated;
    public final long rowsScanned;          // how many rows the engine looked at (joins included)
    public long millis;

    public QueryResult(List<String> columns, List<List<Object>> rows, int totalRows, boolean truncated, long rowsScanned) {
        this.columns = columns;
        this.rows = rows;
        this.totalRows = totalRows;
        this.truncated = truncated;
        this.rowsScanned = rowsScanned;
        this.numeric = new ArrayList<>();
        for (int c = 0; c < columns.size(); c++) {
            boolean any = false, all = true;
            for (List<Object> r : rows) {
                Object v = r.get(c);
                if (v == null) continue;
                any = true;
                if (!(v instanceof Number)) { all = false; break; }
            }
            numeric.add(any && all);
        }
    }

    @Override public Object toJson() {
        return Json.map("columns", columns, "numeric", numeric, "rows", rows, "totalRows", totalRows,
                "truncated", truncated, "rowsScanned", rowsScanned, "millis", millis);
    }
}
