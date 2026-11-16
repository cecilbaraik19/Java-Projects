package com.cecil.dainandini.ai;

import com.cecil.dainandini.model.FocusSession;
import com.cecil.dainandini.model.User;

import java.util.List;

/**
 * PEAK HOURS – learns at which hours of the day this person focuses best.
 *
 * Every finished focus session gives "good minutes" to the hours it covered:
 *     quality = minutes × (1.0 if it ran to the end, else 0.6) ÷ (1 + 0.5 × interruptions)
 * The 24 numbers are scaled so the best hour is 1.0. With little history (under 3 hours of focus) a normal office
 * curve is used (fresh in the morning, a dip after lunch); with more history the learned curve takes over
 * (80 % learned + 20 % normal curve, so one odd week does not flip everything).
 */
public final class PeakHours {
    public static final double[] DEFAULT = new double[24];
    static {
        for (int h = 0; h < 24; h++) DEFAULT[h] = 0.2;
        DEFAULT[9] = 0.9; DEFAULT[10] = 1.0; DEFAULT[11] = 0.85; DEFAULT[12] = 0.6; DEFAULT[13] = 0.3;
        DEFAULT[14] = 0.55; DEFAULT[15] = 0.65; DEFAULT[16] = 0.55; DEFAULT[17] = 0.4; DEFAULT[8] = 0.6;
    }

    private PeakHours() { }

    public record Profile(double[] hours, boolean learned, int focusMinutes) {
        /** Average focus quality across [start, end) (only the first hour counts most for a block). */
        public double scoreFor(long start, long end) {
            long s = start, e = Math.min(end, start + WorkTime.HOUR);
            double sum = 0; long n = 0;
            for (long t = s; t < e; t += 5 * WorkTime.MIN) { sum += hours[WorkTime.minuteOf(t) / 60]; n++; }
            return n == 0 ? 0 : sum / n;
        }

        /** The best 2-hour window inside working hours, as {startMinute, endMinute}. */
        public int[] bestWindow(User u) {
            int bestStart = u.workStart; double best = -1;
            for (int m = u.workStart; m + 120 <= u.workEnd; m += 30) {
                double sum = 0;
                for (int k = m; k < m + 120; k += 30) sum += hours[k / 60];
                if (sum > best) { best = sum; bestStart = m; }
            }
            return new int[] { bestStart, Math.min(u.workEnd, bestStart + 120) };
        }
    }

    public static Profile learn(List<FocusSession> sessions) {
        double[] h = new double[24];
        int total = 0;
        for (FocusSession f : sessions) {
            if (f.running() || f.minutes() < 5) continue;
            total += f.minutes();
            double q = (f.finished ? 1.0 : 0.6) / (1 + 0.5 * f.interruptions);
            for (long t = f.start; t < f.end; t += WorkTime.MIN) h[WorkTime.minuteOf(t) / 60] += q;
        }
        if (total < 180) return new Profile(DEFAULT.clone(), false, total);
        double max = 0;
        for (double x : h) max = Math.max(max, x);
        double[] out = new double[24];
        for (int i = 0; i < 24; i++) out[i] = Math.round((0.8 * (h[i] / max) + 0.2 * DEFAULT[i]) * 100) / 100.0;
        return new Profile(out, true, total);
    }
}
