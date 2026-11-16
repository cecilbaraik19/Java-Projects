package com.cecil.dainandini.ai;

import com.cecil.dainandini.model.Task;
import com.cecil.dainandini.model.TaskStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ESTIMATE COACH – learns from finished tasks how long work REALLY takes this person.
 *
 *  1. Bias factor. For each finished task with an estimate:  ratio = actual minutes ÷ estimated minutes.
 *     The MEDIAN ratio is the person's factor (the median is not pulled away by one crazy task).
 *     With few tasks we do not trust it fully, so it is pulled towards 1.0 ("shrinkage"):
 *         factor = (n × median + 3 × 1.0) ÷ (n + 3)
 *     The same is done per #tag when there are at least 3 tasks with that tag.
 *
 *  2. Guess for a new task without an estimate: the 3 most similar finished tasks (same words in the title,
 *     Jaccard similarity) – their median real time. This is "k nearest neighbours" with k = 3.
 */
public final class EstimateCoach {
    public static final int PRIOR = 3;
    public static final int MIN_PER_TAG = 3;

    public record Factor(double value, int samples, double median) { }

    public record Model(Factor overall, Map<String, Factor> byTag) {
        /** The factor for a task: its tag factor when known (the most extreme one), else the overall one. */
        public double factorFor(Task t) {
            double best = overall.value();
            double bestGap = -1;
            for (String tag : t.tags) {
                Factor f = byTag.get(tag);
                if (f != null && Math.abs(f.value() - 1) > bestGap) { best = f.value(); bestGap = Math.abs(f.value() - 1); }
            }
            return best;
        }
    }

    public record Guess(int minutes, List<String> similarTitles) { }

    public static Model learn(Collection<Task> tasks) {
        List<Double> all = new ArrayList<>();
        Map<String, List<Double>> tags = new LinkedHashMap<>();
        for (Task t : tasks) {
            if (t.status != TaskStatus.DONE || t.estimateMin <= 0 || t.actualMin <= 0 || t.estimateGuessed) continue;
            double r = Math.max(0.2, Math.min(5.0, t.actualMin / (double) t.estimateMin));   // clip silly values
            all.add(r);
            for (String tag : t.tags) tags.computeIfAbsent(tag, k -> new ArrayList<>()).add(r);
        }
        Map<String, Factor> byTag = new LinkedHashMap<>();
        for (Map.Entry<String, List<Double>> e : tags.entrySet())
            if (e.getValue().size() >= MIN_PER_TAG) byTag.put(e.getKey(), shrink(e.getValue()));
        return new Model(shrink(all), byTag);
    }

    static Factor shrink(List<Double> ratios) {
        if (ratios.isEmpty()) return new Factor(1.0, 0, 1.0);
        double med = median(ratios);
        int n = ratios.size();
        double v = (n * med + PRIOR * 1.0) / (n + PRIOR);
        return new Factor(Math.round(v * 100) / 100.0, n, Math.round(med * 100) / 100.0);
    }

    public static double median(List<Double> xs) {
        List<Double> s = new ArrayList<>(xs);
        s.sort(Double::compare);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }

    /** Work still to do on a task, in minutes (at least 15). */
    public static int remaining(Task t, Model m) {
        int est = t.estimateMin > 0 ? t.estimateMin : 30;
        int corrected = (int) Math.round(est * (t.estimateGuessed ? 1.0 : m.factorFor(t)) / 5.0) * 5;
        return Math.max(15, corrected - t.actualMin);
    }

    /** k-nearest-neighbour guess of how long a new task takes; null when nothing is similar enough. */
    public static Guess guess(String title, List<String> tagsOfNew, Collection<Task> history) {
        Set<String> a = QuickCapture.words(title);
        if (a.isEmpty()) return null;
        record Near(Task t, double sim) { }
        List<Near> near = new ArrayList<>();
        for (Task t : history) {
            if (t.status != TaskStatus.DONE || t.actualMin <= 0) continue;
            Set<String> b = QuickCapture.words(t.title);
            double sim = jaccard(a, b);
            for (String tag : tagsOfNew) if (t.tags.contains(tag)) sim += 0.15;
            if (sim >= 0.34) near.add(new Near(t, sim));
        }
        if (near.isEmpty()) return null;
        near.sort((x, y) -> Double.compare(y.sim(), x.sim()));
        List<Double> mins = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < Math.min(3, near.size()); i++) {
            mins.add((double) near.get(i).t().actualMin);
            titles.add(near.get(i).t().title + " (" + QuickCapture.duration(near.get(i).t().actualMin) + ")");
        }
        int g = (int) Math.round(median(mins) / 5.0) * 5;
        return new Guess(Math.max(5, g), titles);
    }

    public static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int both = 0;
        for (String x : a) if (b.contains(x)) both++;
        return both / (double) (a.size() + b.size() - both);
    }
}
