package com.cecil.docqa.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns text into search words: lower case, drops common words ("the", "is" ...)
 * and cuts simple endings so "running", "runs" and "run" all match.
 */
public final class Tokenizer {

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "been", "but", "by", "can", "could", "did", "do",
            "does", "for", "from", "had", "has", "have", "how", "i", "if", "in", "into", "is", "it", "its",
            "me", "my", "of", "on", "or", "our", "please", "should", "so", "tell", "than", "that", "the",
            "their", "them", "then", "there", "these", "they", "this", "those", "to", "was", "we", "were",
            "what", "when", "where", "which", "who", "whom", "why", "will", "with", "would", "you", "your",
            "about", "also", "any", "all", "give", "explain", "describe", "document", "documents", "s");

    private Tokenizer() {}

    public static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String raw : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (raw.isEmpty() || STOP_WORDS.contains(raw)) continue;
            String stem = stem(raw);
            if (!stem.isEmpty()) out.add(stem);
        }
        return out;
    }

    /** A very small stemmer — good enough for matching, and easy to read. */
    static String stem(String w) {
        if (w.length() <= 3 || Character.isDigit(w.charAt(0))) return w;
        String s = stripEnding(w);
        // "store", "stored", "storing" -> "stor"
        if (s.length() > 4 && s.endsWith("e")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String stripEnding(String w) {
        if (w.endsWith("ies") && w.length() > 4) return w.substring(0, w.length() - 3) + "y";
        if (w.endsWith("ing") && w.length() > 5) return undouble(w.substring(0, w.length() - 3));
        if (w.endsWith("ed") && w.length() > 4) return undouble(w.substring(0, w.length() - 2));
        if (w.endsWith("ly") && w.length() > 4) return w.substring(0, w.length() - 2);
        if (w.endsWith("es") && w.length() > 4 && "sxz".indexOf(w.charAt(w.length() - 3)) >= 0)
            return w.substring(0, w.length() - 2);
        if (w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is"))
            return w.substring(0, w.length() - 1);
        return w;
    }

    private static String undouble(String w) {
        int n = w.length();
        if (n > 2 && w.charAt(n - 1) == w.charAt(n - 2) && "lsz".indexOf(w.charAt(n - 1)) < 0) {
            return w.substring(0, n - 1);
        }
        return w;
    }
}
