package com.cecil.sikho.match;

import com.cecil.sikho.util.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Text search with TF-IDF and cosine similarity, plus two helpers for how people really type:
 *
 *   1. Synonyms - "khana" finds cooking, "coding" finds programming, "silai" finds sewing (Hindi and English).
 *   2. Spelling mistakes - a query word that is not in the index is matched to the closest index word when the
 *      Levenshtein (edit) distance is at most 1 (words of 5+ letters) or 2 (words of 8+ letters):
 *      "gitar" -> "guitar", "photografy" -> "photography".
 *
 * TF-IDF:  weight(word, doc) = (count in doc / words in doc) × ln(1 + N / docs containing word)
 * Cosine:  sim(q, d) = (q · d) / (|q| |d|)   - 1 = same words in the same proportions, 0 = nothing in common.
 */
public class SearchIndex {
    /** Each key is replaced by its value words before searching (both in documents and in queries). */
    public static final Map<String, String> SYNONYMS = Map.ofEntries(
            Map.entry("khana", "cooking"), Map.entry("cook", "cooking"), Map.entry("recipe", "cooking"),
            Map.entry("silai", "sewing stitching"), Map.entry("stitch", "sewing stitching"), Map.entry("tailoring", "sewing stitching"),
            Map.entry("coding", "programming"), Map.entry("code", "programming"), Map.entry("program", "programming"),
            Map.entry("maths", "mathematics"), Map.entry("math", "mathematics"), Map.entry("ganit", "mathematics"),
            Map.entry("yog", "yoga"), Map.entry("gaana", "singing"), Map.entry("sing", "singing"), Map.entry("vocal", "singing"),
            Map.entry("angrezi", "english"), Map.entry("bangla", "bengali"), Map.entry("computer", "computer basics"),
            Map.entry("spreadsheet", "excel"), Map.entry("bike", "cycle bicycle"), Map.entry("cycle", "bicycle"),
            Map.entry("garden", "gardening"), Map.entry("plant", "gardening"), Map.entry("bagwani", "gardening"),
            Map.entry("drawing", "sketching"), Map.entry("paint", "painting"), Map.entry("chitrakala", "painting"),
            Map.entry("phone", "smartphone"), Map.entry("mobile", "smartphone"), Map.entry("photo", "photography"),
            Map.entry("camera", "photography"), Map.entry("workout", "fitness"), Map.entry("gym", "fitness"),
            Map.entry("wiring", "electrical"), Map.entry("bijli", "electrical"), Map.entry("tabla", "tabla percussion"),
            Map.entry("speaking", "spoken"), Map.entry("interview", "interview"), Map.entry("resume", "resume cv"));

    /** The same list with the keys stemmed like every other word ("coding" → "cod"), so lookups match. */
    private static final Map<String, String> STEMMED = new HashMap<>();
    static {
        for (Map.Entry<String, String> e : SYNONYMS.entrySet()) STEMMED.put(stemmed(e.getKey()), e.getValue());
    }

    private final Map<String, Map<String, Double>> vectors = new LinkedHashMap<>();
    private final Map<String, Double> norms = new HashMap<>();
    private final Map<String, Double> idf = new HashMap<>();
    private final Set<String> vocabulary = new HashSet<>();

    /** Builds the index. docs: id -> text. */
    public SearchIndex(Map<String, String> docs) {
        Map<String, List<String>> words = new LinkedHashMap<>();
        Map<String, Integer> df = new HashMap<>();
        for (Map.Entry<String, String> d : docs.entrySet()) {
            List<String> w = expand(Text.words(d.getValue()));
            words.put(d.getKey(), w);
            for (String x : new HashSet<>(w)) df.merge(x, 1, Integer::sum);
        }
        int n = Math.max(1, docs.size());
        for (Map.Entry<String, Integer> e : df.entrySet()) idf.put(e.getKey(), Math.log(1.0 + (double) n / e.getValue()));
        vocabulary.addAll(df.keySet());
        for (Map.Entry<String, List<String>> d : words.entrySet()) {
            Map<String, Double> v = weigh(d.getValue());
            vectors.put(d.getKey(), v);
            norms.put(d.getKey(), norm(v));
        }
    }

    /** Synonyms are added next to the original word (so "khana" also counts as "cooking"). */
    static List<String> expand(List<String> words) {
        List<String> out = new ArrayList<>();
        for (String w : words) {
            out.add(w);
            String s = STEMMED.get(w);
            if (s != null) for (String x : s.split(" ")) out.add(stemmed(x));
        }
        return out;
    }

    private static String stemmed(String w) {
        List<String> l = Text.words(w);
        return l.isEmpty() ? w : l.get(0);
    }

    private Map<String, Double> weigh(List<String> words) {
        Map<String, Double> tf = new HashMap<>();
        for (String w : words) tf.merge(w, 1.0, Double::sum);
        Map<String, Double> v = new HashMap<>();
        for (Map.Entry<String, Double> e : tf.entrySet()) {
            Double i = idf.get(e.getKey());
            if (i != null) v.put(e.getKey(), e.getValue() / words.size() * i);
        }
        return v;
    }

    private static double norm(Map<String, Double> v) {
        double s = 0;
        for (double x : v.values()) s += x * x;
        return Math.sqrt(s);
    }

    /** The query words after synonyms and spelling correction, plus the corrections made (for "Did you mean"). */
    public record Query(List<String> words, Map<String, String> corrected) { }

    public Query prepare(String query) {
        List<String> raw = Text.words(query);
        Map<String, String> corrected = new LinkedHashMap<>();
        List<String> fixed = new ArrayList<>();
        for (String w : raw) {
            if (vocabulary.contains(w) || STEMMED.containsKey(w) || w.length() < 5) { fixed.add(w); continue; }
            String best = null;
            int bestD = Integer.MAX_VALUE;
            int allowed = w.length() >= 8 ? 2 : 1;
            for (String v : vocabulary) {
                if (Math.abs(v.length() - w.length()) > allowed) continue;
                int d = levenshtein(w, v, allowed);
                if (d < bestD || (d == bestD && best != null && v.compareTo(best) < 0)) { bestD = d; best = v; }
            }
            if (best != null && bestD <= allowed) { fixed.add(best); corrected.put(w, best); }
            else fixed.add(w);
        }
        return new Query(expand(fixed), corrected);
    }

    /** Cosine similarity between the query and every document (only documents with a score above 0). */
    public Map<String, Double> scores(Query q) {
        Map<String, Double> out = new HashMap<>();
        if (q.words().isEmpty()) return out;
        Map<String, Double> qv = weigh(q.words());
        double qn = norm(qv);
        if (qn == 0) return out;
        for (Map.Entry<String, Map<String, Double>> d : vectors.entrySet()) {
            double dot = 0;
            for (Map.Entry<String, Double> e : qv.entrySet()) {
                Double x = d.getValue().get(e.getKey());
                if (x != null) dot += x * e.getValue();
            }
            if (dot > 0) out.put(d.getKey(), dot / (qn * norms.get(d.getKey())));
        }
        return out;
    }

    /**
     * Levenshtein distance: the fewest single-letter inserts, deletes or changes that turn a into b.
     * Dynamic programming, one row at a time. Stops early (returns limit + 1) once the answer must be above limit.
     */
    public static int levenshtein(String a, String b, int limit) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, cur[j]);
            }
            if (rowMin > limit) return limit + 1;
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }
}
