package com.cecil.mail.ai;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Multinomial Naive Bayes text classifier written from scratch in plain Java.
 *
 * Features: every word plus every pair of neighbouring words (bigrams).
 *
 * Idea in one line: for each intent, count how often every word appears in its training
 * emails; for a new email, the intent whose words "match best" (highest probability) wins.
 *
 *   P(intent | words) is proportional to  P(intent) * product of P(word | intent)
 *
 * Laplace smoothing (+1) stops unseen words from making a probability zero.
 * We add logs instead of multiplying tiny numbers, so nothing underflows to 0.
 * The model can keep learning: every time a user rates a reply, that email is added.
 */
public class NaiveBayesClassifier {

    private final Map<String, Map<String, Integer>> wordCounts = new HashMap<>();
    private final Map<String, Integer> totalWords = new HashMap<>();
    private final Map<String, Integer> docCounts = new HashMap<>();
    private final Set<String> vocabulary = new HashSet<>();
    private int totalDocs = 0;

    /** Teach the model one example. */
    public synchronized void train(String text, String label) {
        List<String> tokens = features(text);
        Map<String, Integer> counts = wordCounts.computeIfAbsent(label, k -> new HashMap<>());
        for (String t : tokens) {
            counts.merge(t, 1, Integer::sum);
            vocabulary.add(t);
        }
        totalWords.merge(label, tokens.size(), Integer::sum);
        docCounts.merge(label, 1, Integer::sum);
        totalDocs++;
    }

    /** Returns every label with its probability (0..1), highest first. */
    public synchronized Map<String, Double> predict(String text) {
        List<String> tokens = features(text);
        Map<String, Double> logScores = new HashMap<>();
        int v = vocabulary.size();

        for (String label : docCounts.keySet()) {
            double score = Math.log((double) docCounts.get(label) / totalDocs);   // prior
            Map<String, Integer> counts = wordCounts.get(label);
            int total = totalWords.get(label);
            for (String t : tokens) {
                int c = counts.getOrDefault(t, 0);
                score += Math.log((c + 1.0) / (total + v));                      // likelihood
            }
            logScores.put(label, score);
        }
        return softmax(logScores);
    }

    /**
     * Features = single words + word pairs (bigrams).
     * "money back" as one feature means more than "money" and "back" separately.
     */
    static List<String> features(String text) {
        List<String> words = TextTokenizer.tokenize(text);
        List<String> out = new java.util.ArrayList<>(words);
        for (int i = 0; i + 1 < words.size(); i++) out.add(words.get(i) + "_" + words.get(i + 1));
        return out;
    }

    /** Turns log scores into normal probabilities that add up to 1. */
    private Map<String, Double> softmax(Map<String, Double> logScores) {
        double max = logScores.values().stream().mapToDouble(d -> d).max().orElse(0);
        double sum = 0;
        Map<String, Double> exp = new HashMap<>();
        for (var e : logScores.entrySet()) {
            double x = Math.exp(e.getValue() - max);
            exp.put(e.getKey(), x);
            sum += x;
        }
        final double s = sum;
        Map<String, Double> sorted = new LinkedHashMap<>();
        exp.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .forEach(e -> sorted.put(e.getKey(), e.getValue() / s));
        return sorted;
    }

    public synchronized int getTrainingSize() { return totalDocs; }

    public synchronized int getVocabularySize() { return vocabulary.size(); }

    public synchronized Map<String, Integer> getExamplesPerLabel() { return new HashMap<>(docCounts); }
}
