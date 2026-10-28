package com.cecil.energy.util;

/** Small number helpers. */
public final class Num {

    private Num() { }

    /** Rounds to the given number of decimal places (2 -> 12.35). */
    public static double round(double v, int places) {
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }

    public static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
