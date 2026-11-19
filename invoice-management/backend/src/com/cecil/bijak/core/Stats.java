package com.cecil.bijak.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Small statistics for the reports. */
public final class Stats {
    private Stats() { }

    /** The p-th percentile (0-100) with linear interpolation; null for an empty list. */
    public static Double percentile(List<? extends Number> values, double p) {
        if (values.isEmpty()) return null;
        List<Double> v = new ArrayList<>();
        for (Number n : values) v.add(n.doubleValue());
        Collections.sort(v);
        double pos = (p / 100.0) * (v.size() - 1);
        int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
        return v.get(lo) + (v.get(hi) - v.get(lo)) * (pos - lo);
    }

    public static Double median(List<? extends Number> values) { return percentile(values, 50); }

    /** Ageing bucket for days late: 0 = not due yet, 1 = 1-30, 2 = 31-60, 3 = 61-90, 4 = over 90. */
    public static int bucket(long daysLate) {
        if (daysLate <= 0) return 0;
        if (daysLate <= 30) return 1;
        if (daysLate <= 60) return 2;
        if (daysLate <= 90) return 3;
        return 4;
    }

    public static final String[] BUCKETS = { "Not due", "1-30 days", "31-60 days", "61-90 days", "Over 90 days" };
}
