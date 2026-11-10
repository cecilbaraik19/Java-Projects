package com.cecil.parisar.ai;

import com.cecil.parisar.util.Json;
import com.cecil.parisar.util.Stats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * AI model: "Which students are at risk of failing?"  - Logistic regression, written by hand (no library).
 *
 * Inputs (features) for each student:
 *   attendance %  (from the RFID class taps)        internal marks %        assignments handed in %
 *   library visits in 30 days (from the library RFID gate)                  hours per week on the learning portal
 *
 * How it learns: it looks at last year's batch (made-up history with the result PASS / FAIL known) and finds one
 * weight per feature with gradient descent, so that  P(fail) = sigmoid(b + w1*x1 + ... + w5*x5)  fits the history.
 * Features are standardised first (z = (x - mean) / std) so every weight is on the same scale.
 * 25% of the history is kept aside (never used for learning) to measure accuracy honestly.
 *
 * Explanation: each feature's push = weight * z. The biggest pushes towards "fail" are the reasons shown.
 */
public class RiskModel {
    public static final String[] FEATURES = {"attendance", "internal", "assignments", "library", "lms"};
    public static final String[] LABELS = {"Attendance", "Internal marks", "Assignments done", "Library visits (30 days)", "Learning portal hours / week"};
    public static final String[] UNITS = {"%", "%", "%", "", " h"};

    private double[] mean = new double[5], std = new double[5], w = new double[5];
    private double bias;
    private Map<String, Object> report = Json.obj();
    private long trainedAt;

    /** Made-up history of last year's batch: 600 students with features and the known result (1 = failed). */
    public static List<double[]> history(long seed) {
        Random r = new Random(seed);
        List<double[]> rows = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            double habit = Stats.clamp(0.72 + r.nextGaussian() * 0.16, 0.25, 1.0);
            double att = Stats.clamp(habit * 100 + r.nextGaussian() * 6, 20, 100);
            double internal = Stats.clamp(30 + habit * 52 + r.nextGaussian() * 10, 5, 100);
            double assign = Stats.clamp(25 + habit * 70 + r.nextGaussian() * 12, 0, 100);
            double lib = Math.max(0, Math.round(habit * 9 + r.nextGaussian() * 2.5));
            double lms = Stats.clamp(habit * 6 + r.nextGaussian() * 1.4, 0, 14);
            // the "true" rule of this made-up world (unknown to the model, which has to discover it)
            double logit = 0.9 - 0.09 * (att - 75) - 0.07 * (internal - 55) - 0.025 * (assign - 70) - 0.12 * (lib - 5) - 0.18 * (lms - 3.5) - 2.2;
            double y = r.nextDouble() < Stats.sigmoid(logit) ? 1 : 0;
            rows.add(new double[]{att, internal, assign, lib, lms, y});
        }
        return rows;
    }

    /** Learns from the history and fills the report (accuracy etc. on the 25% kept aside). */
    public synchronized Map<String, Object> train(List<double[]> rows, int epochs, double learningRate, double l2) {
        long t0 = System.nanoTime();
        int split = (int) (rows.size() * 0.75);
        List<double[]> trainSet = rows.subList(0, split), testSet = rows.subList(split, rows.size());
        // 1. standardise with the training set's mean and std
        for (int j = 0; j < 5; j++) {
            List<Double> col = new ArrayList<>();
            for (double[] r : trainSet) col.add(r[j]);
            mean[j] = Stats.mean(col);
            std[j] = Math.max(Stats.std(col), 1e-6);
        }
        // 2. batch gradient descent on the log-loss
        w = new double[5];
        bias = 0;
        List<Double> lossCurve = new ArrayList<>();
        int n = trainSet.size();
        for (int e = 0; e < epochs; e++) {
            double[] gw = new double[5];
            double gb = 0, loss = 0;
            for (double[] r : trainSet) {
                double[] z = scale(r);
                double p = Stats.sigmoid(bias + dot(z));
                double err = p - r[5];
                for (int j = 0; j < 5; j++) gw[j] += err * z[j];
                gb += err;
                loss += -(r[5] * Math.log(p + 1e-12) + (1 - r[5]) * Math.log(1 - p + 1e-12));
            }
            for (int j = 0; j < 5; j++) w[j] -= learningRate * (gw[j] / n + l2 * w[j]);
            bias -= learningRate * gb / n;
            if (e % Math.max(1, epochs / 20) == 0 || e == epochs - 1) lossCurve.add(Stats.round2(loss / n));
        }
        // 3. measure on the test set
        int tp = 0, fp = 0, tn = 0, fn = 0;
        List<double[]> scored = new ArrayList<>();
        for (double[] r : testSet) {
            double p = probability(r);
            scored.add(new double[]{p, r[5]});
            boolean predFail = p >= 0.5, fail = r[5] == 1;
            if (predFail && fail) tp++; else if (predFail) fp++; else if (fail) fn++; else tn++;
        }
        double acc = (tp + tn) / (double) testSet.size();
        double prec = tp + fp == 0 ? 0 : tp / (double) (tp + fp);
        double rec = tp + fn == 0 ? 0 : tp / (double) (tp + fn);
        double f1 = prec + rec == 0 ? 0 : 2 * prec * rec / (prec + rec);
        trainedAt = System.currentTimeMillis();
        List<Object> weights = new ArrayList<>();
        for (int j = 0; j < 5; j++) weights.add(Json.obj("feature", FEATURES[j], "label", LABELS[j], "weight", Stats.round2(w[j]),
                "mean", Stats.round1(mean[j]), "std", Stats.round1(std[j])));
        long fails = rows.stream().filter(r -> r[5] == 1).count();
        report = Json.obj("algorithm", "Logistic regression (gradient descent, L2)", "trainRows", trainSet.size(), "testRows", testSet.size(),
                "failRate", Stats.round1(100.0 * fails / rows.size()), "epochs", epochs, "learningRate", learningRate, "l2", l2,
                "accuracy", Stats.round1(acc * 100), "precision", Stats.round1(prec * 100), "recall", Stats.round1(rec * 100),
                "f1", Stats.round1(f1 * 100), "auc", Stats.round1(auc(scored) * 100),
                "confusion", Json.obj("tp", tp, "fp", fp, "tn", tn, "fn", fn), "bias", Stats.round2(bias), "weights", weights,
                "loss", lossCurve, "trainedAt", trainedAt, "millis", (System.nanoTime() - t0) / 1_000_000);
        return report;
    }

    /** Area under the ROC curve: the chance that a random failing student gets a higher score than a random passing one. */
    static double auc(List<double[]> scored) {
        List<double[]> s = new ArrayList<>(scored);
        s.sort(Comparator.comparingDouble(x -> x[0]));
        double pos = 0, neg = 0, rankSum = 0;
        for (int i = 0; i < s.size(); i++) if (s.get(i)[1] == 1) { pos++; rankSum += i + 1; } else neg++;
        if (pos == 0 || neg == 0) return 0.5;
        return (rankSum - pos * (pos + 1) / 2) / (pos * neg);
    }

    private double[] scale(double[] r) {
        double[] z = new double[5];
        for (int j = 0; j < 5; j++) z[j] = (r[j] - mean[j]) / std[j];
        return z;
    }

    private double dot(double[] z) {
        double s = 0;
        for (int j = 0; j < 5; j++) s += w[j] * z[j];
        return s;
    }

    /** P(fail) for one student: features in the order of FEATURES. */
    public synchronized double probability(double[] x) {
        return Stats.sigmoid(bias + dot(scale(x)));
    }

    public static String band(double p) { return p >= 0.6 ? "HIGH" : p >= 0.3 ? "MEDIUM" : "LOW"; }

    /** Probability, band, the reasons (biggest pushes towards fail) and what to do about them. */
    public synchronized Map<String, Object> explain(double[] x) {
        double p = probability(x);
        double[] z = scale(x);
        List<Map<String, Object>> parts = new ArrayList<>();
        for (int j = 0; j < 5; j++) {
            double push = w[j] * z[j];     // > 0 pushes towards "fail"
            parts.add(Json.obj("feature", FEATURES[j], "label", LABELS[j], "value", Stats.round1(x[j]), "unit", UNITS[j],
                    "push", Stats.round2(push), "typical", Stats.round1(mean[j])));
        }
        parts.sort(Comparator.comparingDouble(m -> -((Number) m.get("push")).doubleValue()));
        List<String> reasons = new ArrayList<>(), actions = new ArrayList<>(), tips = new ArrayList<>();
        for (Map<String, Object> m : parts) {
            double push = ((Number) m.get("push")).doubleValue();
            if (push < 0.25 || reasons.size() >= 3) continue;
            String f = (String) m.get("feature");
            reasons.add("Low " + ((String) m.get("label")).toLowerCase() + ": " + m.get("value") + m.get("unit") + " (typical " + m.get("typical") + m.get("unit") + ")");
            actions.add(switch (f) {
                case "attendance" -> "Talk to the student about attending; inform parents if it stays under 75%.";
                case "internal" -> "Offer a remedial class or a doubt-clearing session before the next test.";
                case "assignments" -> "Agree a new deadline for the missing assignments.";
                case "library" -> "Suggest fixed self-study hours in the library.";
                default -> "Share the recorded lectures and practice quizzes on the learning portal.";
            });
            tips.add(switch (f) {          // the same advice, written for the student
                case "attendance" -> "Come to every class for the next few weeks - each class you attend lifts your percentage.";
                case "internal" -> "Ask your teacher about the remedial class and bring your doubts before the next test.";
                case "assignments" -> "Finish the missing assignments first - talk to your teacher about a new deadline.";
                case "library" -> "Fix one or two self-study hours in the library each day.";
                default -> "Watch the recorded lectures and try the practice quizzes on the learning portal.";
            });
        }
        if (reasons.isEmpty()) reasons.add("No weak area - keep it up.");
        return Json.obj("probability", Stats.round2(p), "riskPct", Math.round(p * 100), "band", band(p), "factors", parts,
                "reasons", reasons, "actions", actions, "tips", tips);
    }

    public synchronized Map<String, Object> report() { return report; }
}
