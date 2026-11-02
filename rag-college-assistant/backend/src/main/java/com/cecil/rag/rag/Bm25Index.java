package com.cecil.rag.rag;

import com.cecil.rag.model.Chunk;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keyword search with BM25, the ranking formula used by search engines like Elasticsearch.
 *
 *   score(chunk) = sum over question words w of
 *        idf(w) * tf * (k1 + 1) / (tf + k1 * (1 - b + b * length / averageLength))
 *
 *   tf  = how many times w is in the chunk
 *   idf = log(1 + (N - n + 0.5) / (n + 0.5))   rare words count more than common ones
 *   k1  = 1.2  (extra copies of a word help less and less)
 *   b   = 0.75 (long chunks are not favoured just for being long)
 */
public final class Bm25Index {
    public static final double K1 = 1.2;
    public static final double B = 0.75;

    private final List<Chunk> chunks;
    private final Map<String, Map<Integer, Integer>> postings = new HashMap<>(); // word -> (chunk index -> tf)
    private final double avgLen;

    public Bm25Index(List<Chunk> chunks) {
        this.chunks = chunks;
        long total = 0;
        for (int i = 0; i < chunks.size(); i++) {
            List<String> toks = chunks.get(i).tokens;
            total += toks.size();
            for (String t : toks) postings.computeIfAbsent(t, k -> new HashMap<>()).merge(i, 1, Integer::sum);
        }
        avgLen = chunks.isEmpty() ? 1 : (double) total / chunks.size();
    }

    public double idf(String word) {
        int n = postings.getOrDefault(word, Map.of()).size();
        int N = chunks.size();
        return Math.log(1 + (N - n + 0.5) / (n + 0.5));
    }

    public int docFreq(String word) { return postings.getOrDefault(word, Map.of()).size(); }

    /** Scores every chunk. weights = question word -> weight (synonyms have 0.5). */
    public double[] score(Map<String, Double> weights) {
        double[] s = new double[chunks.size()];
        for (Map.Entry<String, Double> e : weights.entrySet()) {
            Map<Integer, Integer> p = postings.get(e.getKey());
            if (p == null) continue;
            double idf = idf(e.getKey());
            for (Map.Entry<Integer, Integer> pe : p.entrySet()) {
                int i = pe.getKey();
                double tf = pe.getValue();
                double len = chunks.get(i).tokens.size();
                s[i] += e.getValue() * idf * tf * (K1 + 1) / (tf + K1 * (1 - B + B * len / avgLen));
            }
        }
        return s;
    }

    public int vocabularySize() { return postings.size(); }
    public double averageLength() { return avgLen; }
}
