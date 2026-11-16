package com.cecil.dainandini.ai;

import com.cecil.dainandini.model.User;
import com.cecil.dainandini.util.Json;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MEETING NOTES ANALYZER – reads rough meeting notes and finds:
 *
 *  • SUMMARY – the most important sentences (an "extractive" summary, the method of H. P. Luhn, 1958):
 *        1. count every meaningful word (stop words like "the", "is" are skipped);
 *        2. a word's weight = how often it appears ÷ how often the most common word appears;
 *        3. sentence score = sum of its word weights ÷ √(number of words)   (so long sentences do not always win)
 *                           + 0.3 for words like "decided", "deadline", "risk"   + 0.15 for the first two sentences;
 *        4. keep the best 30 % (1 to 5 sentences) in their original order.
 *  • DECISIONS – sentences with "decided", "agreed", "approved", "go with" ...
 *  • OPEN QUESTIONS – sentences that end with "?".
 *  • ACTION ITEMS – who does what by when:  "Ravi will send the deck by Thursday",  "Action: @neha update the logo",
 *    "I'll book the room",  "We need to renew the licence". The date words are turned into a real due date.
 */
public final class NoteAnalyzer {
    private NoteAnalyzer() { }

    public static final class Result {
        public final List<String> summary = new ArrayList<>();
        public final List<String> decisions = new ArrayList<>();
        public final List<String> questions = new ArrayList<>();
        public final List<Map<String, Object>> actions = new ArrayList<>();
    }

    static final Set<String> STOP = Set.of("the", "and", "for", "with", "that", "this", "are", "was", "were", "will", "would",
            "should", "could", "can", "has", "have", "had", "not", "but", "you", "your", "our", "out", "all", "any", "from",
            "they", "them", "their", "its", "it's", "into", "about", "also", "just", "than", "then", "there", "here", "what",
            "when", "who", "which", "been", "being", "some", "more", "most", "very", "need", "needs", "get", "got", "did",
            "does", "done", "let", "lets", "let's", "one", "two", "we'll", "i'll", "she", "his", "her", "him", "per", "via");
    static final Set<String> NOT_NAMES = Set.of("this", "that", "it", "we", "they", "he", "she", "which", "there", "everyone",
            "team", "nobody", "the", "who", "what", "everybody", "someone", "somebody", "you", "i", "these", "those", "next",
            "then", "also", "and", "but", "if", "so", "all", "both", "each", "release", "design", "marketing", "sales", "it'll");
    static final Pattern CUE = Pattern.compile("\\b(decided|agreed|approved|deadline|risk|blocked|budget|launch|priority|delay|deliver|final)\\w*", Pattern.CASE_INSENSITIVE);
    static final Pattern DECISION = Pattern.compile("^(?:decision|decided)\\s*[:\\-]|\\b(decided|agreed|approved|we will go with|we'll go with|go with|finali[sz]ed|signed off)\\b", Pattern.CASE_INSENSITIVE);
    static final Pattern A_PREFIX = Pattern.compile("^(?:action(?:\\s*item)?s?|todo|to do|ai|next steps?|follow[- ]?up)\\s*[:\\-]\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    static final Pattern A_NAME = Pattern.compile("^@?([A-Za-z][a-z]+)\\s+(will|to|should|must|needs to|is going to|has to|shall)\\s+(.+)$");
    static final Pattern A_NAME_COLON = Pattern.compile("^@?([A-Z][a-z]+)\\s*[:\\-]\\s*(.+)$");
    static final Pattern A_I = Pattern.compile("^(?:i\\s+will|i'll|i\\s+am going to|i\\s+need to|i\\s+have to)\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    static final Pattern A_WE = Pattern.compile("\\b(?:we need to|we have to|we must|someone needs to|someone has to|needs? to be)\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    static final Pattern A_CAN = Pattern.compile("^(?:[Cc]an|[Cc]ould)\\s+@?([A-Z][a-z]+)\\s+(?:please\\s+)?(.+?)\\??$");
    static final Pattern A_PLEASE = Pattern.compile("^please\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    static final Pattern MENTION = Pattern.compile("@([A-Za-z][\\w.]{0,30})");

    /**
     * text = the notes, author = who wrote them ("I will ..." belongs to them), at = when the meeting was
     * (dates like "Thursday" count from then), people = finds a person from a first name or username.
     */
    public static Result analyze(String text, User author, long at, Function<String, User> people) {
        Result r = new Result();
        List<String> sentences = sentences(text == null ? "" : text);
        if (sentences.isEmpty()) return r;

        // ---- summary (Luhn)
        Map<String, Integer> freq = new HashMap<>();
        List<List<String>> words = new ArrayList<>();
        for (String s : sentences) {
            List<String> w = contentWords(s);
            words.add(w);
            for (String x : new LinkedHashSet<>(w)) freq.merge(x, 1, Integer::sum);
        }
        int max = 1;
        for (int v : freq.values()) max = Math.max(max, v);
        double[] score = new double[sentences.size()];
        for (int i = 0; i < sentences.size(); i++) {
            List<String> w = words.get(i);
            if (w.size() < 3) { score[i] = -1; continue; }
            double sum = 0;
            for (String x : new LinkedHashSet<>(w)) sum += freq.get(x) / (double) max;
            score[i] = sum / Math.sqrt(w.size());
            if (CUE.matcher(sentences.get(i)).find()) score[i] += 0.3;
            if (i < 2) score[i] += 0.15;
        }
        int k = Math.max(1, Math.min(5, (int) Math.round(sentences.size() * 0.3)));
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < sentences.size(); i++) if (score[i] >= 0) order.add(i);
        order.sort((a, b) -> Double.compare(score[b], score[a]));
        List<Integer> keep = new ArrayList<>(order.subList(0, Math.min(k, order.size())));
        keep.sort(Integer::compare);
        for (int i : keep) r.summary.add(sentences.get(i));

        // ---- decisions, questions, actions
        Set<String> seen = new LinkedHashSet<>();
        for (String s : sentences) {
            if (s.endsWith("?") && !A_CAN.matcher(s).matches()) { r.questions.add(s); continue; }
            if (DECISION.matcher(s).find()) r.decisions.add(s.replaceFirst("(?i)^(decision|decided)\\s*[:\\-]\\s*", ""));
            Map<String, Object> a = action(s, author, at, people);
            if (a != null && seen.add(((String) a.get("text")).toLowerCase(Locale.ROOT))) r.actions.add(a);
        }
        return r;
    }

    /** Turns one sentence into an action item, or null when it is not one. */
    static Map<String, Object> action(String s, User author, long at, Function<String, User> people) {
        String what = null, ownerWord = null;
        boolean mine = false;
        Matcher m;
        if ((m = A_PREFIX.matcher(s)).matches()) {
            what = m.group(1);
            Matcher mm = MENTION.matcher(what);
            if (mm.find()) { ownerWord = mm.group(1); what = (what.substring(0, mm.start()) + what.substring(mm.end())).trim(); }
            else if ((mm = A_NAME.matcher(what)).matches() && nameOk(mm, people)) { ownerWord = mm.group(1); what = mm.group(3); }
            else if ((mm = A_NAME_COLON.matcher(what)).matches() && people.apply(mm.group(1)) != null && startsWithVerb(mm.group(2))) { ownerWord = mm.group(1); what = mm.group(2); }
        } else if ((m = A_I.matcher(s)).matches()) {
            what = m.group(1); mine = true;
        } else if ((m = A_CAN.matcher(s)).matches() && !NOT_NAMES.contains(m.group(1).toLowerCase(Locale.ROOT))) {
            ownerWord = m.group(1); what = m.group(2);
        } else if ((m = A_NAME.matcher(s)).matches() && nameOk(m, people)
                && (Character.isUpperCase(m.group(1).charAt(0)) || s.startsWith("@"))) {
            ownerWord = m.group(1); what = m.group(3);
        } else if ((m = A_NAME_COLON.matcher(s)).matches() && people.apply(m.group(1)) != null && startsWithVerb(m.group(2))) {
            ownerWord = m.group(1); what = m.group(2);
        } else if (s.startsWith("@") && (m = MENTION.matcher(s)).lookingAt()) {
            ownerWord = m.group(1); what = s.substring(m.end()).trim();
        } else if ((m = A_PLEASE.matcher(s)).matches()) {
            what = m.group(1);
        } else if ((m = A_WE.matcher(s)).find()) {
            what = m.group(1);
        }
        if (what == null || what.isBlank()) return null;

        StringBuilder sb = new StringBuilder(what);
        QuickCapture.When w = QuickCapture.findWhen(sb, author, at, true);
        String text = QuickCapture.tidy(sb.toString().replaceAll("[.!]+$", ""));
        if (text.split("\\s+").length < 2) return null;
        if (text.length() > 160) text = text.substring(0, 160).trim();
        long due = 0;
        if (w.hasAny()) {
            var date = w.date != null ? w.date : WorkTime.dateOf(at).plusDays(WorkTime.at(WorkTime.dateOf(at), Math.max(0, w.minute)) > at ? 0 : 1);
            due = WorkTime.at(date, w.minute >= 0 ? w.minute : author.workEnd);
        }
        User owner = mine ? author : ownerWord == null ? null : people.apply(ownerWord);
        String ownerName = owner != null ? owner.name : ownerWord == null ? null : QuickCapture.capital(ownerWord);
        return Json.obj("text", text, "ownerId", owner == null ? null : owner.id, "ownerName", ownerName, "due", due, "taskId", null);
    }

    /**
     * Is the first word of "Ravi will send ..." really a person? A known person always is. An unknown capital word
     * only with "will" and an active verb ("Budget will be approved" is not a person doing something).
     */
    static boolean nameOk(Matcher m, Function<String, User> people) {
        String name = m.group(1);
        if (NOT_NAMES.contains(name.toLowerCase(Locale.ROOT))) return false;
        if (people.apply(name) != null) return true;
        return m.group(2).equals("will") && !m.group(3).toLowerCase(Locale.ROOT).startsWith("be ");
    }

    static final Set<String> MORE_VERBS = Set.of("make", "fix", "move", "finish", "talk", "find", "get", "set", "add", "remove",
            "look", "organise", "organize", "arrange", "contact", "speak", "start", "complete", "clean", "draft", "decide", "own");

    /** "Meera: share the screenshots" is a job for Meera; "Meera: the form breaks" is only something Meera said. */
    static boolean startsWithVerb(String text) {
        String w = text.trim().toLowerCase(Locale.ROOT).split("[^a-z]+")[0];
        return QuickCapture.DEEP_VERBS.contains(w) || QuickCapture.SHALLOW_VERBS.contains(w) || MORE_VERBS.contains(w);
    }

    /** Splits notes into sentences. Every line of a list ("- ...", "* ...", "1. ...") is its own sentence. */
    public static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim().replaceFirst("^(?:[-*•–]+|\\d+[.)])\\s*", "").trim();
            if (line.isEmpty()) continue;
            for (String s : line.split("(?<=[.!?])\\s+(?=[A-Z@\"'(0-9])")) {
                String t = s.trim();
                if (t.length() >= 3) out.add(t);
            }
        }
        return out;
    }

    static List<String> contentWords(String s) {
        List<String> out = new ArrayList<>();
        for (String w : s.toLowerCase(Locale.ROOT).split("[^a-z0-9']+")) {
            String x = w.replaceAll("^'+|'+$", "");
            if (x.length() > 2 && !STOP.contains(x)) out.add(stem(x));
        }
        return out;
    }

    /** A very small stemmer: "launches", "launched", "launching" → "launch" (so they count as one word). */
    static String stem(String w) {
        if (w.length() > 5 && w.endsWith("ing")) return w.substring(0, w.length() - 3);
        if (w.length() > 4 && w.endsWith("ed")) return w.substring(0, w.length() - 2);
        if (w.length() > 4 && w.endsWith("es")) return w.substring(0, w.length() - 2);
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) return w.substring(0, w.length() - 1);
        return w;
    }
}
