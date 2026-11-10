package com.cecil.parisar.util;

import java.util.List;

/** Basic statistics used by the analytics and AI parts. */
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

    public static double sigmoid(double x) { return 1 / (1 + Math.exp(-x)); }
}
