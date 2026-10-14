package com.cecil.complaints.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * TF-IDF (Term Frequency - Inverse Document Frequency) vectors and cosine similarity.
 *
 * TF  = how often a word appears in this text.
 * IDF = log(total documents / documents containing the word) -> rare words matter more.
 * Cosine similarity = how close two vectors point, 0 (unrelated) to 1 (same).
 */
public class TfIdf {

    private final Map<String, Double> idf = new HashMap<>();
    private final double defaultIdf;

    /** Build IDF weights from a corpus (each document is a list of terms). */
    public TfIdf(List<List<String>> corpus) {
        Map<String, Integer> df = new HashMap<>();
        for (List<String> doc : corpus) {
            for (String t : new HashSet<>(doc)) {
                df.merge(t, 1, Integer::sum);
            }
        }
        int n = Math.max(1, corpus.size());
        for (Map.Entry<String, Integer> e : df.entrySet()) {
            idf.put(e.getKey(), Math.log((n + 1.0) / (e.getValue() + 1.0)) + 1.0);
        }
        // an unseen word is treated as rare (but not wildly so)
        defaultIdf = Math.log(n + 1.0) + 1.0;
    }

    public double idf(String term) {
        return idf.getOrDefault(term, defaultIdf);
    }

    /** L2-normalised TF-IDF vector. Uses 1 + log(tf) to stop repeated words dominating. */
    public Map<String, Double> vector(List<String> terms) {
        Map<String, Integer> tf = new HashMap<>();
        for (String t : terms) {
            tf.merge(t, 1, Integer::sum);
        }
        Map<String, Double> v = new HashMap<>();
        double norm = 0;
        for (Map.Entry<String, Integer> e : tf.entrySet()) {
            double w = (1 + Math.log(e.getValue())) * idf(e.getKey());
            v.put(e.getKey(), w);
            norm += w * w;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (Map.Entry<String, Double> e : v.entrySet()) {
                e.setValue(e.getValue() / norm);
            }
        }
        return v;
    }

    /** Cosine similarity of two normalised vectors = dot product. */
    public static double cosine(Map<String, Double> a, Map<String, Double> b) {
        Map<String, Double> small = a.size() <= b.size() ? a : b;
        Map<String, Double> large = small == a ? b : a;
        double dot = 0;
        for (Map.Entry<String, Double> e : small.entrySet()) {
            Double o = large.get(e.getKey());
            if (o != null) {
                dot += e.getValue() * o;
            }
        }
        return dot;
    }

    /** Terms shared by both vectors, strongest first (used to explain a match). */
    public static List<String> sharedTerms(Map<String, Double> a, Map<String, Double> b, int limit) {
        List<Map.Entry<String, Double>> shared = new ArrayList<>();
        for (Map.Entry<String, Double> e : a.entrySet()) {
            Double o = b.get(e.getKey());
            if (o != null) {
                shared.add(Map.entry(e.getKey(), e.getValue() * o));
            }
        }
        shared.sort((x, y) -> Double.compare(y.getValue(), x.getValue()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, shared.size()); i++) {
            String term = shared.get(i).getKey();
            if (!term.contains("_")) {
                out.add(term);
            }
        }
        return out;
    }
}
