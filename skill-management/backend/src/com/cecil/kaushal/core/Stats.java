package com.cecil.kaushal.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Median and percentiles for the reports. */
public final class Stats {
    private Stats() { }

    /** p-th percentile (0..100) with linear interpolation between the two nearest values. NaN for an empty list. */
    public static double percentile(List<? extends Number> values, double p) {
        if (values.isEmpty()) return Double.NaN;
        List<Double> v = new ArrayList<>();
        for (Number n : values) v.add(n.doubleValue());
        Collections.sort(v);
        double pos = (p / 100.0) * (v.size() - 1);
        int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
        return v.get(lo) + (v.get(hi) - v.get(lo)) * (pos - lo);
    }

    public static double median(List<? extends Number> values) { return percentile(values, 50); }

    public static double round1(double d) { return Double.isNaN(d) ? d : Math.round(d * 10) / 10.0; }
}
