package com.cecil.sanket.util;

import java.util.List;

/** Basic statistics used by the analytics (latency percentiles, averages). */
public final class Stats {
    private Stats() { }

    public static double mean(List<Double> xs) {
        if (xs.isEmpty()) return Double.NaN;
        double s = 0;
        for (double x : xs) s += x;
        return s / xs.size();
    }

    /** Standard deviation (sample, n-1). */
    public static double std(List<Double> xs) {
        if (xs.size() < 2) return 0;
        double m = mean(xs), s = 0;
        for (double x : xs) s += (x - m) * (x - m);
        return Math.sqrt(s / (xs.size() - 1));
    }

    /** z-score: how many standard deviations x is away from the mean. */
    public static double z(double x, double mean, double std) {
        return std <= 1e-9 ? 0 : (x - mean) / std;
    }

    public static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    public static double round1(double v) { return Math.round(v * 10) / 10.0; }

    public static double round2(double v) { return Math.round(v * 100) / 100.0; }

    /** Percentile with the "nearest rank" method: p = 95 gives the value that 95% of the numbers are at or below. */
    public static double percentile(List<Double> xs, double p) {
        if (xs.isEmpty()) return Double.NaN;
        List<Double> sorted = new java.util.ArrayList<>(xs);
        java.util.Collections.sort(sorted);
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }
}
