package com.cecil.swikriti.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Median and percentiles (with linear interpolation, like Excel's PERCENTILE.INC). */
public final class Stats {
    private Stats() { }

    public static double percentile(List<Double> values, double p) {
        if (values.isEmpty()) return Double.NaN;
        List<Double> v = new ArrayList<>(values);
        Collections.sort(v);
        double pos = (v.size() - 1) * p;
        int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
        return v.get(lo) + (v.get(hi) - v.get(lo)) * (pos - lo);
    }

    public static double median(List<Double> values) { return percentile(values, 0.5); }

    public static double round1(double d) { return Double.isNaN(d) ? Double.NaN : Math.round(d * 10) / 10.0; }
}
