package com.cecil.dainandini.ai;

import com.cecil.dainandini.model.User;
import com.cecil.dainandini.util.Clock;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Time maths used everywhere. All times are kept as epoch milliseconds and shown in India time (Clock.ZONE).
 * "Minutes of the day" = minutes after midnight (570 = 09:30).
 */
public final class WorkTime {
    public static final long MIN = 60_000L;
    public static final long HOUR = 60 * MIN;
    public static final long DAY = 24 * HOUR;

    private WorkTime() { }

    /** The moment "date at minute m" in India time. */
    public static long at(LocalDate d, int minuteOfDay) {
        int m = Math.max(0, Math.min(24 * 60, minuteOfDay));
        ZonedDateTime z = m == 24 * 60 ? d.plusDays(1).atStartOfDay(Clock.ZONE) : d.atTime(LocalTime.of(m / 60, m % 60)).atZone(Clock.ZONE);
        return z.toInstant().toEpochMilli();
    }

    public static LocalDate dateOf(long ms) { return LocalDate.ofInstant(Instant.ofEpochMilli(ms), Clock.ZONE); }

    public static int minuteOf(long ms) {
        ZonedDateTime z = Instant.ofEpochMilli(ms).atZone(Clock.ZONE);
        return z.getHour() * 60 + z.getMinute();
    }

    public static boolean workday(User u, LocalDate d) { return u.workDays.contains(d.getDayOfWeek().getValue()); }

    /** The next working day on or after d (gives up after 14 days and returns d). */
    public static LocalDate nextWorkday(User u, LocalDate d) {
        for (int i = 0; i < 14; i++) if (workday(u, d.plusDays(i))) return d.plusDays(i);
        return d;
    }

    /**
     * Working minutes between two moments (only the person's working hours on working days count).
     * Negative when "to" is before "from". Used for "how much working time is left until the deadline?".
     */
    public static long workingMinutes(User u, long from, long to) {
        if (to < from) return -workingMinutes(u, to, from);
        long total = 0;
        LocalDate d = dateOf(from), last = dateOf(to);
        for (int i = 0; i < 400 && !d.isAfter(last); i++, d = d.plusDays(1)) {
            if (!workday(u, d)) continue;
            long s = Math.max(from, at(d, u.workStart)), e = Math.min(to, at(d, u.workEnd));
            if (e > s) total += (e - s) / MIN;
        }
        return total;
    }

    /** Takes the busy parts out of [start, end). busy = list of {start, end}; result is sorted, no overlaps. */
    public static List<long[]> free(long start, long end, List<long[]> busy) {
        List<long[]> b = merge(busy);
        List<long[]> out = new ArrayList<>();
        long cur = start;
        for (long[] x : b) {
            if (x[1] <= cur) continue;
            if (x[0] >= end) break;
            if (x[0] > cur) out.add(new long[] { cur, Math.min(x[0], end) });
            cur = Math.max(cur, x[1]);
            if (cur >= end) break;
        }
        if (cur < end) out.add(new long[] { cur, end });
        return out;
    }

    /** Sorts intervals and joins the ones that overlap or touch. */
    public static List<long[]> merge(List<long[]> in) {
        List<long[]> s = new ArrayList<>();
        for (long[] x : in) if (x[1] > x[0]) s.add(new long[] { x[0], x[1] });
        s.sort((a, b) -> Long.compare(a[0], b[0]));
        List<long[]> out = new ArrayList<>();
        for (long[] x : s) {
            if (!out.isEmpty() && x[0] <= out.get(out.size() - 1)[1]) out.get(out.size() - 1)[1] = Math.max(out.get(out.size() - 1)[1], x[1]);
            else out.add(x);
        }
        return out;
    }

    /** Rounds a moment up to the next 5 minutes. */
    public static long ceil5(long ms) {
        long step = 5 * MIN;
        return ((ms + step - 1) / step) * step;
    }
}
