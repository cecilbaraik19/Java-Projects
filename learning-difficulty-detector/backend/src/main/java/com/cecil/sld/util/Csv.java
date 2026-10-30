package com.cecil.sld.util;

import java.util.List;

/** Builds CSV text for the export buttons. */
public final class Csv {
    private Csv() {}

    public static String build(List<String> header, List<List<Object>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append('﻿');                 // tells Excel the file is UTF-8
        line(sb, header);
        for (List<Object> r : rows) line(sb, r);
        return sb.toString();
    }

    private static void line(StringBuilder sb, List<?> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(cell(cells.get(i)));
        }
        sb.append("\r\n");
    }

    /**
     * Quote one cell. Text starting with = + - @ is made safe so a spreadsheet
     * does not run it as a formula (CSV / formula injection).
     */
    public static String cell(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        if (!(v instanceof Number) && !s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r") || s.startsWith("'")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
