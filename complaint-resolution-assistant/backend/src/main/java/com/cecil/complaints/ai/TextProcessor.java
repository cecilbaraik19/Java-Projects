package com.cecil.complaints.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Basic NLP helpers: tokenizing, stop-word removal and a light stemmer.
 *
 * Example: "My orders were delivered LATE!!" -> [order, deliver, late]
 */
public final class TextProcessor {

    private TextProcessor() {
    }

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "and", "or", "but", "if", "then", "so", "of", "at", "by", "for", "with",
            "about", "to", "from", "in", "on", "into", "over", "under", "again", "further", "once",
            "here", "there", "when", "where", "why", "how", "all", "any", "both", "each", "few", "more",
            "most", "other", "some", "such", "only", "own", "same", "than", "too", "very", "can", "will",
            "just", "should", "now", "i", "me", "my", "myself", "we", "our", "ours", "you", "your",
            "yours", "he", "him", "his", "she", "her", "it", "its", "they", "them", "their", "what",
            "which", "who", "whom", "this", "that", "these", "those", "am", "is", "are", "was", "were",
            "be", "been", "being", "have", "has", "had", "having", "do", "does", "did", "doing", "would",
            "could", "also", "please", "hi", "hello", "dear", "team", "sir", "madam", "regards", "thanks",
            "thank", "get", "got", "im", "ive", "id", "us", "as", "up", "out", "off", "even", "still",
            "yet", "one", "two", "since", "because", "while", "until", "after", "before", "said", "told",
            "via", "per", "really", "much", "many", "way", "know", "like", "want", "need", "make", "made",
            "not", "no", "nor", "never", "cannot", "cant", "dont", "didnt", "doesnt", "isnt", "wasnt", "wont",
            "ever", "says", "say", "rs", "inr", "use", "used", "yesterday", "every", "tried", "try", "see",
            "going", "go", "come", "came", "day", "days", "time", "times", "someone", "something", "anything",
            "bought", "buy", "started", "start", "first", "gave", "give", "getting", "keep", "keeps", "able");

    /** Lower-case words and numbers, keeping apostrophes inside words (don't, can't). */
    public static List<String> rawTokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        String lower = text.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c) || ((c == '\'' || c == '’') && cur.length() > 0)) {
                cur.append(c == '’' ? '\'' : c);
            } else if (cur.length() > 0) {
                out.add(trimApostrophe(cur.toString()));
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) {
            out.add(trimApostrophe(cur.toString()));
        }
        return out;
    }

    private static String trimApostrophe(String s) {
        while (s.endsWith("'")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** Tokens with stop words removed and stems applied. Used for classification and matching. */
    public static List<String> terms(String text) {
        List<String> out = new ArrayList<>();
        for (String t : rawTokens(text)) {
            String clean = t.replace("'", "");
            if (clean.length() < 2 || STOP_WORDS.contains(clean) || isNumber(clean)) {
                continue;
            }
            out.add(stem(clean));
        }
        return out;
    }

    /** Terms plus two-word phrases ("credit card", "wrong item") for better matching. */
    public static List<String> termsWithBigrams(String text) {
        List<String> terms = terms(text);
        List<String> out = new ArrayList<>(terms);
        for (int i = 0; i + 1 < terms.size(); i++) {
            out.add(terms.get(i) + "_" + terms.get(i + 1));
        }
        return out;
    }

    /** Maps each stem back to the first real word that produced it, e.g. "charg" -> "charged". */
    public static java.util.Map<String, String> displayMap(String text) {
        java.util.Map<String, String> display = new java.util.HashMap<>();
        for (String raw : rawTokens(text)) {
            String clean = raw.replace("'", "");
            if (clean.length() < 2 || STOP_WORDS.contains(clean)) {
                continue;
            }
            display.putIfAbsent(stem(clean), clean);
        }
        return display;
    }

    public static boolean isStopWord(String word) {
        return STOP_WORDS.contains(word);
    }

    static boolean isNumber(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * A small suffix-stripping stemmer. It is not perfect, but it maps common forms together:
     * refunds/refunded/refunding -> refund, deliveries/delivered -> deliver.
     */
    public static String stem(String w) {
        String s = stripSuffix(w);
        // delivery/deliveries -> deliver, so they match "delivered"
        if (s.length() > 5 && s.endsWith("ery")) {
            s = s.substring(0, s.length() - 1);
        }
        // drop a final silent "e" so charge/charged/charges all become "charg"
        if (s.length() > 4 && s.endsWith("e") && !s.endsWith("ee")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String stripSuffix(String w) {
        if (w.length() <= 3) {
            return w;
        }
        if (w.endsWith("ies") && w.length() > 4) {
            return w.substring(0, w.length() - 3) + "y";
        }
        if (w.endsWith("ing") && w.length() > 5) {
            return undouble(w.substring(0, w.length() - 3));
        }
        if (w.endsWith("ed") && w.length() > 4) {
            String base = w.substring(0, w.length() - 2);
            if (base.endsWith("i")) {
                return base.substring(0, base.length() - 1) + "y";
            }
            return undouble(base);
        }
        if (w.endsWith("ly") && w.length() > 5) {
            return w.substring(0, w.length() - 2);
        }
        if (w.endsWith("ment") && w.length() > 7) {
            return w.substring(0, w.length() - 4);
        }
        if (w.endsWith("es") && w.length() > 4 && (w.endsWith("sses") || w.endsWith("ches") || w.endsWith("shes") || w.endsWith("xes"))) {
            return w.substring(0, w.length() - 2);
        }
        if (w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is")) {
            return w.substring(0, w.length() - 1);
        }
        return w;
    }

    private static String undouble(String s) {
        int n = s.length();
        if (n >= 2 && s.charAt(n - 1) == s.charAt(n - 2) && "bdgmnprt".indexOf(s.charAt(n - 1)) >= 0) {
            return s.substring(0, n - 1);
        }
        // "deliver" stays, "charg" -> "charge" is not restored; good enough for matching
        return s;
    }
}
