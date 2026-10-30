package com.cecil.sld.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Small text helpers: trimming, checking lengths, formatting numbers and dates. */
public final class Text {
    private Text() {}

    private static final DateTimeFormatter NICE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    /** Trim; turn null into "". */
    public static String clean(String s) {
        if (s == null) return "";
        return s.strip().replaceAll("[\\p{Cntrl}&&[^\n\t]]", "");
    }

    public static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** Shorten text that is too long. */
    public static String cut(String s, int max) {
        s = clean(s);
        return s.length() <= max ? s : s.substring(0, max);
    }

    public static String niceDate(LocalDate d) {
        return d == null ? "" : d.format(NICE);
    }

    /** 0.734 -> "73%" */
    public static String pct(double ratio) {
        return Math.round(ratio * 100) + "%";
    }

    /** Round to one decimal place. */
    public static double r1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    /** Round to two decimal places. */
    public static double r2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    /** "1 question" / "3 questions" */
    public static String plural(long n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }

    /** A username must be simple: lowercase letters, numbers, dot, dash, underscore. */
    public static boolean validUsername(String u) {
        return u != null && u.matches("[a-z0-9._-]{3,30}");
    }
}
