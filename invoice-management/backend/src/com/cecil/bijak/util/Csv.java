package com.cecil.bijak.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes CSV (comma separated values, the format Excel saves) by the RFC 4180 rules:
 * a field with a comma, quote or new line is put in quotes, and a quote inside is written twice ("").
 */
public final class Csv {
    private Csv() { }

    public static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean quoted = false, any = false;
        if (text.startsWith("﻿")) text = text.substring(1);     // Excel's byte-order mark
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { f.append('"'); i++; }
                    else quoted = false;
                } else f.append(c);
                continue;
            }
            switch (c) {
                case '"' -> { quoted = true; any = true; }
                case ',' -> { row.add(f.toString()); f.setLength(0); any = true; }
                case '\r' -> { }
                case '\n' -> {
                    row.add(f.toString()); f.setLength(0);
                    if (any || row.size() > 1 || !row.get(0).isEmpty()) rows.add(row);
                    row = new ArrayList<>(); any = false;
                }
                default -> { f.append(c); any = true; }
            }
        }
        if (any || f.length() > 0) { row.add(f.toString()); rows.add(row); }
        return rows;
    }

    public static String write(List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        for (List<String> r : rows) {
            for (int i = 0; i < r.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(field(r.get(i)));
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }

    static String field(String v) {
        if (v == null) return "";
        // a cell starting with = + - @ could run as a formula in Excel ("CSV injection"): put a ' in front
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0 && !v.matches("-?\\d+(\\.\\d+)?")) v = "'" + v;
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) return '"' + v.replace("\"", "\"\"") + '"';
        return v;
    }
}
