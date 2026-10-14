package com.cecil.complaints.ai;

import com.cecil.complaints.model.Complaint;
import com.cecil.complaints.model.Complaint.Recommendation;
import com.cecil.complaints.model.Enums.Status;
import com.cecil.complaints.model.Solution;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The "brain" of the assistant. One call to {@link #analyze} runs the whole AI pipeline:
 *
 *   complaint text
 *     -> sentiment + emotions        (SentimentAnalyzer)
 *     -> category                    (ComplaintClassifier, Naive Bayes)
 *     -> keywords                    (TF-IDF)
 *     -> duplicate check             (TF-IDF cosine vs. same customer's open complaints)
 *     -> priority + escalation       (PriorityEngine)
 *     -> top solutions               (SolutionRecommender)
 *     -> draft reply                 (ResponseGenerator, optionally polished by LlmClient)
 */
public class AiEngine {

    public static final int TOP_N = 3;
    public static final double DUPLICATE_THRESHOLD = 0.45;

    private final SentimentAnalyzer sentimentAnalyzer = new SentimentAnalyzer();
    private final ComplaintClassifier classifier = new ComplaintClassifier();
    private final PriorityEngine priorityEngine = new PriorityEngine();
    private final SolutionRecommender recommender = new SolutionRecommender();
    private final ResponseGenerator responseGenerator = new ResponseGenerator();
    private final LlmClient llm;

    public AiEngine(LlmClient llm) {
        this.llm = llm;
    }

    public ComplaintClassifier classifier() {
        return classifier;
    }

    public LlmClient llm() {
        return llm;
    }

    public ResponseGenerator responseGenerator() {
        return responseGenerator;
    }

    /** Learn from previously resolved complaints (called at start-up). */
    public void learnFrom(List<Complaint> complaints) {
        for (Complaint c : complaints) {
            if (c.status == Status.RESOLVED || c.status == Status.CLOSED) {
                classifier.train(c.fullText(), c.category);
            }
        }
    }

    /**
     * Run the full pipeline and write the results into the complaint.
     *
     * @param solutions knowledge base
     * @param others    all other complaints (for duplicates, repeat counts and similar cases)
     * @return the priority result (it tells the caller whether to auto-escalate)
     */
    public PriorityEngine.Result analyze(Complaint c, List<Solution> solutions, List<Complaint> others,
                                         Map<String, Solution> solutionById, String agentName) {
        String text = c.fullText();

        SentimentAnalyzer.Result sentiment = sentimentAnalyzer.analyze(text);
        c.sentiment = sentiment.label();
        c.sentimentScore = sentiment.score();
        c.emotions = new ArrayList<>(sentiment.emotions());

        ComplaintClassifier.Prediction pred = classifier.predict(text);
        c.category = pred.category();
        c.categoryConfidence = pred.confidence();

        c.keywords = extractKeywords(text, others, 6);
        c.duplicateOf = findDuplicate(c, others);

        int repeats = countRecent(c, others, Duration.ofDays(30));
        PriorityEngine.Result pr = priorityEngine.evaluate(text, c.sentiment, c.category, c.customerTier,
                c.channel, repeats);
        c.priority = pr.priority();
        c.priorityReasons = new ArrayList<>(pr.reasons());

        List<Complaint> resolved = others.stream()
                .filter(o -> o.status == Status.RESOLVED || o.status == Status.CLOSED)
                .toList();
        c.recommendations = recommender.recommend(c, solutions, resolved, TOP_N);

        Solution top = c.recommendations.isEmpty() ? null : solutionById.get(c.recommendations.get(0).solutionId);
        c.draftResponse = responseGenerator.generate(c, top, agentName);
        return pr;
    }

    /** Draft a reply for a specific solution (used when the agent applies a different one). */
    public String draftFor(Complaint c, Solution s, String agentName, boolean useLlm) {
        String draft = responseGenerator.generate(c, s, agentName);
        if (useLlm && llm.isEnabled()) {
            String steps = s == null ? "Investigate and follow up." : String.join("\n", s.steps);
            return llm.polishReply(c.fullText(), steps, draft).orElse(draft);
        }
        return draft;
    }

    /** Analysis without saving anything - powers the live preview while typing. */
    public Map<String, Object> preview(String subject, String description, List<Solution> solutions) {
        Complaint tmp = new Complaint();
        tmp.id = "PREVIEW";
        tmp.subject = subject;
        tmp.description = description;
        Map<String, Solution> byId = new HashMap<>();
        for (Solution s : solutions) {
            byId.put(s.id, s);
        }
        analyze(tmp, solutions, List.of(), byId, null);
        ComplaintClassifier.Prediction pred = classifier.predict(tmp.fullText());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("category", tmp.category);
        m.put("categoryLabel", tmp.category.label);
        m.put("categoryConfidence", Math.round(tmp.categoryConfidence * 1000) / 1000.0);
        m.put("categoryProbabilities", pred.probabilities());
        Map<String, String> display = TextProcessor.displayMap(tmp.fullText());
        m.put("evidence", classifier.topEvidence(tmp.fullText(), tmp.category, 5).stream()
                .map(t -> display.getOrDefault(t, t)).toList());
        m.put("sentiment", tmp.sentiment);
        m.put("sentimentScore", Math.round(tmp.sentimentScore * 1000) / 1000.0);
        m.put("emotions", tmp.emotions);
        m.put("priority", tmp.priority);
        m.put("priorityReasons", tmp.priorityReasons);
        m.put("keywords", tmp.keywords);
        List<Object> recs = new ArrayList<>();
        for (Recommendation r : tmp.recommendations) {
            recs.add(r.toMap());
        }
        m.put("recommendations", recs);
        return m;
    }

    // ------------------------------------------------------------------ helpers

    /** Most important words in this complaint compared with all other complaints. */
    List<String> extractKeywords(String text, List<Complaint> others, int limit) {
        List<List<String>> corpus = new ArrayList<>();
        for (Complaint o : others) {
            corpus.add(TextProcessor.terms(o.fullText()));
        }
        List<String> terms = TextProcessor.terms(text);
        corpus.add(terms);
        TfIdf tfidf = new TfIdf(corpus);
        Map<String, Double> v = tfidf.vector(terms);

        Map<String, String> display = TextProcessor.displayMap(text);
        List<String> out = new ArrayList<>();
        v.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .forEach(e -> out.add(display.getOrDefault(e.getKey(), e.getKey())));
        return out;
    }

    /** Same customer (email or order) + very similar text + still open = probably a duplicate. */
    String findDuplicate(Complaint c, List<Complaint> others) {
        List<Complaint> candidates = new ArrayList<>();
        Instant since = Instant.now().minus(Duration.ofDays(14));
        for (Complaint o : others) {
            if (Objects.equals(o.id, c.id) || !o.status.isActive() || o.createdAt == null || o.createdAt.isBefore(since)) {
                continue;
            }
            boolean sameCustomer = (c.customerEmail != null && c.customerEmail.equalsIgnoreCase(o.customerEmail))
                    || (c.orderId != null && !c.orderId.isBlank() && c.orderId.equalsIgnoreCase(o.orderId));
            if (sameCustomer) {
                candidates.add(o);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        List<List<String>> corpus = new ArrayList<>();
        for (Complaint o : candidates) {
            corpus.add(TextProcessor.terms(o.fullText()));
        }
        List<String> mine = TextProcessor.terms(c.fullText());
        corpus.add(mine);
        TfIdf tfidf = new TfIdf(corpus);
        Map<String, Double> q = tfidf.vector(mine);
        String best = null;
        double bestSim = DUPLICATE_THRESHOLD;
        for (int i = 0; i < candidates.size(); i++) {
            double sim = TfIdf.cosine(q, tfidf.vector(corpus.get(i)));
            if (sim >= bestSim) {
                bestSim = sim;
                best = candidates.get(i).id;
            }
        }
        return best;
    }

    static int countRecent(Complaint c, List<Complaint> others, Duration window) {
        if (c.customerEmail == null || c.customerEmail.isBlank()) {
            return 0;
        }
        Instant since = Instant.now().minus(window);
        int n = 0;
        for (Complaint o : others) {
            if (!Objects.equals(o.id, c.id) && c.customerEmail.equalsIgnoreCase(o.customerEmail)
                    && o.createdAt != null && o.createdAt.isAfter(since)) {
                n++;
            }
        }
        return n;
    }
}
