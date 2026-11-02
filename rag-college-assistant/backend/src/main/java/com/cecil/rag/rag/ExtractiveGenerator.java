package com.cecil.rag.rag;

import com.cecil.rag.util.Text;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Step 3 of RAG, offline version: build the answer from the best sentences of the chosen chunks.
 * It never makes anything up - every sentence is copied from a college document and gets a [n] mark
 * that points to its source. No internet and no API key needed.
 *
 * Sentence score = 0.6 x word match (rare words count more; heading words count half)
 *                + 0.25 if it has the kind of value asked for (amount, date, time ...)
 *                + up to 0.15 for coming from a higher-ranked chunk
 */
public final class ExtractiveGenerator {

    /** A short sentence that starts like this continues the sentence before it ("The fee is ₹750 per paper."). */
    private static final Pattern FOLLOW_ON = Pattern.compile("^(?:The|This|It|They|These|That|Its|Their)\\b.*");
    private static final Pattern LIST_QUESTION = Pattern.compile("(?i)^(?:which|what are|list|name|show)\\b|\\ball the\\b");
    private static final Pattern ROW = Pattern.compile("^[^:;]{1,40}: [^;]+(?:; [^:;]{1,40}: [^;]+)+\\.?$");

    /** Table rows were turned into "Column: value; Column: value" by the Chunker. */
    static boolean isRow(String s) { return ROW.matcher(s).matches(); }

    private static final class Sentence {
        int source;        // 1-based source number
        String docId;
        int position;      // place inside the chunk
        String text;
        Set<String> tokens;
        double overlap;
        double score;
        List<String> continuation = new ArrayList<>();
    }

    public Answer generate(Retriever.Result r) {
        Answer a = new Answer();
        a.mode = "offline";
        List<Sentence> all = new ArrayList<>();
        double total = 0;
        for (String t : new LinkedHashSet<>(r.tokens)) total += idf(r, t);
        if (total == 0) total = 1;

        for (int n = 0; n < r.selected.size(); n++) {
            var chunk = r.selected.get(n).chunk;
            List<String> sents = Text.sentences(chunk.text);
            Set<String> heading = Text.tokenSet(chunk.doc.title + " " + chunk.section);
            int listEnd = -1;  // sentences up to here belong to a list that was already taken
            for (int p = 0; p < sents.size(); p++) {
                Sentence s = new Sentence();
                s.source = n + 1;
                s.docId = chunk.doc.id;
                s.position = p;
                s.text = sents.get(p);
                // a sentence that introduces a list ("To be eligible, a student needs:") brings the list with it
                if (s.text.endsWith(":")) {
                    for (int q = p + 1; q < Math.min(sents.size(), p + 6); q++) {
                        if (sents.get(q).endsWith(":")) break;
                        s.continuation.add(sents.get(q));
                    }
                    listEnd = p + s.continuation.size();
                } else if (p <= listEnd) {
                    continue; // list items are judged together with their intro
                } else if (p + 1 < sents.size()) {
                    String next = sents.get(p + 1);
                    if (Text.wordCount(next) <= 12 && FOLLOW_ON.matcher(next).matches()) s.continuation.add(next);
                }
                s.tokens = Text.tokenSet(s.text + " " + String.join(" ", s.continuation));
                s.overlap = overlap(r, s.tokens, heading, total);
                boolean typed = r.type.foundIn(s.text + " " + String.join(" ", s.continuation));
                double typeBonus = r.type == QuestionType.GENERAL ? 0 : (typed ? 0.25 : 0);
                double rankBonus = 0.15 * (1 - (double) n / Math.max(1, r.selected.size()));
                int words = Text.wordCount(s.text);
                double lengthFactor = words < 4 && s.continuation.isEmpty() ? 0.5 : words > 70 ? 0.8 : 1;
                s.score = (0.6 * s.overlap + typeBonus + rankBonus) * lengthFactor;
                all.add(s);
            }
        }
        if (all.isEmpty()) { a.found = false; return a; }

        all.sort(Comparator.comparingDouble((Sentence s) -> -s.score));
        Sentence best = all.get(0);

        // "Which subjects ...?" / "What are the ...?" and the best sentence is a table row -> give the whole table
        if (r.type == QuestionType.GENERAL && LIST_QUESTION.matcher(r.query).find() && isRow(best.text)) {
            String key = best.text.substring(0, best.text.indexOf(':'));
            List<String> rows = new ArrayList<>();
            for (String s : Text.sentences(r.selected.get(best.source - 1).chunk.text)) {
                if (isRow(s) && s.startsWith(key + ":")) rows.add(s.replaceAll("\\.$", ""));
            }
            if (rows.size() >= 2) {
                StringBuilder sb = new StringBuilder("From \"" + r.selected.get(best.source - 1).chunk.section + "\" [" + best.source + "]:");
                for (String row : rows.subList(0, Math.min(10, rows.size()))) sb.append("\n- ").append(row);
                a.text = sb.toString();
                a.confidence = Math.max(0, Math.min(0.98, 0.45 * Math.min(1, r.topScore() / 0.8) + 0.35 * best.overlap + 0.12));
                return a;
            }
        }
        List<Sentence> chosen = new ArrayList<>();
        chosen.add(best);
        for (int i = 1; i < all.size() && chosen.size() < 3; i++) {
            Sentence s = all.get(i);
            if (s.score < Math.max(0.25, best.score * 0.7)) break;
            if (s.overlap < Math.max(0.45, best.overlap * 0.75)) continue;   // must be about the same thing
            if (!s.docId.equals(best.docId) && (s.overlap < best.overlap * 0.95 || s.score < best.score * 0.85)) continue;
            boolean dup = false;
            for (Sentence c : chosen) {
                if (Text.dice(c.tokens, s.tokens) > 0.6 || c.continuation.contains(s.text) || s.continuation.contains(c.text)) dup = true;
            }
            if (!dup) chosen.add(s);
        }
        // keep the document's own order inside one source, so the answer reads naturally
        chosen.sort(Comparator.comparingInt((Sentence s) -> s.source).thenComparingInt(s -> s.position));
        if (chosen.get(0) != best && chosen.get(0).source != best.source) {
            chosen.remove(best);
            chosen.add(0, best);
        }

        StringBuilder sb = new StringBuilder();
        for (Sentence s : chosen) {
            boolean list = s.text.endsWith(":");
            if (sb.length() > 0) sb.append(list ? "\n" : " ");
            sb.append(withStop(s.text));
            if (!list) for (String c : s.continuation) sb.append(" ").append(withStop(c));
            sb.append(" [").append(s.source).append("]");
            if (list) {
                for (String c : s.continuation) sb.append("\n- ").append(c);
                sb.append("\n");
            }
        }
        a.text = sb.toString().strip();
        a.shortAnswer = shortAnswer(r.type, best.text + (best.continuation.isEmpty() ? "" : " " + String.join(" ", best.continuation)));
        // several chosen sentences each with their own value (e.g. weekday and Sunday timings): no single short answer
        long typed = chosen.stream().filter(s -> r.type.foundIn(s.text)).count();
        if (typed > 1 && best.continuation.isEmpty()) a.shortAnswer = "";

        double top = Math.min(1, r.topScore() / 0.8);
        double typePart = r.type == QuestionType.GENERAL ? 0.6 : (a.shortAnswer.isEmpty() ? 0.2 : 1);
        a.confidence = Math.max(0, Math.min(0.98, 0.45 * top + 0.35 * best.overlap + 0.2 * typePart));
        return a;
    }

    /**
     * For each word of the question: full credit (its IDF) if it is in the sentence, 70 % if a synonym is,
     * 50 % if it is only in the heading. Divided by the total, so the result is between 0 and 1.
     */
    private static double overlap(Retriever.Result r, Set<String> tokens, Set<String> heading, double total) {
        double got = 0;
        for (String t : new LinkedHashSet<>(r.tokens)) {
            double w = idf(r, t);
            if (tokens.contains(t)) { got += w; continue; }
            boolean syn = false;
            if (r.weights.size() > r.tokens.size()) {
                for (String s : Synonyms.groupOf(t)) if (tokens.contains(s)) { syn = true; break; }
            }
            if (syn) got += 0.7 * w;
            else if (heading.contains(t)) got += 0.5 * w;
        }
        return Math.min(1, got / total);
    }

    private static double idf(Retriever.Result r, String t) {
        Double v = r.idf.get(t);
        return v == null ? 1 : v;
    }

    private static String withStop(String s) {
        return s.matches(".*[.!?:)]$") ? s : s + ".";
    }

    /** Pulls the single value out of the best sentence, e.g. "₹38,000" or "9:00 AM – 5:00 PM". */
    static String shortAnswer(QuestionType type, String sentence) {
        if (type.pattern == null) return "";
        if (type == QuestionType.CONTACT) {
            String person = Text.firstMatch(Text.PERSON, sentence);
            if (person != null) return person;
        }
        Matcher m = type.pattern.matcher(sentence);
        Set<String> found = new LinkedHashSet<>();
        while (m.find() && found.size() < 2) {
            String v = m.group().strip().replaceAll("[,.]+$", "");
            if (type == QuestionType.NUMBER && v.matches("\\d{4}")) continue; // years are rarely the answer
            if (!v.isEmpty()) found.add(v);
        }
        if (found.isEmpty()) return "";
        if (type == QuestionType.NUMBER || type == QuestionType.CONTACT) return found.iterator().next();
        List<String> v = new ArrayList<>(found);
        // "9:00 AM to 5:00 PM" is a range; "9:00 PM for boys and 8:30 PM for girls" is two separate values
        boolean range = v.size() == 2 && Pattern.compile(Pattern.quote(v.get(0)) + "(?:[^.;]{0,14}?)\\s(?:to|until|till|-|–)\\s+(?:\\S+\\s){0,2}?"
                + Pattern.quote(v.get(1))).matcher(sentence).find();
        return String.join(range ? " – " : " / ", v);
    }
}
