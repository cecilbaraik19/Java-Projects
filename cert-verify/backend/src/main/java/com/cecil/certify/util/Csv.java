package com.cecil.certify.util;

import java.util.ArrayList;
import java.util.List;

/** Reads CSV text (used for issuing many certificates at once) and writes safe CSV cells. */
public final class Csv {
    private Csv() {}

    /** Splits CSV text into rows of cells. Handles "quoted, cells" and "" inside quotes. */
    public static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        String t = text.startsWith("﻿") ? text.substring(1) : text;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < t.length() && t.charAt(i + 1) == '"') { cell.append('"'); i++; }
                    else quoted = false;
                } else cell.append(c);
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString().trim());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < t.length() && t.charAt(i + 1) == '\n') i++;
                row.add(cell.toString().trim());
                cell.setLength(0);
                if (!(row.size() == 1 && row.get(0).isEmpty())) rows.add(row);
                row = new ArrayList<>();
            } else cell.append(c);
        }
        row.add(cell.toString().trim());
        if (!(row.size() == 1 && row.get(0).isEmpty())) rows.add(row);
        return rows;
    }

    /** One CSV cell. Cells starting with = + - @ get a ' in front so Excel never runs them as a formula. */
    public static String cell(String v) {
        if (v == null) return "";
        String s = v;
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
