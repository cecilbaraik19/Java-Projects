package com.cecil.sql.db;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** The four data types the mini database understands. Dates are kept as "YYYY-MM-DD" text, so they sort correctly. */
public enum ColumnType {
    INT, DECIMAL, TEXT, DATE;

    public boolean numeric() { return this == INT || this == DECIMAL; }

    /** Turns CSV text into a Java value: Long, Double, String or null. */
    public Object parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || s.equalsIgnoreCase("null")) return null;
        try {
            return switch (this) {
                case INT -> Long.parseLong(s.replace(",", ""));
                case DECIMAL -> Double.parseDouble(s.replace(",", ""));
                case DATE -> LocalDate.parse(normaliseDate(s)).toString();
                case TEXT -> raw;
            };
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new IllegalArgumentException("'" + raw + "' is not a valid " + name());
        }
    }

    /** Accepts 2026-01-05, 2026/01/05 and 05-01-2026 / 05/01/2026 (day first, Indian style). */
    static String normaliseDate(String s) {
        String t = s.replace('/', '-');
        if (t.matches("\\d{1,2}-\\d{1,2}-\\d{4}")) {
            String[] p = t.split("-");
            return p[2] + "-" + pad(p[1]) + "-" + pad(p[0]);
        }
        if (t.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) {
            String[] p = t.split("-");
            return p[0] + "-" + pad(p[1]) + "-" + pad(p[2]);
        }
        return t;
    }

    private static String pad(String x) { return x.length() == 1 ? "0" + x : x; }

    /** Guesses the best type for a column of CSV values (used when a user uploads a file). */
    public static ColumnType guess(Iterable<String> values) {
        boolean anyValue = false, allInt = true, allNum = true, allDate = true;
        for (String v : values) {
            if (v == null || v.isBlank()) continue;
            anyValue = true;
            String s = v.trim().replace(",", "");
            if (!s.matches("-?\\d{1,18}")) allInt = false;
            if (!s.matches("-?\\d+(\\.\\d+)?")) allNum = false;
            try { LocalDate.parse(normaliseDate(v.trim())); } catch (DateTimeParseException e) { allDate = false; }
        }
        if (!anyValue) return TEXT;
        if (allInt) return INT;
        if (allNum) return DECIMAL;
        if (allDate) return DATE;
        return TEXT;
    }
}
