package com.cecil.ngo.util;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/** Small helpers for cleaning and checking what people type into forms. */
public final class Text {
    private Text() {}

    /** Trims, removes control characters (keeps line breaks) and checks the length. */
    public static String clean(String s, String field, int max, boolean required) {
        if (s == null) s = "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '\n' || c == '\t' || c >= 0x20) sb.append(c == '\t' ? ' ' : c);
        }
        String out = sb.toString().replace("\r", "").trim();
        if (required && out.isEmpty()) throw new IllegalArgumentException(field + " is required");
        if (out.length() > max) throw new IllegalArgumentException(field + " is too long (max " + max + " characters)");
        return out;
    }

    /** One-line text (line breaks become spaces). */
    public static String line(String s, String field, int max, boolean required) {
        return clean(s == null ? null : s.replace('\n', ' ').replace('\r', ' '), field, max, required).replaceAll(" {2,}", " ");
    }

    public static int range(Integer v, String field, int min, int max) {
        if (v == null) throw new IllegalArgumentException(field + " is required");
        if (v < min || v > max) throw new IllegalArgumentException(field + " must be between " + min + " and " + max);
        return v;
    }

    public static LocalDate date(String s, String field, boolean required) {
        if (s == null || s.isBlank()) {
            if (required) throw new IllegalArgumentException(field + " is required");
            return null;
        }
        try { return LocalDate.parse(s.trim()); }
        catch (DateTimeParseException e) { throw new IllegalArgumentException(field + " must be a date like 2026-11-20"); }
    }

    /** Optional email: empty is fine, otherwise it must look like name@place.domain. */
    public static String email(String s) {
        String e = line(s, "Email", 120, false).toLowerCase(Locale.ROOT);
        if (!e.isEmpty() && !e.matches("[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}")) throw new IllegalArgumentException("Email does not look right");
        return e;
    }

    /** Optional Indian phone number: 10 digits, may start with +91. Spaces and dashes are removed. */
    public static String phone(String s) {
        String p = line(s, "Phone", 20, false).replaceAll("[\\s-]", "");
        if (p.isEmpty()) return p;
        if (p.startsWith("+91")) p = p.substring(3);
        if (!p.matches("[6-9][0-9]{9}")) throw new IllegalArgumentException("Phone must be a 10-digit mobile number");
        return p;
    }

    /** Lower-case letters and digits only, for comparing names ("Sita Devi" == "sita  devi"). */
    public static String key(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
