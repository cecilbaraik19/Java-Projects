package com.cecil.edge.edge;

import com.cecil.edge.model.Enums.NodeMode;

/**
 * Decides, for ONE sensor, which messages leave the edge node for the cloud.
 * This is the heart of "processing IoT data at the edge".
 *
 * RAW       every reading is sent.
 * FILTER    dead-band filter: send only when the value moved at least `deadband` away from the last value sent.
 * AGGREGATE tumbling window: keep min / max / sum / count locally and send one summary when the window ends.
 * SMART     window summaries + any reading that jumped by 2 x dead-band + any reading that raised an alert.
 *
 * The same class is used by the live engine and by the "compare modes" replay, so both always agree.
 */
public class ModeLogic {
    /** Last value sent to the cloud (null = nothing sent yet). */
    Double lastSent;
    long winStart = -1;
    long winN;
    double winMin;
    double winMax;
    double winSum;
    double winLast;

    /** What one reading caused. */
    public static final class Out {
        public boolean rawSent;
        public int summaries;
        /** the summary that was closed (if any): windowStart, min, max, avg, count */
        public double[] summary;
    }

    /**
     * @param deadband    absolute dead-band in the sensor's unit
     * @param windowMs    window length in milliseconds
     * @param urgent      true when this reading raised an alert (SMART sends it at once)
     */
    public Out offer(NodeMode mode, long ts, double v, double deadband, long windowMs, boolean urgent) {
        Out out = new Out();
        switch (mode) {
            case RAW -> {
                out.rawSent = true;
                lastSent = v;
            }
            case FILTER -> {
                if (lastSent == null || Math.abs(v - lastSent) >= deadband) {
                    out.rawSent = true;
                    lastSent = v;
                }
            }
            case AGGREGATE -> window(out, ts, v, windowMs);
            case SMART -> {
                window(out, ts, v, windowMs);
                if (urgent || lastSent == null || Math.abs(v - lastSent) >= 2 * deadband) {
                    out.rawSent = true;
                    lastSent = v;
                }
            }
        }
        return out;
    }

    /** Tumbling window: close the old window (one summary message) when a reading falls outside it. */
    private void window(Out out, long ts, double v, long windowMs) {
        if (winStart >= 0 && ts >= winStart + windowMs) {
            if (winN > 0) {
                out.summaries = 1;
                out.summary = new double[] {winStart, winMin, winMax, winSum / winN, winN};
                lastSent = winLast;
            }
            winStart = -1;
        }
        if (winStart < 0) {
            // windows line up with the clock (e.g. 12:00:00, 12:01:00), which makes summaries easy to read
            winStart = ts - Math.floorMod(ts, windowMs);
            winN = 0;
            winMin = Double.MAX_VALUE;
            winMax = -Double.MAX_VALUE;
            winSum = 0;
        }
        winN++;
        winMin = Math.min(winMin, v);
        winMax = Math.max(winMax, v);
        winSum += v;
        winLast = v;
    }

    /** Called when the mode or window changes, so old state does not leak into the new mode. */
    public void reset() {
        lastSent = null;
        winStart = -1;
        winN = 0;
    }
}
