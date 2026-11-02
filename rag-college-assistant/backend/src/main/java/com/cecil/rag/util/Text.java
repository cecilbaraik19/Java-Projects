package com.cecil.rag.util;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text helpers used by the search engine:
 * - tokens(): cut text into words, drop common words ("the", "is"), and stem ("fees" -> "fee")
 * - sentences(): cut text into sentences
 * - small detectors for money, dates, times and numbers (used to judge if a sentence really answers)
 */
public final class Text {
    private Text() {}

    /** Very common words. They appear everywhere, so they do not help the search. */
    public static final Set<String> STOP = Set.of(
            "a", "an", "the", "and", "or", "but", "if", "then", "else", "of", "to", "in", "on", "at", "by", "for",
            "with", "about", "as", "into", "from", "up", "down", "out", "over", "under", "is", "am", "are", "was",
            "were", "be", "been", "being", "do", "does", "did", "doing", "have", "has", "had", "having", "i", "me",
            "my", "we", "our", "you", "your", "he", "she", "him", "her", "it", "its", "they", "them", "their", "this",
            "that", "these", "those", "there", "here", "what", "which", "who", "whom", "whose", "when", "where",
            "why", "how", "can", "could", "should", "would", "will", "shall", "may", "might", "must", "so", "than",
            "too", "very", "just", "also", "any", "all", "each", "some", "such", "no", "not", "only", "own", "same",
            "other", "more", "most", "much", "many", "s", "t", "get", "got", "tell", "please", "know", "want",
            "need", "let", "us", "kindly", "sir", "madam", "mam", "college", "svit", "per", "via", "etc", "one");

    /**
     * "Soft" question words (already stemmed): they are part of how people ask ("what is REQUIRED",
     * "how LONG does it TAKE") but rarely appear in the answer itself, so they count only 30 %.
     */
    public static final double SOFT_WEIGHT = 0.2;
    public static final Set<String> SOFT = Set.of("requir", "need", "minimum", "maximum", "allow", "rule", "procedur",
            "process", "possibl", "availabl", "apply", "appli", "tak", "long", "mean", "give", "list", "detail",
            "inform", "info", "kind", "type", "way", "happen", "go", "use", "mak", "provid", "offer", "find", "able",
            "start", "sure", "ok", "okay", "regard", "related", "thing", "student", "fil", "rais", "submit", "ask", "do");

    private static final Pattern WORD =Pattern.compile("[\\p{L}\\p{N}]+(?:[-'][\\p{L}\\p{N}]+)*");

    /** Lower-case words with stop words removed and each word stemmed. */
    public static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = WORD.matcher(fold(text));
        while (m.find()) {
            String w = m.group();
            // "re-evaluation" is kept as one word and also split, so both forms match
            if (w.indexOf('-') > 0) {
                String joined = w.replace("-", "");
                addWord(out, joined);
                for (String part : w.split("-")) addWord(out, part);
            } else {
                addWord(out, w.replace("'", ""));
            }
        }
        return out;
    }

    private static void addWord(List<String> out, String w) {
        if (w.isEmpty() || STOP.contains(w)) return;
        if (w.length() == 1 && !Character.isDigit(w.charAt(0))) return;
        out.add(stem(w));
    }

    /** Words without stop-word removal or stemming (used for showing highlights). */
    public static List<String> rawWords(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = WORD.matcher(fold(text));
        while (m.find()) out.add(m.group());
        return out;
    }

    public static Set<String> tokenSet(String text) {
        return new LinkedHashSet<>(tokens(text));
    }

    /** Lower case, remove accents, unify quotes and dashes. */
    public static String fold(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).replace('’', '\'').replace('‘', '\'')
                .replace('–', '-').replace('—', '-');
    }

    /**
     * A light stemmer: removes common English endings so that "fees", "fee", "charged", "charges" and
     * "charging" all become the same word. Simpler than the full Porter stemmer but enough here.
     */
    public static String stem(String w) {
        if (w.length() <= 3 || Character.isDigit(w.charAt(0))) return w;
        String s = w;
        if (s.endsWith("ies") && s.length() > 4) s = s.substring(0, s.length() - 3) + "y";
        else if (s.endsWith("sses")) s = s.substring(0, s.length() - 2);
        else if (s.endsWith("ss") || s.endsWith("us") || s.endsWith("is")) { /* keep: class, bus, thesis */ }
        else if (s.endsWith("s") && !s.endsWith("ss")) s = s.substring(0, s.length() - 1);

        if (s.endsWith("ibility") || s.endsWith("ability")) s = s.substring(0, s.length() - 5) + "le"; // eligibility -> eligible
        if (s.endsWith("ational")) s = s.substring(0, s.length() - 7) + "ate";
        else if (s.endsWith("ization")) s = s.substring(0, s.length() - 7) + "ize";
        else if (s.endsWith("ation") && s.length() > 7) s = s.substring(0, s.length() - 5) + "e";
        else if (s.endsWith("ingly")) s = s.substring(0, s.length() - 5);
        else if (s.endsWith("ing") && s.length() > 5) s = undouble(s.substring(0, s.length() - 3));
        else if (s.endsWith("edly")) s = s.substring(0, s.length() - 4);
        else if (s.endsWith("ed") && s.length() > 4) s = undouble(s.substring(0, s.length() - 2));
        else if (s.endsWith("ly") && s.length() > 5) s = s.substring(0, s.length() - 2);
        else if (s.endsWith("ment") && s.length() > 7) s = s.substring(0, s.length() - 4);
        if (s.endsWith("e") && s.length() > 3 && !s.endsWith("ee")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String undouble(String s) {
        int n = s.length();
        if (n > 3 && s.charAt(n - 1) == s.charAt(n - 2) && "lsz".indexOf(s.charAt(n - 1)) < 0) return s.substring(0, n - 1);
        return s;
    }

    // ------------------------------------------------------------------ sentences

    private static final Pattern ABBREV = Pattern.compile("(?i)(?:\\b(?:dr|mr|mrs|ms|prof|no|rs|st|sr|jr|vs|approx|dept|i\\.e|e\\.g|b\\.sc|m\\.sc|b\\.tech|ph\\.d)\\.)$");

    /** Splits a block into sentences. Bullet lines and table rows count as their own sentence. */
    public static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            String l = line.strip();
            if (l.isEmpty()) continue;
            l = l.replaceFirst("^(?:[-*•]|\\d+[.)])\\s+", "");
            if (l.startsWith("#")) continue; // headings are not answer sentences
            if (l.startsWith("|")) { // markdown table row -> "a: b; c"
                if (l.matches("^\\|[\\s:|-]+\\|?$")) continue;
                out.add(l.replaceAll("^\\||\\|$", "").replaceAll("\\s*\\|\\s*", " | ").strip());
                continue;
            }
            int start = 0;
            for (int i = 0; i < l.length(); i++) {
                char c = l.charAt(i);
                if ((c == '.' || c == '?' || c == '!') && i + 1 < l.length() && l.charAt(i + 1) == ' ') {
                    String part = l.substring(start, i + 1);
                    if (ABBREV.matcher(part).find()) continue;
                    if (i + 2 < l.length() && Character.isLowerCase(l.charAt(i + 2))) continue;
                    out.add(part.strip());
                    start = i + 2;
                }
            }
            if (start < l.length()) out.add(l.substring(start).strip());
        }
        out.removeIf(s -> s.length() < 3);
        return out;
    }

    // ------------------------------------------------------------------ detectors

    public static final Pattern MONEY = Pattern.compile(
            "(?:₹|rs\\.?|inr)\\s?\\d[\\d,]*(?:\\.\\d+)?(?:\\s?(?:lakh|lakhs|crore))?|\\d[\\d,]*(?:\\.\\d+)?\\s?(?:rupees|/-)",
            Pattern.CASE_INSENSITIVE);
    private static final String MONTH = "(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?"
            + "|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\b";
    public static final Pattern DATE = Pattern.compile(
            "\\b\\d{1,2}(?:st|nd|rd|th)?\\s+" + MONTH + "\\.?(?:,?\\s+\\d{4})?\\b"
                    + "|\\b" + MONTH + "\\.?\\s+\\d{1,2}(?:st|nd|rd|th)?(?:,?\\s+\\d{4})?\\b"
                    + "|\\b\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}\\b"
                    + "|\\b(?:january|february|march|april|june|july|august|september|october|november|december)\\b"
                    + "|\\b(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)s?\\b",
            Pattern.CASE_INSENSITIVE);
    public static final Pattern TIME = Pattern.compile(
            "\\b\\d{1,2}(?::\\d{2})?\\s?(?:am|pm|a\\.m\\.|p\\.m\\.)|\\b\\d{1,2}:\\d{2}\\b|\\bnoon\\b|\\bmidnight\\b",
            Pattern.CASE_INSENSITIVE);
    public static final Pattern NUMBER = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\s?%?|\\b(?:one|two|three|four|five|six|seven|eight|nine|ten|twelve|fifteen|twenty|thirty)\\b",
            Pattern.CASE_INSENSITIVE);
    public static final Pattern CONTACT = Pattern.compile(
            "[\\w.+-]+@[\\w-]+\\.[\\w.]+|\\b(?:\\+91[\\s-]?)?\\d{3,5}[\\s-]?\\d{3}[\\s-]?\\d{3,4}\\b|\\broom\\s+\\w+|\\bext(?:ension)?\\.?\\s*\\d+",
            Pattern.CASE_INSENSITIVE);
    public static final Pattern PERSON = Pattern.compile("\\b(?:Dr|Prof|Mr|Mrs|Ms)\\.?\\s+[A-Z][a-z]+(?:\\s+[A-Z][a-z]+)?");

    public static String firstMatch(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group().strip() : null;
    }

    /** Shortens long text for previews. */
    public static String clip(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").strip();
        if (s.length() <= max) return s;
        int cut = s.lastIndexOf(' ', max - 1);
        return s.substring(0, cut > max / 2 ? cut : max - 1) + "…";
    }

    public static int wordCount(String s) {
        if (s == null || s.isBlank()) return 0;
        return s.strip().split("\\s+").length;
    }

    /** Dice similarity of two token sets (0 = nothing shared, 1 = same words). */
    public static double dice(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int common = 0;
        for (String x : a) if (b.contains(x)) common++;
        return 2.0 * common / (a.size() + b.size());
    }

    public static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1;
        int common = 0;
        for (String x : a) if (b.contains(x)) common++;
        return (double) common / (a.size() + b.size() - common);
    }

    public static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
