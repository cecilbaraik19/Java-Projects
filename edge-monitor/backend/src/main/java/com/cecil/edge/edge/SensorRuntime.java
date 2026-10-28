package com.cecil.edge.edge;

import java.util.HashMap;
import java.util.Map;

/** Live, in-memory state of one sensor on its edge node (not saved to disk). */
public class SensorRuntime {
    public static final int FLAG_KEPT = 0;     // processed at the edge, not sent
    public static final int FLAG_SENT = 1;     // sent to the cloud as a raw reading
    public static final int FLAG_INVALID = 2;  // impossible value, dropped at the edge

    /** Last raw readings: {time, value, flag}. 720 readings = 1 hour at one reading every 5 s. */
    public final Ring raw = new Ring(720);
    /** Window summaries sent to the cloud: {windowStart, min, max, avg, count}. */
    public final Ring summaries = new Ring(240);
    /** One row per minute: {minuteStart, count, min, max, sum}. 360 minutes = 6 hours. */
    public final Ring minutes = new Ring(360);
    public final RollingStats stats = new RollingStats(120);
    public final ModeLogic logic = new ModeLogic();
    /** rule id -> time the rule's condition first became true (for "must last N seconds"). */
    public final Map<String, Long> since = new HashMap<>();

    // hour being summed up (saved as an HourAgg when the hour ends)
    public long hourStart = -1;
    public long hourN;
    public double hourMin;
    public double hourMax;
    public double hourSum;

    // used by the simulator
    public long lastSampleTs;
    public double walk;

    public void addMinute(long ts, double v) {
        long m = ts - Math.floorMod(ts, 60_000L);
        double[] last = minutes.last();
        if (last != null && last[0] == m) {
            last[1]++;
            last[2] = Math.min(last[2], v);
            last[3] = Math.max(last[3], v);
            last[4] += v;
        } else {
            minutes.add(m, 1, v, v, v);
        }
    }

    /** Value about a minute ago (for rate of change), or null. */
    public double[] aboutOneMinuteAgo(long ts) {
        double[] best = null;
        for (double[] r : raw.all()) {
            if (r[2] == FLAG_INVALID) continue;
            if (ts - r[0] >= 55_000) best = r;  // keep the newest one that is at least 55 s old
            else break;
        }
        return best;
    }
}
