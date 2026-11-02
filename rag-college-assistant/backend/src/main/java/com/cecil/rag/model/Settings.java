package com.cecil.rag.model;

import com.cecil.rag.util.Json;
import java.util.Map;

/** Knobs of the RAG pipeline. The admin can change them on the Settings page. */
public class Settings implements Json.JsonAware {
    public String collegeName = "Sarna Valley Institute of Technology";
    // chunking
    public int chunkWords = 110;        // target size of one chunk
    public int overlapWords = 25;       // words repeated from the previous chunk
    // retrieval
    public int candidatePool = 20;      // how many chunks each search method brings back
    public int topK = 4;                // how many chunks go into the answer
    public double minScore = 0.30;      // below this the assistant says "I could not find it"
    public double mmrLambda = 0.72;     // 1 = only relevance, 0 = only variety
    public boolean useSynonyms = true;
    public boolean useVectors = true;
    public boolean useRerank = true;
    public int vectorDims = 48;
    // generation
    public boolean useClaude = false;
    public String claudeModel = "claude-sonnet-5-5";
    public double verifiedMatch = 0.70; // how close a question must be to use a verified answer

    @Override
    public Map<String, Object> toJson() {
        return Json.map("collegeName", collegeName, "chunkWords", chunkWords, "overlapWords", overlapWords,
                "candidatePool", candidatePool, "topK", topK, "minScore", minScore, "mmrLambda", mmrLambda,
                "useSynonyms", useSynonyms, "useVectors", useVectors, "useRerank", useRerank,
                "vectorDims", vectorDims, "useClaude", useClaude, "claudeModel", claudeModel,
                "verifiedMatch", verifiedMatch);
    }

    public static Settings from(Map<String, Object> m) {
        Settings s = new Settings();
        s.apply(m);
        return s;
    }

    /** Copies known values from a map, keeping each inside a safe range. */
    public void apply(Map<String, Object> m) {
        collegeName = Json.str(m, "collegeName", collegeName).strip();
        if (collegeName.isEmpty() || collegeName.length() > 80) collegeName = "Sarna Valley Institute of Technology";
        chunkWords = clamp((int) Json.num(m, "chunkWords", chunkWords), 40, 400);
        overlapWords = clamp((int) Json.num(m, "overlapWords", overlapWords), 0, chunkWords / 2);
        candidatePool = clamp((int) Json.num(m, "candidatePool", candidatePool), 5, 60);
        topK = clamp((int) Json.num(m, "topK", topK), 1, 8);
        minScore = clampD(Json.dbl(m, "minScore", minScore), 0.05, 0.9);
        mmrLambda = clampD(Json.dbl(m, "mmrLambda", mmrLambda), 0, 1);
        useSynonyms = Json.bool(m, "useSynonyms", useSynonyms);
        useVectors = Json.bool(m, "useVectors", useVectors);
        useRerank = Json.bool(m, "useRerank", useRerank);
        vectorDims = clamp((int) Json.num(m, "vectorDims", vectorDims), 8, 128);
        useClaude = Json.bool(m, "useClaude", useClaude);
        String model = Json.str(m, "claudeModel", claudeModel).strip();
        if (model.matches("[a-z0-9.\\-]{3,60}")) claudeModel = model;
        verifiedMatch = clampD(Json.dbl(m, "verifiedMatch", verifiedMatch), 0.5, 1);
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static double clampD(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}
