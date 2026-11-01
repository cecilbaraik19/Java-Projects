package com.cecil.invoice.util;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds and reads dates written in the many ways invoices use:
 * 12/10/2026, 12-10-26, 12.10.2026, 2026-10-12, 12 Oct 2026, 12-Oct-26, October 12, 2026.
 * Numbers-only dates are read day first (Indian style), unless that is impossible.
 */
public final class Dates {
    private Dates() {}

    public record Found(LocalDate date, int start, int end) {}

    private static final String MON = "(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?";
    private static final Pattern ISO = Pattern.compile("(?<!\\d)(20\\d{2})-(\\d{1,2})-(\\d{1,2})(?!\\d)");
    private static final Pattern DMY = Pattern.compile("(?<![\\d/.-])(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4}|\\d{2})(?![\\d/.-])");
    private static final Pattern D_MON_Y = Pattern.compile("(?i)(?<!\\d)(\\d{1,2})(?:st|nd|rd|th)?[\\s-]+" + MON + "[\\s,'-]*(\\d{4}|\\d{2})(?!\\d)");
    private static final Pattern MON_D_Y = Pattern.compile("(?i)(?<![a-z])" + MON + "\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})(?!\\d)");

    private static final String[] MONTHS = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};

    /** All dates in a line, left to right. */
    public static List<Found> findAll(String line) {
        List<Found> out = new ArrayList<>();
        if (line == null) return out;
        Matcher m = ISO.matcher(line);
        while (m.find()) add(out, make(i(m.group(1)), i(m.group(2)), i(m.group(3))), m);
        m = D_MON_Y.matcher(line);
        while (m.find()) add(out, make(year(m.group(3)), month(m.group(2)), i(m.group(1))), m);
        m = MON_D_Y.matcher(line);
        while (m.find()) add(out, make(i(m.group(3)), month(m.group(1)), i(m.group(2))), m);
        m = DMY.matcher(line);
        while (m.find()) {
            int a = i(m.group(1)), b = i(m.group(2)), y = year(m.group(3));
            LocalDate d = make(y, b, a);                 // day first
            if (d == null && a <= 12) d = make(y, a, b); // 10/25/2026 can only be month first
            add(out, d, m);
        }
        out.sort((x, y) -> Integer.compare(x.start(), y.start()));
        return out;
    }

    /** Read one date, or null. */
    public static LocalDate parse(String s) {
        List<Found> f = findAll(s);
        return f.isEmpty() ? null : f.get(0).date();
    }

    private static void add(List<Found> out, LocalDate d, Matcher m) {
        if (d == null) return;
        for (Found f : out) if (m.start() < f.end() && f.start() < m.end()) return;   // overlaps one already found
        out.add(new Found(d, m.start(), m.end()));
    }

    private static int i(String s) { return Integer.parseInt(s); }

    private static int year(String s) {
        int y = Integer.parseInt(s);
        return s.length() == 2 ? 2000 + y : y;
    }

    private static int month(String s) {
        String k = s.toLowerCase(Locale.ROOT).substring(0, 3);
        for (int i = 0; i < 12; i++) if (MONTHS[i].equals(k)) return i + 1;
        return 0;
    }

    private static LocalDate make(int y, int m, int d) {
        if (y < 1990 || y > 2100) return null;
        try { return LocalDate.of(y, m, d); } catch (DateTimeException e) { return null; }
    }
}
