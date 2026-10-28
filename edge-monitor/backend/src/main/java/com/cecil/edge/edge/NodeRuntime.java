package com.cecil.edge.edge;

/** Live, in-memory numbers for one edge node (not saved to disk). */
public class NodeRuntime {
    /** Per minute: {minuteStart, raw, forwarded, rawBytes, fwdBytes, invalid}. */
    public final Ring traffic = new Ring(240);
    /** Per minute: {minuteStart, cpu, mem, temp, buffered}. */
    public final Ring health = new Ring(240);
    public boolean wasOnline;
    /** Recent average time to process one reading at the edge (ms). */
    public double edgeMsAvg;

    public double[] trafficRow(long ts) {
        long m = ts - Math.floorMod(ts, 60_000L);
        double[] last = traffic.last();
        if (last == null || last[0] != m) {
            last = new double[] {m, 0, 0, 0, 0, 0};
            traffic.add(last);
        }
        return last;
    }

    public void healthPoint(long ts, double cpu, double mem, double temp, double buffered) {
        long m = ts - Math.floorMod(ts, 60_000L);
        double[] last = health.last();
        if (last != null && last[0] == m) {
            // keep the worst value seen in the minute
            last[1] = Math.max(last[1], cpu);
            last[2] = Math.max(last[2], mem);
            last[3] = Math.max(last[3], temp);
            last[4] = Math.max(last[4], buffered);
        } else {
            health.add(m, cpu, mem, temp, buffered);
        }
    }
}
