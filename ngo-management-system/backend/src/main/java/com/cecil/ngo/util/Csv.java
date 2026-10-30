package com.cecil.ngo.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes CSV (comma separated values) files, the format Excel opens.
 * Writing blocks "formula injection": a cell that starts with = + - @ gets a ' in front,
 * so Excel shows it as text instead of running it.
 */
public final class Csv {
    private Csv() {}

    public static String cell(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    public static String row(Object... cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(cell(cells[i]));
        }
        return sb.append("\r\n").toString();
    }

    /** Splits CSV text into rows of cells. Handles quotes, commas and line breaks inside quotes. */
    public static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        if (text == null) return rows;
        if (text.startsWith("﻿")) text = text.substring(1); // Excel's BOM
        List<String> row = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQ = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQ) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else inQ = false;
                } else cur.append(c);
            } else if (c == '"') {
                inQ = true;
            } else if (c == ',') {
                row.add(cur.toString()); cur.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cur.toString()); cur.setLength(0);
                if (!(row.size() == 1 && row.get(0).isEmpty())) rows.add(row);
                row = new ArrayList<>();
            } else cur.append(c);
        }
        row.add(cur.toString());
        if (!(row.size() == 1 && row.get(0).isEmpty())) rows.add(row);
        return rows;
    }

    /** Removes the ' that cell() adds in front of = + - @ (so export then import gives the same text). */
    public static String unguard(String s) {
        if (s != null && s.length() > 1 && s.charAt(0) == '\'' && "=+-@".indexOf(s.charAt(1)) >= 0) return s.substring(1);
        return s;
    }
}
