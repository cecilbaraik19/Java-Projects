package com.cecil.mail.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Breaks email text into clean words (tokens) that the AI can learn from.
 * Steps: lowercase -> split on non-letters -> drop common "stop words" -> light stemming.
 */
public final class TextTokenizer {

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "and", "or", "but", "if", "of", "to", "in", "on", "at", "by", "for",
            "with", "is", "are", "was", "were", "be", "been", "am", "it", "this", "that", "these",
            "those", "i", "me", "my", "we", "our", "you", "your", "he", "she", "they", "them", "his",
            "her", "its", "as", "so", "do", "does", "did", "have", "has", "had", "will", "would",
            "shall", "should", "can", "could", "may", "might", "from", "up", "about", "into", "than",
            "then", "there", "here", "what", "which", "who", "whom", "how", "all", "any", "some",
            "just", "very", "also", "too", "hi", "hello", "dear", "regards", "thanks", "sir", "madam",
            "best", "sincerely", "warm", "kind", "mr", "ms", "mrs", "dr", "team");

    private TextTokenizer() { }

    /** Returns stemmed, meaningful words. Used for training and prediction. */
    public static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        String[] parts = text.toLowerCase(Locale.ROOT).split("[^a-z']+");
        for (String p : parts) {
            String w = p.replace("'", "");
            if (w.length() < 2 || STOP_WORDS.contains(w)) continue;
            out.add(stem(w));
        }
        return out;
    }

    /** Returns lowercase words without removing anything. Used by sentiment and tone checks. */
    public static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String p : text.toLowerCase(Locale.ROOT).split("[^a-z']+")) {
            if (!p.isBlank()) out.add(p.replace("'", ""));
        }
        return out;
    }

    /** Very small stemmer: "meetings" -> "meeting", "delayed" -> "delay", "charging" -> "charg". */
    static String stem(String w) {
        if (w.length() > 5 && w.endsWith("ing")) return w.substring(0, w.length() - 3);
        if (w.length() > 4 && w.endsWith("ed")) return w.substring(0, w.length() - 2);
        if (w.length() > 4 && w.endsWith("ies")) return w.substring(0, w.length() - 3) + "y";
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) return w.substring(0, w.length() - 1);
        return w;
    }
}
