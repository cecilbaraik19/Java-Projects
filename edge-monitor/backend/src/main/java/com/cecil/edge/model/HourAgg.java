package com.cecil.edge.model;

/** One hour of a sensor's readings, summed up at the edge (kept for history charts). */
public class HourAgg {
    public String sensorId;
    public long hour;
    public long n;
    public double min;
    public double max;
    public double sum;

    public double avg() { return n == 0 ? 0 : sum / n; }
}
