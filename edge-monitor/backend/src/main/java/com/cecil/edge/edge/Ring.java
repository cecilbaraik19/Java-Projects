package com.cecil.edge.edge;

import java.util.ArrayList;
import java.util.List;

/**
 * A fixed-size list that forgets its oldest rows (a ring buffer). Each row is a double[].
 * Used for the last few hundred raw readings and per-minute numbers kept in memory only.
 */
public class Ring {
    private final double[][] rows;
    private int count;
    private int next;

    public Ring(int size) {
        rows = new double[size][];
    }

    public void add(double... row) {
        rows[next] = row;
        next = (next + 1) % rows.length;
        if (count < rows.length) count++;
    }

    public int size() { return count; }

    /** Newest row, or null. */
    public double[] last() {
        if (count == 0) return null;
        return rows[(next - 1 + rows.length) % rows.length];
    }

    /** Oldest to newest. */
    public List<double[]> all() {
        List<double[]> out = new ArrayList<>(count);
        int start = (next - count + rows.length) % rows.length;
        for (int i = 0; i < count; i++) out.add(rows[(start + i) % rows.length]);
        return out;
    }

    /** Rows whose first column (time) is at or after `since`. */
    public List<double[]> since(long since) {
        List<double[]> out = new ArrayList<>();
        for (double[] r : all()) if (r[0] >= since) out.add(r);
        return out;
    }

    public void clear() {
        count = 0;
        next = 0;
    }
}
