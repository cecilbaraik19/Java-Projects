package com.cecil.sambandh.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes CSV (comma separated values) - the format Excel and Google Sheets use.
 * Handles "quoted, values", doubled quotes ("") and new lines inside quotes.
 */
public final class Csv {
    private Csv() { }

    public static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        if (text == null) return rows;
        if (text.startsWith("﻿")) text = text.substring(1);   // Excel's byte-order mark
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                    else quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString()); cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString()); cell.setLength(0);
                if (!(row.size() == 1 && row.get(0).isBlank())) rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        row.add(cell.toString());
        if (!(row.size() == 1 && row.get(0).isBlank())) rows.add(row);
        return rows;
    }

    /**
     * One CSV line. Cells that start with = + - @ (or a tab) get a ' in front so that Excel does not run them
     * as formulas ("CSV injection"). Phone numbers like +91... are written with the ' too - Excel then keeps them as text.
     */
    public static String line(List<?> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(',');
            Object o = cells.get(i);
            String s = o == null ? "" : String.valueOf(o);
            if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
            if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
                s = "\"" + s.replace("\"", "\"\"") + "\"";
            }
            sb.append(s);
        }
        return sb.append("\r\n").toString();
    }
}
