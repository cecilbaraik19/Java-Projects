package com.cecil.energy.util;

import java.util.List;

/** Builds CSV text that opens cleanly in Excel. */
public final class Csv {

    private Csv() { }

    /** Quotes a cell. Cells starting with = + - @ are prefixed with ' so Excel does not run them as formulas. */
    public static String cell(Object value) {
        String s = value == null ? "" : String.valueOf(value);
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 && !isNumber(s)) s = "'" + s;
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static boolean isNumber(String s) {
        try { Double.parseDouble(s); return true; } catch (NumberFormatException e) { return false; }
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
