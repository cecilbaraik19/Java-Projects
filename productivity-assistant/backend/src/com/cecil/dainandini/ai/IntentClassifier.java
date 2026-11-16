package com.cecil.dainandini.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * INTENT CLASSIFIER for the chat box – a MULTINOMIAL NAIVE BAYES model trained on example sentences.
 *
 *   P(intent | words)  ∝  P(intent) × Π P(word | intent)
 *   P(word | intent)   =  (count of word in that intent's examples + α) ÷ (all words of the intent + α × vocabulary size)
 *                         (α = 0.1 "additive smoothing", so a word never seen with an intent does not make the
 *                          probability zero – a small α trusts the examples more than α = 1 would)
 *
 * Words AND word pairs ("what next", "free time") are used as features. Logarithms are added instead of
 * multiplying tiny numbers. The scores are turned into percentages (softmax). When the best intent is below 45 %,
 * or most of the meaningful words in the question were never seen in training ("what is the capital of France?"),
 * the assistant says it is not sure (and may ask Claude, if switched on).
 */
public final class IntentClassifier {
    public static final double SURE = 0.45;
    public static final double ALPHA = 0.1;
    /** Small words that say nothing about the intent (used only for the "do I know these words?" check). */
    static final java.util.Set<String> FILLER = java.util.Set.of("what", "is", "the", "of", "a", "an", "to", "i", "my", "me", "do",
            "how", "can", "you", "please", "are", "for", "in", "on", "at", "and", "it", "this", "that", "be", "should", "will", "with");

    public record Guess(String intent, double confidence, Map<String, Double> all) { }

    static final Map<String, String[]> EXAMPLES = new LinkedHashMap<>();
    static {
        EXAMPLES.put("WHAT_NOW", new String[] { "what should i do now", "what next", "what do i work on", "what should i work on now",
                "what is my next task", "next task", "what now", "where should i start", "which task first", "what is most important right now",
                "i am free what should i do", "suggest a task", "pick a task for me", "most important thing" });
        EXAMPLES.put("PLAN_DAY", new String[] { "plan my day", "make a plan for today", "plan tomorrow", "show my plan", "schedule my day",
                "how does my day look", "time block my day", "plan my tomorrow", "what is my plan today", "organise my day",
                "build my schedule", "show today's schedule", "what does tomorrow look like", "organise my afternoon", "plan my afternoon",
                "plan the rest of my day", "plan my morning", "what is on my calendar today" });
        EXAMPLES.put("FREE_TIME", new String[] { "how much free time do i have", "when am i free", "free time today", "do i have time for a meeting",
                "free slots tomorrow", "how busy am i", "any free time this week", "when is my next free slot", "how much time is left today",
                "do i have time today", "how many hours are free", "is my calendar full tomorrow", "is my day full", "am i busy tomorrow",
                "do i have a gap this afternoon" });
        EXAMPLES.put("OVERDUE", new String[] { "what is overdue", "show overdue tasks", "what did i miss", "late tasks", "what is due today",
                "deadlines this week", "which tasks are due", "upcoming deadlines", "what is due soon", "am i late on anything",
                "show deadlines", "what is at risk" });
        EXAMPLES.put("ADD_TASK", new String[] { "add a task", "remind me to call the bank", "add send invoice tomorrow", "create a task",
                "new task review contract friday", "remember to book tickets", "add buy printer paper", "put on my list email ravi",
                "note down to pay rent", "remind me to submit report by monday" });
        EXAMPLES.put("WEEK_SUMMARY", new String[] { "summarise my week", "how was my week", "weekly review", "what did i finish this week",
                "show my progress", "how productive was i", "my week in numbers", "what did i get done", "week report", "how am i doing" });
        EXAMPLES.put("FOCUS", new String[] { "when do i focus best", "my best focus hours", "how much did i focus today", "focus time today",
                "how many focus sessions", "peak hours", "when am i most productive", "deep work time", "how long did i focus",
                "start a focus session", "focus stats" });
        EXAMPLES.put("MEETINGS", new String[] { "how many meetings do i have", "meeting load this week", "too many meetings",
                "how much time in meetings", "next meeting", "when is my next meeting", "meetings today", "busiest day of the week",
                "show my meetings", "meeting hours" });
        EXAMPLES.put("ESTIMATES", new String[] { "how good are my estimates", "do i underestimate", "how long do my tasks take",
                "estimate accuracy", "am i good at estimating", "how much longer do tasks take", "my estimation bias",
                "why does the plan add time" });
        EXAMPLES.put("MOVE", new String[] { "move the report to friday", "postpone the deck to tomorrow", "push budget review to next week",
                "reschedule the slides to monday", "do the invoice later", "delay the review to thursday", "move it to tomorrow",
                "shift the presentation to next monday", "snooze the newsletter" });
        EXAMPLES.put("DONE", new String[] { "mark the report as done", "i finished the slides", "done with the invoice", "complete the budget task",
                "i have completed the review", "tick off email ravi", "finished the deck", "close the logo task", "the newsletter is done" });
        EXAMPLES.put("HELP", new String[] { "help", "help me", "what can you do", "how do you work", "what can i ask", "show commands", "how to use this",
                "who are you", "what are you" });
        EXAMPLES.put("GREETING", new String[] { "hi", "hello", "good morning", "hey there", "thanks", "thank you", "good evening", "namaste", "ok thanks", "great thank you" });
    }

    private final Map<String, Map<String, Integer>> counts = new LinkedHashMap<>();
    private final Map<String, Integer> totals = new HashMap<>();
    private final Map<String, Integer> docs = new HashMap<>();
    private final Map<String, Boolean> vocab = new HashMap<>();
    private int allDocs;

    public IntentClassifier() {
        for (Map.Entry<String, String[]> e : EXAMPLES.entrySet()) for (String s : e.getValue()) train(e.getKey(), s);
    }

    public void train(String intent, String sentence) {
        Map<String, Integer> c = counts.computeIfAbsent(intent, k -> new HashMap<>());
        for (String f : features(sentence)) {
            c.merge(f, 1, Integer::sum);
            totals.merge(intent, 1, Integer::sum);
            vocab.put(f, true);
        }
        docs.merge(intent, 1, Integer::sum);
        allDocs++;
    }

    public Guess classify(String sentence) {
        List<String> f = features(sentence);
        Map<String, Double> logp = new LinkedHashMap<>();
        double best = Double.NEGATIVE_INFINITY;
        int known = 0, knownContent = 0, unknownContent = 0;
        for (String x : f) {
            if (vocab.containsKey(x)) known++;
            if (x.contains("_") || FILLER.contains(x)) continue;
            if (vocab.containsKey(x)) knownContent++; else unknownContent++;
        }
        for (String intent : counts.keySet()) {
            double lp = Math.log(docs.get(intent) / (double) allDocs);
            Map<String, Integer> c = counts.get(intent);
            int total = totals.getOrDefault(intent, 0);
            for (String x : f) {
                if (!vocab.containsKey(x)) continue;          // unknown words tell us nothing
                lp += Math.log((c.getOrDefault(x, 0) + ALPHA) / (total + ALPHA * vocab.size()));
            }
            logp.put(intent, lp);
            best = Math.max(best, lp);
        }
        double sum = 0;
        Map<String, Double> prob = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : logp.entrySet()) { double p = Math.exp(e.getValue() - best); prob.put(e.getKey(), p); sum += p; }
        String top = null; double topP = -1;
        for (Map.Entry<String, Double> e : prob.entrySet()) {
            double p = e.getValue() / sum;
            e.setValue(Math.round(p * 1000) / 1000.0);
            if (p > topP) { topP = p; top = e.getKey(); }
        }
        if (known == 0 || unknownContent > knownContent) return new Guess("UNKNOWN", 0, prob);
        return new Guess(top, Math.round(topP * 1000) / 1000.0, prob);
    }

    /** Lower-case words (with a light "s" stem) plus pairs of neighbouring words. */
    static List<String> features(String s) {
        List<String> words = new ArrayList<>();
        for (String w : s.toLowerCase(Locale.ROOT).replace("'", "").split("[^a-z0-9]+")) {
            if (w.isEmpty()) continue;
            if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) w = w.substring(0, w.length() - 1);
            words.add(w);
        }
        List<String> out = new ArrayList<>(words);
        for (int i = 0; i + 1 < words.size(); i++) out.add(words.get(i) + "_" + words.get(i + 1));
        return out;
    }
}
