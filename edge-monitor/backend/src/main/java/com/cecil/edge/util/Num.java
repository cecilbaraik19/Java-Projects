package com.cecil.edge.util;

/** Small number helpers. */
public final class Num {
    private Num() { }

    public static double round(double v, int places) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0;
        double p = Math.pow(10, places);
        return Math.round(v * p) / p;
    }

    public static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** a / b as a percent (0 when b is 0). */
    public static double pct(double a, double b) {
        return b <= 0 ? 0 : a * 100.0 / b;
    }
}
