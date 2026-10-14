package com.cecil.complaints.ai;

import com.cecil.complaints.model.Complaint;
import com.cecil.complaints.model.Complaint.Recommendation;
import com.cecil.complaints.model.Solution;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Recommends knowledge-base solutions for a complaint.
 *
 * Final score (0..1) mixes four signals:
 *   55%  text similarity   - TF-IDF cosine between complaint and the article
 *   25%  category match    - article category equals the predicted category
 *   10%  success rate      - how often agents marked this article "helpful"
 *   10%  similar past case - the article fixed a very similar resolved complaint
 *
 * This is a simple hybrid of content-based filtering and case-based reasoning.
 */
public class SolutionRecommender {

    public static final double W_TEXT = 0.55;
    public static final double W_CATEGORY = 0.25;
    public static final double W_SUCCESS = 0.10;
    public static final double W_CASE = 0.10;

    public List<Recommendation> recommend(Complaint complaint, List<Solution> solutions,
                                          List<Complaint> resolvedCases, int limit) {
        List<Solution> active = solutions.stream().filter(s -> s.active).toList();
        if (active.isEmpty()) {
            return new ArrayList<>();
        }

        // 1. build the TF-IDF space from articles + past cases
        List<List<String>> corpus = new ArrayList<>();
        List<List<String>> solTerms = new ArrayList<>();
        for (Solution s : active) {
            List<String> t = TextProcessor.termsWithBigrams(s.searchText());
            solTerms.add(t);
            corpus.add(t);
        }
        List<Complaint> cases = resolvedCases.stream()
                .filter(c -> c.appliedSolutionId != null && !c.id.equals(complaint.id))
                .filter(c -> c.satisfactionRating == null || c.satisfactionRating >= 3)
                .toList();
        List<List<String>> caseTerms = new ArrayList<>();
        for (Complaint c : cases) {
            List<String> t = TextProcessor.termsWithBigrams(c.fullText());
            caseTerms.add(t);
            corpus.add(t);
        }
        TfIdf tfidf = new TfIdf(corpus);
        Map<String, Double> query = tfidf.vector(TextProcessor.termsWithBigrams(complaint.fullText()));
        Map<String, String> display = TextProcessor.displayMap(complaint.fullText());

        // 2. find the best similar past case for each solution
        Map<String, double[]> bestCaseSim = new HashMap<>();
        Map<String, String> bestCaseId = new HashMap<>();
        for (int i = 0; i < cases.size(); i++) {
            Complaint c = cases.get(i);
            double sim = TfIdf.cosine(query, tfidf.vector(caseTerms.get(i)));
            double[] cur = bestCaseSim.get(c.appliedSolutionId);
            if (sim >= 0.25 && (cur == null || sim > cur[0])) {
                bestCaseSim.put(c.appliedSolutionId, new double[]{sim});
                bestCaseId.put(c.appliedSolutionId, c.id);
            }
        }

        // 3. score every article
        List<Recommendation> out = new ArrayList<>();
        for (int i = 0; i < active.size(); i++) {
            Solution s = active.get(i);
            Map<String, Double> sv = tfidf.vector(solTerms.get(i));
            double cos = TfIdf.cosine(query, sv);
            double textScore = Math.min(1.0, cos / 0.45); // a cosine of 0.45+ is already a very strong match
            boolean sameCategory = s.category == complaint.category;
            double categoryScore = sameCategory ? Math.max(0.5, complaint.categoryConfidence) : 0;
            double success = s.successRate();
            double caseScore = 0;
            double[] cs = bestCaseSim.get(s.id);
            if (cs != null) {
                caseScore = Math.min(1.0, cs[0] / 0.6);
            }

            double score = W_TEXT * textScore + W_CATEGORY * categoryScore + W_SUCCESS * success + W_CASE * caseScore;
            if (textScore < 0.08 && !sameCategory) {
                continue; // nothing in common
            }

            Recommendation r = new Recommendation();
            r.solutionId = s.id;
            r.title = s.title;
            r.score = Math.min(1.0, score);
            List<String> shared = TfIdf.sharedTerms(query, sv, 4).stream()
                    .map(t -> display.getOrDefault(t, t)).toList();
            if (!shared.isEmpty()) {
                r.reasons.add("Matches: " + String.join(", ", shared));
            }
            if (sameCategory) {
                r.reasons.add("Same category: " + s.category.label);
            }
            int votes = s.helpfulCount + s.notHelpfulCount;
            if (votes > 0) {
                r.reasons.add("Rated helpful " + Math.round(success * 100) + "% of the time (" + votes + " votes)");
            }
            if (cs != null) {
                r.reasons.add("Solved similar complaint " + bestCaseId.get(s.id));
            }
            out.add(r);
        }
        out.sort((a, b) -> Double.compare(b.score, a.score));
        return new ArrayList<>(out.subList(0, Math.min(limit, out.size())));
    }
}
