package com.cecil.edge.util;

import java.util.List;

/** Builds CSV text. Cells that start with = + - @ are prefixed with ' so spreadsheets do not run them as formulas. */
public final class Csv {
    private Csv() { }

    public static String cell(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            // plain negative numbers are safe and should stay numbers
            if (!s.matches("-?\\d+(\\.\\d+)?")) s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    public static String row(List<?> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(cell(cells.get(i)));
        }
        return sb.append("\r\n").toString();
    }
}
