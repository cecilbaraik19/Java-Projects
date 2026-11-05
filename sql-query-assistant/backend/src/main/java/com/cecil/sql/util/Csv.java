package com.cecil.sql.util;

import java.util.ArrayList;
import java.util.List;

/** Reads and writes CSV text (RFC 4180 style: commas, "quotes", "" for a quote inside quotes). */
public final class Csv {
    private Csv() {}

    public static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        String s = text.startsWith("﻿") ? text.substring(1) : text;   // drop Excel's BOM
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') { cell.append('"'); i++; }
                    else inQuotes = false;
                } else cell.append(c);
            } else if (c == '"') inQuotes = true;
            else if (c == ',') { row.add(cell.toString()); cell.setLength(0); }
            else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') i++;
                row.add(cell.toString());
                cell.setLength(0);
                if (!(row.size() == 1 && row.get(0).isEmpty())) rows.add(row);
                row = new ArrayList<>();
            } else cell.append(c);
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            if (!(row.size() == 1 && row.get(0).isEmpty())) rows.add(row);
        }
        return rows;
    }

    public static String cell(Object v) {
        if (v == null) return "";
        String s = v instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf(d) : String.valueOf(v);
        // stop spreadsheet formula injection when the file is opened in Excel
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0 && !(v instanceof Number)) s = "'" + s;
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r"))
            return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    public static String line(List<?> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(cell(cells.get(i)));
        }
        return sb.append("\r\n").toString();
    }
}
