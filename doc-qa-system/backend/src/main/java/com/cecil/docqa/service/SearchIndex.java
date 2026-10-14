package com.cecil.docqa.service;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * In-memory search index using BM25 — the same ranking idea search engines use.
 * A chunk scores higher when it contains the question's words, especially rare words.
 */
@Component
public class SearchIndex {

    /** What the index stores for each chunk. */
    public record Entry(long chunkId, long documentId, Integer pageNumber, String text) {}

    public record Hit(Entry entry, double score, double coverage) {}

    private static final double K1 = 1.5;
    private static final double B = 0.75;

    private final Map<Long, Entry> entries = new HashMap<>();
    private final Map<Long, Map<String, Integer>> termFreqs = new HashMap<>();
    private final Map<Long, Integer> lengths = new HashMap<>();
    private final Map<String, Integer> docFreq = new HashMap<>();
    private long totalLength = 0;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public void add(Entry e) {
        Map<String, Integer> tf = new HashMap<>();
        List<String> tokens = Tokenizer.tokens(e.text());
        for (String t : tokens) tf.merge(t, 1, Integer::sum);

        lock.writeLock().lock();
        try {
            if (entries.containsKey(e.chunkId())) removeChunk(e.chunkId());
            entries.put(e.chunkId(), e);
            termFreqs.put(e.chunkId(), tf);
            lengths.put(e.chunkId(), tokens.size());
            totalLength += tokens.size();
            for (String term : tf.keySet()) docFreq.merge(term, 1, Integer::sum);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void removeDocument(long documentId) {
        lock.writeLock().lock();
        try {
            List<Long> ids = entries.values().stream()
                    .filter(e -> e.documentId() == documentId)
                    .map(Entry::chunkId).toList();
            ids.forEach(this::removeChunk);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void removeChunk(long chunkId) {
        entries.remove(chunkId);
        Map<String, Integer> tf = termFreqs.remove(chunkId);
        Integer len = lengths.remove(chunkId);
        if (len != null) totalLength -= len;
        if (tf != null) {
            for (String term : tf.keySet()) {
                docFreq.computeIfPresent(term, (k, v) -> v <= 1 ? null : v - 1);
            }
        }
    }

    public void clear() {
        lock.writeLock().lock();
        try {
            entries.clear();
            termFreqs.clear();
            lengths.clear();
            docFreq.clear();
            totalLength = 0;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return entries.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns the best matching chunks.
     *
     * @param documentIds only search these documents; empty or null means all documents
     */
    public List<Hit> search(String query, int topK, Collection<Long> documentIds) {
        Set<String> queryTerms = new LinkedHashSet<>(Tokenizer.tokens(query));
        if (queryTerms.isEmpty()) return List.of();
        boolean filter = documentIds != null && !documentIds.isEmpty();

        lock.readLock().lock();
        try {
            int n = entries.size();
            if (n == 0) return List.of();
            double avgLen = Math.max(1.0, (double) totalLength / n);

            List<Hit> hits = new ArrayList<>();
            for (Entry e : entries.values()) {
                if (filter && !documentIds.contains(e.documentId())) continue;
                Map<String, Integer> tf = termFreqs.get(e.chunkId());
                int len = lengths.get(e.chunkId());
                double score = 0;
                int matched = 0;
                for (String term : queryTerms) {
                    Integer f = tf.get(term);
                    if (f == null) continue;
                    matched++;
                    int df = docFreq.getOrDefault(term, 0);
                    double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
                    score += idf * (f * (K1 + 1)) / (f + K1 * (1 - B + B * len / avgLen));
                }
                if (score > 0) hits.add(new Hit(e, score, (double) matched / queryTerms.size()));
            }
            hits.sort(Comparator.comparingDouble(Hit::score).reversed());
            return hits.size() > topK ? new ArrayList<>(hits.subList(0, topK)) : hits;
        } finally {
            lock.readLock().unlock();
        }
    }
}
