package com.cecil.sql.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Step 1 of understanding English: cut the question into words and numbers.
 *   - lower case + light stemming, so "students" and "student" match
 *   - numbers in many shapes: 8.5, 50,000, 50k, 1.5 lakh, ₹500, 80%, "five", "3rd"
 *   - text in "quotes" stays one piece
 */
public final class NlTokenizer {
    private NlTokenizer() {}

    public enum Kind { WORD, NUMBER, QUOTED, SYMBOL }

    public static final class Word {
        public final Kind kind;
        public final String raw;      // as typed
        public final String norm;     // lower case
        public final String stem;     // lower case, plural removed
        public final Double number;   // for NUMBER
        public final boolean ordinal; // 3rd, third
        public final String unit;     // "rupees", "percent" or null
        public final int index;

        Word(Kind kind, String raw, String norm, Double number, boolean ordinal, String unit, int index) {
            this.kind = kind;
            this.raw = raw;
            this.norm = norm;
            this.stem = kind == Kind.WORD ? stem(norm) : norm;
            this.number = number;
            this.ordinal = ordinal;
            this.unit = unit;
            this.index = index;
        }

        public boolean isNumber() { return kind == Kind.NUMBER; }
        public boolean isWholeNumber() { return number != null && number == Math.rint(number); }
        public boolean isYear() { return isWholeNumber() && !ordinal && unit == null && number >= 1990 && number <= 2100; }
        @Override public String toString() { return raw; }
    }

    static final Map<String, Integer> NUMBER_WORDS = Map.ofEntries(
            Map.entry("zero", 0), Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4),
            Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8), Map.entry("nine", 9),
            Map.entry("ten", 10), Map.entry("eleven", 11), Map.entry("twelve", 12), Map.entry("fifteen", 15),
            Map.entry("twenty", 20), Map.entry("fifty", 50), Map.entry("hundred", 100));
    static final Map<String, Integer> ORDINAL_WORDS = Map.of("first", 1, "second", 2, "third", 3, "fourth", 4,
            "fifth", 5, "sixth", 6, "seventh", 7, "eighth", 8);
    static final Set<String> CURRENCY = Set.of("rs", "rs.", "inr", "rupee", "rupees", "₹");

    public static List<Word> tokenize(String text) {
        List<String> pieces = new ArrayList<>();
        List<Kind> kinds = new ArrayList<>();
        String s = text == null ? "" : text.replace('’', '\'').replace('“', '"').replace('”', '"');
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '"' || (c == '\'' && (i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1))))) {
                int end = s.indexOf(c, i + 1);
                if (end > i + 1) {
                    pieces.add(s.substring(i + 1, end));
                    kinds.add(Kind.QUOTED);
                    i = end + 1;
                    continue;
                }
                i++;
                continue;
            }
            if (c == '₹') { pieces.add("₹"); kinds.add(Kind.WORD); i++; continue; }
            if (Character.isLetterOrDigit(c)) {
                int st = i;
                while (i < n) {
                    char d = s.charAt(i);
                    if (Character.isLetterOrDigit(d)) { i++; continue; }
                    // keep 8.5 / 50,000 / A+ together
                    if ((d == '.' || d == ',') && i + 1 < n && Character.isDigit(s.charAt(i + 1)) && Character.isDigit(s.charAt(i - 1))) { i++; continue; }
                    if (d == '+' && i - st == 1 && Character.isUpperCase(s.charAt(st))) { i++; break; }   // grade A+
                    break;
                }
                String w = s.substring(st, i);
                if (i < n && s.charAt(i) == '\'' && i + 1 < n && s.charAt(i + 1) == 's') i += 2;   // student's -> student
                pieces.add(w);
                kinds.add(Kind.WORD);
                if (i < n && s.charAt(i) == '%') { pieces.add("%"); kinds.add(Kind.SYMBOL); i++; }
                continue;
            }
            if (c == '>' || c == '<' || c == '=' || c == '!') {
                String two = i + 1 < n ? s.substring(i, i + 2) : "";
                if (two.equals(">=") || two.equals("<=") || two.equals("!=")) { pieces.add(two); i += 2; }
                else { pieces.add(String.valueOf(c)); i++; }
                kinds.add(Kind.SYMBOL);
                continue;
            }
            if (c == '-' && i + 1 < n && Character.isLetter(s.charAt(i + 1)) && i > 0 && Character.isLetter(s.charAt(i - 1))) {
                i++;   // department-wise -> department wise
                continue;
            }
            if (c == '%') { pieces.add("%"); kinds.add(Kind.SYMBOL); }
            i++;
        }

        List<Word> out = new ArrayList<>();
        for (int k = 0; k < pieces.size(); k++) {
            String raw = pieces.get(k);
            Kind kind = kinds.get(k);
            String norm = raw.toLowerCase();
            if (kind == Kind.QUOTED) { out.add(new Word(Kind.QUOTED, raw, norm, null, false, null, out.size())); continue; }
            if (kind == Kind.SYMBOL) {
                if (norm.equals("%") && !out.isEmpty() && out.get(out.size() - 1).isNumber()) {
                    Word prev = out.remove(out.size() - 1);
                    out.add(new Word(Kind.NUMBER, prev.raw + "%", prev.norm, prev.number, false, "percent", prev.index));
                    continue;
                }
                out.add(new Word(Kind.SYMBOL, raw, norm, null, false, null, out.size()));
                continue;
            }
            Double num = number(norm);
            boolean ordinal = false;
            if (num == null && norm.matches("\\d+(st|nd|rd|th)")) { num = Double.parseDouble(norm.replaceAll("\\D", "")); ordinal = true; }
            if (num == null && ORDINAL_WORDS.containsKey(norm) && !norm.equals("first") && !norm.equals("second")) {
                num = (double) ORDINAL_WORDS.get(norm);
                ordinal = true;
            }
            if (num == null && NUMBER_WORDS.containsKey(norm)) num = (double) NUMBER_WORDS.get(norm);
            if (num != null) {
                String unit = null;
                // 1.5 lakh, 2 crore, 50k handled in number()
                if (k + 1 < pieces.size() && (pieces.get(k + 1).equalsIgnoreCase("lakh") || pieces.get(k + 1).equalsIgnoreCase("lakhs"))) {
                    num *= 100000; k++;
                } else if (k + 1 < pieces.size() && pieces.get(k + 1).equalsIgnoreCase("crore")) {
                    num *= 10000000; k++;
                } else if (k + 1 < pieces.size() && pieces.get(k + 1).equalsIgnoreCase("thousand")) {
                    num *= 1000; k++;
                }
                if (!out.isEmpty() && CURRENCY.contains(out.get(out.size() - 1).norm)) {
                    out.remove(out.size() - 1);
                    unit = "rupees";
                }
                if (k + 1 < pieces.size() && !pieces.get(k + 1).equals("₹") && CURRENCY.contains(pieces.get(k + 1).toLowerCase())) { unit = "rupees"; k++; }
                if (k + 1 < pieces.size() && (pieces.get(k + 1).equalsIgnoreCase("percent") || pieces.get(k + 1).equalsIgnoreCase("percentage"))) { unit = "percent"; k++; }
                out.add(new Word(Kind.NUMBER, raw, norm, num, ordinal, unit, out.size()));
            } else {
                out.add(new Word(Kind.WORD, raw, norm, null, false, null, out.size()));
            }
        }
        // re-number positions
        List<Word> fixed = new ArrayList<>();
        for (Word w : out) fixed.add(new Word(w.kind, w.raw, w.norm, w.number, w.ordinal, w.unit, fixed.size()));
        return fixed;
    }

    static Double number(String s) {
        String t = s.replace(",", "");
        try {
            if (t.matches("\\d+(\\.\\d+)?")) return Double.parseDouble(t);
            if (t.matches("\\d+(\\.\\d+)?k")) return Double.parseDouble(t.substring(0, t.length() - 1)) * 1000;
        } catch (NumberFormatException ignored) { }
        return null;
    }

    /** Light stemming: students -> student, classes -> class, cities -> city, marks -> mark. */
    public static String stem(String w) {
        if (w.length() <= 3) return w;
        if (w.endsWith("ies") && w.length() > 4) return w.substring(0, w.length() - 3) + "y";
        if (w.endsWith("sses") || w.endsWith("shes") || w.endsWith("ches") || w.endsWith("xes")) return w.substring(0, w.length() - 2);
        if (w.endsWith("ss") || w.endsWith("us") || w.endsWith("is")) return w;
        if (w.endsWith("s")) return w.substring(0, w.length() - 1);
        return w;
    }

    /** Stems every word of a phrase: "fee payments" -> ["fee", "payment"]. */
    public static List<String> stems(String phrase) {
        List<String> out = new ArrayList<>();
        for (Word w : tokenize(phrase)) out.add(w.kind == Kind.NUMBER ? w.norm : w.stem);
        return out;
    }
}
