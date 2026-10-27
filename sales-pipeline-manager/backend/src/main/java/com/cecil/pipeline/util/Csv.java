package com.cecil.pipeline.util;

import java.util.List;

/** Builds CSV text (opens cleanly in Excel / Google Sheets). */
public final class Csv {

    private Csv() { }

    public static String build(List<String> header, List<List<Object>> rows) {
        StringBuilder sb = new StringBuilder("﻿"); // BOM so Excel reads UTF-8 (₹, names) correctly
        line(sb, header.stream().map(h -> (Object) h).toList());
        for (List<Object> r : rows) line(sb, r);
        return sb.toString();
    }

    private static void line(StringBuilder sb, List<Object> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(cell(cells.get(i)));
        }
        sb.append("\r\n");
    }

    /** Quotes a value when needed, and blocks spreadsheet formula injection (=, +, -, @ at the start). */
    public static String cell(Object v) {
        if (v == null) return "";
        String s = v.toString();
        if (!(v instanceof Number) && !s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
