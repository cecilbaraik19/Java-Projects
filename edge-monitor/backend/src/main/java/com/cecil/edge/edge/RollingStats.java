package com.cecil.edge.edge;

/**
 * Mean and standard deviation of the last N values, kept in a small circular buffer.
 * Used for the z-score: how many standard deviations a new value is away from the recent average.
 *   z = (value - mean) / std      |z| >= 3 is already rare for normal data.
 */
public class RollingStats {
    private final double[] buf;
    private int count;
    private int next;
    private double sum;
    private double sumSq;

    public RollingStats(int size) {
        buf = new double[size];
    }

    public void add(double v) {
        if (count == buf.length) {
            double old = buf[next];
            sum -= old;
            sumSq -= old * old;
        } else {
            count++;
        }
        buf[next] = v;
        next = (next + 1) % buf.length;
        sum += v;
        sumSq += v * v;
    }

    public int count() { return count; }

    public double mean() { return count == 0 ? 0 : sum / count; }

    public double std() {
        if (count < 2) return 0;
        double m = mean();
        double var = (sumSq - count * m * m) / (count - 1);
        return var <= 0 ? 0 : Math.sqrt(var);
    }

    /** z-score of v against the values seen so far; null when there is not enough history yet. */
    public Double z(double v, int minCount) {
        if (count < minCount) return null;
        double sd = std();
        if (sd < 1e-9) return v == mean() ? 0.0 : null; // flat signal: no meaningful z
        return (v - mean()) / sd;
    }
}
