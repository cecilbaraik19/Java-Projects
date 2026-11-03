package com.cecil.mail.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Measures how good the intent classifier really is, on held-out test emails it never trained on.
 *
 *  accuracy   = correct / total
 *  precision  = of the emails we CALLED "Complaint", how many really were complaints
 *  recall     = of the emails that REALLY were complaints, how many we found
 *  F1         = balance of precision and recall = 2PR / (P + R)
 *  confusion  = table of [real intent][predicted intent] counts; the diagonal is correct
 *
 * Two test sets are measured (see TestEmails): the development set and the final, untouched set.
 */
public final class ModelEvaluator {

    private ModelEvaluator() { }

    public record PerIntent(String intent, String label, int support, double precision, double recall, double f1) { }

    public record Mistake(String expected, String predicted, double confidence, String text) { }

    /** One row of the "which method is best" table: accuracy on both test sets. */
    public record ModeResult(String mode, int devCorrect, int devTotal, double devAccuracy,
                             int finalCorrect, int finalTotal, double finalAccuracy, double finalMacroF1) { }

    /** Full details of the hybrid method on one test set. */
    public record SetResult(String id, String name, String note, int correct, int total, double accuracy,
                            List<PerIntent> perIntent, int[][] confusion, List<Mistake> mistakes) { }

    public record Report(List<ModeResult> comparison, List<String> labels, List<SetResult> sets, long millis) { }

    public static Report run(EmailAiEngine engine) {
        long start = System.currentTimeMillis();
        List<ModeResult> comparison = new ArrayList<>();
        for (EmailAiEngine.Mode mode : EmailAiEngine.Mode.values()) {
            SetResult dev = runSet(engine, mode, TestEmails.ALL, "dev", "", "");
            SetResult fin = runSet(engine, mode, TestEmails.FINAL, "final", "", "");
            comparison.add(new ModeResult(label(mode), dev.correct(), dev.total(), dev.accuracy(),
                    fin.correct(), fin.total(), fin.accuracy(), macroF1(fin.perIntent())));
        }
        List<SetResult> sets = List.of(
                runSet(engine, EmailAiEngine.Mode.HYBRID, TestEmails.FINAL, "final", "Final set",
                        "Written last and checked once. Nothing was changed after seeing this result, so this is the honest score."),
                runSet(engine, EmailAiEngine.Mode.HYBRID, TestEmails.ALL, "dev", "Development set",
                        "Used while building. Some cue words were added after studying its mistakes, so this score is optimistic."));
        List<String> labels = new ArrayList<>();
        for (EmailIntent i : EmailIntent.values()) labels.add(i.getLabel());
        return new Report(comparison, labels, sets, System.currentTimeMillis() - start);
    }

    static SetResult runSet(EmailAiEngine engine, EmailAiEngine.Mode mode, List<String[]> emails,
                            String id, String name, String note) {
        EmailIntent[] all = EmailIntent.values();
        int n = all.length;
        int[][] confusion = new int[n][n];
        List<Mistake> mistakes = new ArrayList<>();

        for (String[] t : emails) {
            EmailIntent expected = EmailIntent.valueOf(t[0]);
            Map<EmailIntent, Double> probs = engine.classify(t[1], t[2], mode);
            var best = probs.entrySet().iterator().next();
            confusion[expected.ordinal()][best.getKey().ordinal()]++;
            if (best.getKey() != expected) {
                mistakes.add(new Mistake(expected.getLabel(), best.getKey().getLabel(), round(best.getValue()), t[2]));
            }
        }

        List<PerIntent> per = new ArrayList<>();
        int correct = 0;
        for (EmailIntent i : all) {
            int k = i.ordinal();
            int tp = confusion[k][k];
            correct += tp;
            int predicted = 0, actual = 0;
            for (int j = 0; j < n; j++) { predicted += confusion[j][k]; actual += confusion[k][j]; }
            double p = predicted == 0 ? 0 : (double) tp / predicted;
            double r = actual == 0 ? 0 : (double) tp / actual;
            double f1 = p + r == 0 ? 0 : 2 * p * r / (p + r);
            per.add(new PerIntent(i.name(), i.getLabel(), actual, round(p), round(r), round(f1)));
        }
        return new SetResult(id, name, note, correct, emails.size(), round((double) correct / emails.size()), per, confusion, mistakes);
    }

    /** Accuracy of each method on the development set (used by the automated tests). */
    public static Map<String, Double> accuracyByMode(EmailAiEngine engine) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (EmailAiEngine.Mode mode : EmailAiEngine.Mode.values()) {
            m.put(mode.name(), runSet(engine, mode, TestEmails.ALL, "dev", "", "").accuracy());
        }
        return m;
    }

    private static double macroF1(List<PerIntent> per) {
        return round(per.stream().mapToDouble(PerIntent::f1).average().orElse(0));
    }

    private static String label(EmailAiEngine.Mode m) {
        return switch (m) {
            case NAIVE_BAYES -> "Naive Bayes only";
            case CUES -> "Cue words only";
            case HYBRID -> "Hybrid (used by the app)";
        };
    }

    private static double round(double d) { return Math.round(d * 1000) / 1000.0; }
}
