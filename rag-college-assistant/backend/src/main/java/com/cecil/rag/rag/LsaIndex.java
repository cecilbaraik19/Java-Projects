package com.cecil.rag.rag;

import com.cecil.rag.model.Chunk;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * "Meaning" search with vectors, built in plain Java (no AI library, no internet).
 *
 * Method: LSA (Latent Semantic Analysis).
 *  1. Make a big table: one row per word, one column per chunk, value = TF-IDF weight.
 *  2. Squeeze it to d "topics" (d = 48) with a randomized SVD. Words that often appear together
 *     (hostel / warden / mess / room) end up pointing the same way.
 *  3. Every chunk becomes a list of d numbers (its vector). The question is turned into a vector the same way.
 *  4. Similarity = cosine of the angle between two vectors (1 = same direction, 0 = unrelated).
 *
 * This is the same idea as "embeddings" in a vector database, only smaller and fully explainable.
 */
public final class LsaIndex {
    private final Map<String, Integer> vocab = new HashMap<>();
    private final double[] idf;
    private final double[][] basis;   // d rows, each of vocabulary size (orthonormal)
    private final int dims;

    public LsaIndex(List<Chunk> chunks, int wantedDims, long seed) {
        int n = chunks.size();
        // 1. vocabulary: words in at least 2 chunks (a word in only one chunk cannot link anything)
        Map<String, Integer> df = new HashMap<>();
        for (Chunk c : chunks) for (String t : c.tokenSet) df.merge(t, 1, Integer::sum);
        for (Map.Entry<String, Integer> e : df.entrySet()) {
            if (e.getValue() >= 2 && e.getValue() <= Math.max(2, n * 0.6)) vocab.put(e.getKey(), vocab.size());
        }
        int v = vocab.size();
        idf = new double[v];
        for (Map.Entry<String, Integer> e : vocab.entrySet()) idf[e.getValue()] = Math.log((double) (n + 1) / (df.get(e.getKey()) + 1)) + 1;

        // 2. sparse TF-IDF columns, one per chunk, length-normalised
        List<int[]> colIdx = new ArrayList<>();
        List<double[]> colVal = new ArrayList<>();
        for (Chunk c : chunks) {
            Map<Integer, Double> col = weights(c.tokens, null);
            colIdx.add(col.keySet().stream().mapToInt(Integer::intValue).toArray());
            colVal.add(col.values().stream().mapToDouble(Double::doubleValue).toArray());
        }

        dims = Math.max(1, Math.min(wantedDims, Math.min(v, n)));
        basis = new double[dims][v];
        if (v == 0 || n == 0) { assign(chunks); return; }

        // 3. randomized SVD (range finder): Y = A * Omega, then power iterations Y = A * (A^T * Y)
        Random rnd = new Random(seed);
        double[][] y = new double[dims][v];
        for (int k = 0; k < dims; k++) {
            double[] omega = new double[n];
            for (int j = 0; j < n; j++) omega[j] = rnd.nextGaussian();
            y[k] = times(colIdx, colVal, omega, v);
        }
        orthonormalize(y);
        for (int iter = 0; iter < 3; iter++) {
            for (int k = 0; k < dims; k++) {
                double[] z = transposeTimes(colIdx, colVal, y[k], n); // A^T y
                y[k] = times(colIdx, colVal, z, v);                    // A z
            }
            orthonormalize(y);
        }
        for (int k = 0; k < dims; k++) basis[k] = y[k];
        assign(chunks);
    }

    private void assign(List<Chunk> chunks) {
        for (Chunk c : chunks) c.vector = project(weights(c.tokens, null));
    }

    /** Turns a question (word -> weight) into a vector. */
    public double[] queryVector(Map<String, Double> queryWeights) {
        List<String> toks = new ArrayList<>(queryWeights.keySet());
        return project(weights(toks, queryWeights));
    }

    private Map<Integer, Double> weights(List<String> tokens, Map<String, Double> extra) {
        Map<Integer, Double> tf = new HashMap<>();
        for (String t : tokens) {
            Integer i = vocab.get(t);
            if (i != null) tf.merge(i, extra == null ? 1.0 : extra.getOrDefault(t, 1.0), Double::sum);
        }
        double norm = 0;
        for (Map.Entry<Integer, Double> e : tf.entrySet()) {
            double w = Math.log(1 + e.getValue()) * idf[e.getKey()];
            e.setValue(w);
            norm += w * w;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) for (Map.Entry<Integer, Double> e : tf.entrySet()) e.setValue(e.getValue() / norm);
        return tf;
    }

    private double[] project(Map<Integer, Double> col) {
        double[] out = new double[dims];
        for (int k = 0; k < dims; k++) {
            double s = 0;
            for (Map.Entry<Integer, Double> e : col.entrySet()) s += basis[k][e.getKey()] * e.getValue();
            out[k] = s;
        }
        normalize(out);
        return out;
    }

    public static double cosine(double[] a, double[] b) {
        if (a == null || b == null) return 0;
        double s = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) s += a[i] * b[i];
        return s; // both are unit length already
    }

    public int dims() { return dims; }
    public int vocabularySize() { return vocab.size(); }

    // ------------------------------------------------------------------ small linear algebra

    private static double[] times(List<int[]> idx, List<double[]> val, double[] x, int rows) {
        double[] out = new double[rows];
        for (int j = 0; j < idx.size(); j++) {
            if (x[j] == 0) continue;
            int[] ii = idx.get(j);
            double[] vv = val.get(j);
            for (int t = 0; t < ii.length; t++) out[ii[t]] += vv[t] * x[j];
        }
        return out;
    }

    private static double[] transposeTimes(List<int[]> idx, List<double[]> val, double[] y, int cols) {
        double[] out = new double[cols];
        for (int j = 0; j < cols; j++) {
            int[] ii = idx.get(j);
            double[] vv = val.get(j);
            double s = 0;
            for (int t = 0; t < ii.length; t++) s += vv[t] * y[ii[t]];
            out[j] = s;
        }
        return out;
    }

    /** Gram-Schmidt: makes the rows unit length and at right angles to each other. */
    private static void orthonormalize(double[][] rows) {
        for (int k = 0; k < rows.length; k++) {
            for (int p = 0; p < k; p++) {
                double dot = 0;
                for (int i = 0; i < rows[k].length; i++) dot += rows[k][i] * rows[p][i];
                for (int i = 0; i < rows[k].length; i++) rows[k][i] -= dot * rows[p][i];
            }
            if (!normalize(rows[k])) rows[k] = new double[rows[k].length];
        }
    }

    private static boolean normalize(double[] v) {
        double n = 0;
        for (double x : v) n += x * x;
        n = Math.sqrt(n);
        if (n < 1e-12) return false;
        for (int i = 0; i < v.length; i++) v[i] /= n;
        return true;
    }
}
