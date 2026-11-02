package com.cecil.rag.rag;

import com.cecil.rag.model.Chunk;
import com.cecil.rag.model.Role;
import com.cecil.rag.model.Settings;
import com.cecil.rag.util.Json;
import com.cecil.rag.util.Text;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Step 2 of RAG: find the chunks that best answer the question.
 *
 *   question -> words (+ synonyms)
 *            -> [A] BM25 keyword search      (top 20)
 *            -> [B] LSA vector search        (top 20)
 *            -> Reciprocal Rank Fusion of A and B
 *            -> re-rank with 6 simple signals (coverage, BM25, cosine, closeness, heading, answer type)
 *            -> MMR: pick the top K that are good AND not repeating each other
 *
 * Every stage is written into a Trace so the "Press Room" page can show what happened.
 */
public final class Retriever {

    /** Which parts are switched on (the Evaluation page compares these). */
    public enum Mode { BM25, VECTOR, HYBRID, FULL }

    public static final int RRF_K = 60;
    public static final double MIN_COVERAGE = 0.45;

    // weights of the re-ranking signals (they add up to 1)
    public static final double W_COVER = 0.30, W_BM25 = 0.22, W_COS = 0.18, W_PROX = 0.10, W_HEAD = 0.10, W_TYPE = 0.10;

    public static final class Hit {
        public final Chunk chunk;
        public double bm25, cosine, rrf, coverage, proximity, heading, typeMatch, score;
        public int bm25Rank, vectorRank;
        Hit(Chunk c) { this.chunk = c; }

        public Map<String, Object> toJson() {
            return Json.map("chunkId", chunk.id, "docId", chunk.doc.id, "docTitle", chunk.doc.title,
                    "category", chunk.doc.category, "section", chunk.section, "snippet", Text.clip(chunk.text, 220),
                    "bm25", r(bm25), "cosine", r(cosine), "rrf", r(rrf * 1000), "coverage", r(coverage),
                    "proximity", r(proximity), "heading", r(heading), "typeMatch", r(typeMatch), "score", r(score),
                    "bm25Rank", bm25Rank, "vectorRank", vectorRank);
        }
    }

    public static final class Result {
        public String query;
        public List<String> tokens = new ArrayList<>();
        public Map<String, Double> weights = new LinkedHashMap<>();
        public Map<String, Double> idf = new HashMap<>();   // how rare each question word is (soft words x 0.3)
        public QuestionType type = QuestionType.GENERAL;
        public List<Hit> bm25 = new ArrayList<>();
        public List<Hit> vector = new ArrayList<>();
        public List<Hit> fused = new ArrayList<>();
        public List<Hit> ranked = new ArrayList<>();
        public List<Hit> selected = new ArrayList<>();
        public int searched;        // chunks this user may see
        public int hidden;          // chunks hidden by access rules
        public boolean answerable;
        public Map<String, Long> micros = new LinkedHashMap<>();
        public Mode mode;

        public double topScore() { return selected.isEmpty() ? (ranked.isEmpty() ? 0 : ranked.get(0).score) : selected.get(0).score; }

        public Map<String, Object> toJson() {
            List<Map<String, Object>> w = new ArrayList<>();
            weights.forEach((k, v) -> w.add(Json.map("word", k, "weight", v, "idf", r(idf.getOrDefault(k, 0.0)),
                    "soft", Text.SOFT.contains(k), "synonym", !tokens.contains(k))));
            return Json.map("query", query, "tokens", tokens, "weights", w, "questionType", type.name(),
                    "questionTypeLabel", type.label, "searched", searched, "hidden", hidden,
                    "bm25", top(bm25, 8), "vector", top(vector, 8), "fused", top(fused, 10),
                    "ranked", top(ranked, 10), "selected", top(selected, 8), "answerable", answerable,
                    "micros", micros, "mode", mode.name());
        }

        private static List<Map<String, Object>> top(List<Hit> hits, int n) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (int i = 0; i < Math.min(n, hits.size()); i++) out.add(hits.get(i).toJson());
            return out;
        }
    }

    private final KnowledgeIndex index;
    private final Settings s;

    public Retriever(KnowledgeIndex index, Settings settings) {
        this.index = index;
        this.s = settings;
    }

    public Result search(String query, Role role, String category, Mode mode) {
        Result r = new Result();
        r.mode = mode;
        r.query = query;
        long t = System.nanoTime();

        // 1. question words + synonyms
        r.tokens = Text.tokens(query);
        // words carried over from the previous question "(...)" help to find the topic, but count less
        java.util.Set<String> carried = new java.util.HashSet<>();
        int open = query.lastIndexOf(" (");
        if (open > 0 && query.endsWith(")")) {
            carried.addAll(Text.tokens(query.substring(open)));
            carried.removeAll(Text.tokens(query.substring(0, open)));
        }
        r.weights = Synonyms.expand(r.tokens, s.useSynonyms);
        for (String c : carried) r.weights.computeIfPresent(c, (k, v) -> Math.min(v, 0.6));
        r.type = QuestionType.of(query);
        t = lap(r, "words", t);

        // 2. which chunks may this person see?
        boolean[] allowed = new boolean[index.chunks.size()];
        for (int i = 0; i < allowed.length; i++) {
            Chunk c = index.chunks.get(i);
            boolean ok = role.canSee(c.doc.audience) && (category == null || category.isBlank() || c.doc.category.equals(category));
            allowed[i] = ok;
            if (ok) r.searched++;
            else if (!role.canSee(c.doc.audience)) r.hidden++;
        }
        if (r.tokens.isEmpty() || r.searched == 0) { r.answerable = false; return r; }

        Map<Chunk, Hit> hits = new HashMap<>();
        boolean useKeywords = mode != Mode.VECTOR;
        boolean useVectors = mode == Mode.VECTOR || ((mode == Mode.HYBRID || mode == Mode.FULL) && s.useVectors);
        boolean useRerank = mode == Mode.FULL && s.useRerank;

        // 3A. keyword search (BM25)
        double[] bm = index.bm25.score(r.weights);
        double maxBm = 0;
        for (int i = 0; i < bm.length; i++) if (allowed[i]) maxBm = Math.max(maxBm, bm[i]);
        if (useKeywords) {
            List<Integer> order = topIndexes(bm, allowed, s.candidatePool);
            int rank = 1;
            for (int i : order) {
                if (bm[i] <= 0) break;
                Hit h = hits.computeIfAbsent(index.chunks.get(i), Hit::new);
                h.bm25Rank = rank++;
                r.bm25.add(h);
            }
        }
        t = lap(r, "bm25", t);

        // 3B. vector search (LSA cosine)
        double[] qv = index.lsa.queryVector(r.weights);
        double[] cos = new double[index.chunks.size()];
        for (int i = 0; i < cos.length; i++) cos[i] = LsaIndex.cosine(qv, index.chunks.get(i).vector);
        if (useVectors) {
            List<Integer> order = topIndexes(cos, allowed, s.candidatePool);
            int rank = 1;
            for (int i : order) {
                if (cos[i] <= 0.05) break;
                Hit h = hits.computeIfAbsent(index.chunks.get(i), Hit::new);
                h.vectorRank = rank++;
                r.vector.add(h);
            }
        }
        t = lap(r, "vectors", t);

        // 4. Reciprocal Rank Fusion: 1/(60 + rank) from each list, added together
        for (Hit h : hits.values()) {
            int i = index.chunks.indexOf(h.chunk);
            h.bm25 = bm[i];
            h.cosine = Math.max(0, cos[i]);
            if (h.bm25Rank > 0) h.rrf += 1.0 / (RRF_K + h.bm25Rank);
            if (h.vectorRank > 0) h.rrf += 1.0 / (RRF_K + h.vectorRank);
        }
        r.fused = new ArrayList<>(hits.values());
        r.fused.sort(Comparator.comparingDouble((Hit h) -> -h.rrf));
        t = lap(r, "fusion", t);

        // 5. re-rank with simple signals that a person would also use
        Set<String> original = Set.copyOf(r.tokens);
        Map<String, Double> idf = r.idf;
        for (String w : r.weights.keySet()) idf.put(w, index.bm25.idf(w) * (Text.SOFT.contains(w) ? Text.SOFT_WEIGHT : 1) * (carried.contains(w) ? 0.4 : 1));
        for (Hit h : r.fused) {
            h.coverage = coverage(original, h.chunk.tokenSet, idf);
            h.proximity = proximity(original, h.chunk.tokens);
            h.heading = coverage(original, Set.copyOf(Text.tokens(h.chunk.doc.title + " " + h.chunk.section)), idf);
            h.typeMatch = r.type == QuestionType.GENERAL ? 0.5 : (r.type.foundIn(h.chunk.text) ? 1 : 0);
            double bmNorm = maxBm > 0 ? h.bm25 / maxBm : 0;
            h.score = W_COVER * h.coverage + W_BM25 * bmNorm + W_COS * h.cosine + W_PROX * h.proximity
                    + W_HEAD * h.heading + W_TYPE * h.typeMatch;
        }
        r.ranked = new ArrayList<>(r.fused);
        if (useRerank) r.ranked.sort(Comparator.comparingDouble((Hit h) -> -h.score));
        t = lap(r, "rerank", t);

        // 6. MMR: choose K chunks that are relevant but do not repeat each other
        r.selected = mmr(r.ranked, useRerank);
        // answerable only if the best chunk scores well AND contains the important (rare) question words.
        // "What are the gym timings?" matches "timings" everywhere, but no document has "gym" -> not answerable.
        r.answerable = !r.selected.isEmpty() && r.selected.get(0).score >= s.minScore
                && r.selected.get(0).coverage >= MIN_COVERAGE;
        lap(r, "mmr", t);
        return r;
    }

    private List<Hit> mmr(List<Hit> ranked, boolean byScore) {
        List<Hit> pool = new ArrayList<>(ranked.subList(0, Math.min(ranked.size(), 15)));
        List<Hit> chosen = new ArrayList<>();
        double floor = s.minScore * 0.7;
        while (chosen.size() < s.topK && !pool.isEmpty()) {
            Hit best = null;
            double bestVal = -1e9;
            for (int i = 0; i < pool.size(); i++) {
                Hit h = pool.get(i);
                double rel = byScore ? h.score : 1.0 - i / 20.0;  // without re-rank, keep the fused order
                double sim = 0;
                for (Hit c : chosen) sim = Math.max(sim, Text.jaccard(h.chunk.tokenSet, c.chunk.tokenSet));
                double val = s.mmrLambda * rel - (1 - s.mmrLambda) * sim;
                if (val > bestVal) { bestVal = val; best = h; }
            }
            pool.remove(best);
            if (!chosen.isEmpty() && best.score < floor) continue; // too weak to be a source
            chosen.add(best);
        }
        return chosen;
    }

    /**
     * Share of the question's words found in the chunk, weighted by IDF so rare words count more
     * (a synonym counts 70 %). A word that is in no document at all has the highest IDF,
     * so a question about something the college never wrote about gets low coverage.
     */
    static double coverage(Set<String> query, Set<String> chunkTokens, Map<String, Double> idf) {
        if (query.isEmpty()) return 0;
        double got = 0, total = 0;
        for (String q : query) {
            double w = idf.getOrDefault(q, 1.0);
            total += w;
            if (chunkTokens.contains(q)) { got += w; continue; }
            for (String syn : Synonyms.groupOf(q)) if (chunkTokens.contains(syn)) { got += 0.7 * w; break; }
        }
        return total == 0 ? 0 : got / total;
    }

    /** How close together the question words are in the chunk (1 = right next to each other). */
    static double proximity(Set<String> query, List<String> tokens) {
        Map<String, Integer> last = new HashMap<>();
        int matched = 0;
        for (String t : tokens) if (query.contains(t) && !last.containsKey(t)) { last.put(t, 0); matched++; }
        if (matched == 0) return 0;
        if (matched == 1) return 0.5;
        // smallest window that holds every matched word
        last.clear();
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (!query.contains(t)) continue;
            last.put(t, i);
            if (last.size() == matched) {
                int min = last.values().stream().min(Integer::compare).orElse(i);
                best = Math.min(best, i - min + 1);
            }
        }
        return Math.min(1.0, (double) matched / best * 1.5) * ((double) matched / query.size());
    }

    private static List<Integer> topIndexes(double[] scores, boolean[] allowed, int k) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < scores.length; i++) if (allowed[i]) idx.add(i);
        idx.sort((a, b) -> Double.compare(scores[b], scores[a]));
        return idx.subList(0, Math.min(k, idx.size()));
    }

    private static long lap(Result r, String name, long t) {
        long now = System.nanoTime();
        r.micros.put(name, (now - t) / 1000);
        return now;
    }

    static double r(double d) { return Math.round(d * 1000) / 1000.0; }
}
