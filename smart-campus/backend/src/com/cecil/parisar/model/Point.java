package com.cecil.parisar.model;

import com.cecil.parisar.util.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One point of a sensor's history: the average of every reading inside one time bucket.
 * (Fine history = 10-minute buckets for 24 hours; long history = 1-hour buckets for 14 days.)
 */
public class Point implements Json.Writable {
    public long t;                                   // bucket start, epoch millis
    public int n;                                    // how many readings were averaged
    public Map<String, Double> v = new LinkedHashMap<>();

    public Point() { }

    public Point(long t) { this.t = t; }

    /** Adds one reading to the running average. */
    public void add(Map<String, Double> values) {
        for (Map.Entry<String, Double> e : values.entrySet()) {
            Double old = v.get(e.getKey());
            v.put(e.getKey(), old == null || n == 0 ? e.getValue() : (old * n + e.getValue()) / (n + 1));
        }
        n++;
    }

    public double get(String key) {
        Double d = v.get(key);
        return d == null ? Double.NaN : d;
    }

    @Override
    public Map<String, Object> toJson() {
        Map<String, Object> rounded = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : v.entrySet()) rounded.put(e.getKey(), Math.round(e.getValue() * 100) / 100.0);
        return Json.obj("t", t, "n", n, "v", rounded);
    }

    public static Point fromJson(Map<String, Object> m) {
        Point p = new Point(Json.num(m, "t", 0));
        p.n = (int) Json.num(m, "n", 1);
        Object v = m.get("v");
        if (v instanceof Map<?, ?> vm) for (Map.Entry<?, ?> e : vm.entrySet())
            if (e.getValue() instanceof Number num) p.v.put(String.valueOf(e.getKey()), num.doubleValue());
        return p;
    }
}
