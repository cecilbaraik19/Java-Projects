package com.cecil.chatbot.knowledge;

import com.cecil.chatbot.model.KnowledgeDoc;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the parts of the knowledge base that match a question. This is the "R" in RAG
 * (Retrieval-Augmented Generation): we look up notes first, then give them to the AI with the question,
 * so it can answer from your own documents and say where the answer came from ([1], [2]).
 *
 * How it works:
 *   1. Every note is cut into chunks of about 700 characters (by paragraph).
 *   2. Each chunk is turned into words; very common words ("the", "is") are dropped.
 *   3. For a question we score every chunk with BM25 - the standard ranking formula used by search engines:
 *        a chunk scores higher when it has more of the question's words,
 *        rare words count more than common ones (IDF),
 *        and long chunks do not win just by being long.
 *   4. A chunk is used only if it has enough of the question's words (at least 1 in 3 of them)
 *      and a minimum score; the best ones are returned.
 */
public class Retriever {

    /** One piece of a note. */
    public record Chunk(String docId, String title, String text, List<String> words) { }

    /** A search result. n is the number used in citations: [1], [2] ... */
    public record Hit(int n, String docId, String title, String text, double score) { }

    static final int CHUNK_CHARS = 700;
    static final double K1 = 1.2;
    static final double B = 0.75;
    /** Chunks scoring below this are not related enough to the question. */
    public static final double MIN_SCORE = 0.5;

    private static final Set<String> STOP = Set.of(
            "a", "an", "the", "and", "or", "but", "if", "of", "to", "in", "on", "at", "for", "by", "with", "from", "as",
            "is", "are", "was", "were", "be", "been", "am", "do", "does", "did", "have", "has", "had", "it", "its", "this",
            "that", "these", "those", "there", "here", "what", "which", "who", "whom", "how", "why", "when", "where",
            "can", "could", "should", "would", "will", "shall", "may", "might", "must", "i", "me", "my", "we", "our",
            "you", "your", "he", "she", "they", "them", "their", "his", "her", "not", "no", "so", "than", "then", "too",
            "very", "just", "about", "into", "up", "down", "out", "over", "any", "all", "some", "tell", "please", "explain",
            "give", "get", "also", "more", "most", "much", "many", "us", "per", "each", "one", "s", "t");

    private List<Chunk> chunks = new ArrayList<>();
    private Map<String, Integer> docFreq = new HashMap<>();
    private double avgLen = 1;

    /** Rebuilds the index from all notes (fast: done after every change). */
    public synchronized void rebuild(List<KnowledgeDoc> docs) {
        List<Chunk> list = new ArrayList<>();
        for (KnowledgeDoc d : docs) {
            for (String piece : split(d.text)) {
                // the title is added to every chunk's words, so a question about the topic finds it
                List<String> words = words(d.title + " " + d.tags + " " + piece);
                if (!words.isEmpty()) list.add(new Chunk(d.id, d.title, piece, words));
            }
        }
        Map<String, Integer> df = new HashMap<>();
        long total = 0;
        for (Chunk c : list) {
            total += c.words().size();
            for (String w : new HashSet<>(c.words())) df.merge(w, 1, Integer::sum);
        }
        chunks = list;
        docFreq = df;
        avgLen = list.isEmpty() ? 1 : (double) total / list.size();
    }

    public synchronized int chunkCount() { return chunks.size(); }

    /** The best k chunks for the question (only those scoring at least MIN_SCORE). */
    public synchronized List<Hit> search(String question, int k) {
        List<String> q = new ArrayList<>(new LinkedHashSet<>(words(question)));
        List<Hit> out = new ArrayList<>();
        if (q.isEmpty() || chunks.isEmpty()) return out;
        int n = chunks.size();
        int needed = Math.max(1, (q.size() + 2) / 3);
        List<double[]> scored = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Chunk c = chunks.get(i);
            Map<String, Integer> tf = new HashMap<>();
            for (String w : c.words()) tf.merge(w, 1, Integer::sum);
            double score = 0;
            int matched = 0;
            for (String term : q) {
                Integer f = tf.get(term);
                if (f == null) continue;
                matched++;
                int df = docFreq.getOrDefault(term, 0);
                double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
                score += idf * (f * (K1 + 1)) / (f + K1 * (1 - B + B * c.words().size() / avgLen));
            }
            if (score >= MIN_SCORE && matched >= needed) scored.add(new double[] {i, score});
        }
        scored.sort(Comparator.comparingDouble((double[] a) -> a[1]).reversed());
        Set<String> seen = new HashSet<>();
        for (double[] s : scored) {
            Chunk c = chunks.get((int) s[0]);
            if (!seen.add(c.text())) continue;
            out.add(new Hit(out.size() + 1, c.docId(), c.title(), c.text(), Math.round(s[1] * 100) / 100.0));
            if (out.size() >= k) break;
        }
        return out;
    }

    /** Lower-case words without stop words. "Leaves" and "leave" count as the same word. */
    public static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String w : text.toLowerCase().split("[^\\p{L}\\p{N}]+")) {
            if (w.length() < 2 || STOP.contains(w)) continue;
            if (w.length() > 4 && w.endsWith("ies")) w = w.substring(0, w.length() - 3) + "y";
            else if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) w = w.substring(0, w.length() - 1);
            out.add(w);
        }
        return out;
    }

    /** Cuts text into chunks by paragraph, about CHUNK_CHARS each. Very long paragraphs are cut by sentence. */
    static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        StringBuilder cur = new StringBuilder();
        for (String para : text.replace("\r", "").split("\\n\\s*\\n")) {
            String p = para.trim();
            if (p.isEmpty()) continue;
            List<String> parts = new ArrayList<>();
            if (p.length() <= CHUNK_CHARS) {
                parts.add(p);
            } else {
                StringBuilder s = new StringBuilder();
                for (String sentence : p.split("(?<=[.!?])\\s+")) {
                    if (s.length() + sentence.length() > CHUNK_CHARS && s.length() > 0) {
                        parts.add(s.toString().trim());
                        s.setLength(0);
                    }
                    s.append(sentence).append(' ');
                }
                if (s.length() > 0) parts.add(s.toString().trim());
            }
            for (String part : parts) {
                if (cur.length() + part.length() > CHUNK_CHARS && cur.length() > 0) {
                    out.add(cur.toString().trim());
                    cur.setLength(0);
                }
                cur.append(part).append("\n\n");
            }
        }
        if (cur.length() > 0) out.add(cur.toString().trim());
        return out;
    }
}
