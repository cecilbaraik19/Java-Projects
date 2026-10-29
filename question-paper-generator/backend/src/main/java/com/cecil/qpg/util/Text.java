package com.cecil.qpg.util;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Small text helpers: cleaning input, checking lengths, and finding similar questions. */
public final class Text {
    private Text() {}

    /** Common words that do not tell two questions apart. */
    private static final Set<String> STOP = Set.of(
            "a", "an", "the", "of", "in", "on", "to", "and", "or", "is", "are", "was", "be", "with", "for", "by",
            "its", "it", "this", "that", "what", "which", "how", "why", "when", "do", "does", "write", "explain",
            "describe", "define", "give", "short", "note", "notes", "brief", "briefly", "suitable", "example",
            "examples", "between", "using", "any", "two", "three", "list", "state", "discuss", "detail", "help",
            "neat", "proper", "simple", "own", "your", "following", "each", "also");

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

    /** The meaningful words of a question, in lower case, with simple plural endings removed. */
    public static Set<String> words(String s) {
        Set<String> out = new HashSet<>();
        if (s == null) return out;
        for (String w : s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (w.length() < 2 || STOP.contains(w)) continue;
            if (w.length() > 4 && w.endsWith("ies")) w = w.substring(0, w.length() - 3) + "y";          // policies -> policy
            else if (w.length() > 4 && w.matches(".*(sses|shes|ches|xes|zes)")) w = w.substring(0, w.length() - 2); // processes -> process
            else if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) w = w.substring(0, w.length() - 1);    // pages -> page
            out.add(w);
        }
        return out;
    }

    /**
     * How alike two questions are, from 0 (nothing shared) to 1 (same words).
     * Jaccard similarity = shared words / all different words.
     */
    public static double similarity(String a, String b) {
        Set<String> x = words(a), y = words(b);
        if (x.isEmpty() || y.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(x);
        inter.retainAll(y);
        Set<String> union = new HashSet<>(x);
        union.addAll(y);
        return (double) inter.size() / union.size();
    }

    public static String pad2(int n) { return n < 10 ? "0" + n : String.valueOf(n); }
}
