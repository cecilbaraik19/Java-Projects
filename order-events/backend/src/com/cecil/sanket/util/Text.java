package com.cecil.sanket.util;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Small text helpers: clean user input and format numbers. */
public final class Text {
    private Text() { }

    public static boolean blank(String s) { return s == null || s.isBlank(); }

    /** Trims, removes control characters and squeezes spaces. Returns null for empty text. */
    public static String clean(String s) {
        if (s == null) return null;
        String t = s.replaceAll("[\\p{Cntrl}&&[^\n\t]]", "").replaceAll("[ \\t]+", " ").trim();
        return t.isEmpty() ? null : t;
    }

    public static String clean(String s, int maxLength) {
        String t = clean(s);
        if (t == null) return null;
        return t.length() > maxLength ? t.substring(0, maxLength) : t;
    }

    /** Small words that carry no meaning for search ("the", "is", "what" ...). */
    private static final java.util.Set<String> STOP = java.util.Set.of(
            "a", "an", "the", "is", "are", "was", "were", "be", "to", "of", "and", "or", "in", "on", "at", "for",
            "with", "do", "does", "did", "i", "me", "my", "we", "our", "you", "your", "it", "its", "this", "that",
            "what", "which", "who", "how", "when", "where", "can", "could", "please", "tell", "about", "there",
            "any", "some", "from", "by", "will", "should", "would", "have", "has", "get", "show", "give", "much", "many");

    /** Lower-case words without accents and stop words, with a very light stemming ("rooms" -> "room"). */
    public static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        for (String w : n.split("[^a-z0-9]+")) {
            if (w.length() < 2 || STOP.contains(w)) continue;
            out.add(stem(w));
        }
        return out;
    }

    static String stem(String w) {
        if (w.length() > 4 && w.endsWith("ies")) return w.substring(0, w.length() - 3) + "y";
        if (w.length() > 4 && w.endsWith("ing")) return w.substring(0, w.length() - 3);
        if (w.length() > 3 && w.endsWith("es") && !w.endsWith("ses")) return w.substring(0, w.length() - 2);
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) return w.substring(0, w.length() - 1);
        return w;
    }

    public static String pct(double v) { return Math.round(v) + "%"; }

    public static String one(double v) { return String.valueOf(Math.round(v * 10) / 10.0); }
}
