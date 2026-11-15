package com.cecil.sangam.core;

import com.cecil.sangam.model.User;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Everything about time zones and working hours. All times inside the app are UTC milliseconds;
 * this class turns them into "what time is it for Lena in Berlin, and is she working?".
 *
 * java.time does the hard part: summer time (DST) changes, half-hour zones like India (+05:30), and so on.
 * Rule: working hours are given in local time, start before end on the same day (no night shifts).
 */
public final class WorkHours {
    public static final long MIN = 60_000L;
    public static final long HOUR = 60 * MIN;
    public static final long DAY = 24 * HOUR;
    /** The UTC day is cut into 96 quarter hours for the time-zone strip. */
    public static final int SLOTS = 96;

    private WorkHours() { }

    public static ZoneId zone(User u) {
        try { return ZoneId.of(u.zone); } catch (Exception e) { return ZoneId.of("UTC"); }
    }

    public static ZonedDateTime local(User u, long t) { return Instant.ofEpochMilli(t).atZone(zone(u)); }

    /** Minutes after local midnight. */
    public static int minuteOfDay(ZonedDateTime z) { return z.getHour() * 60 + z.getMinute(); }

    public static boolean workday(User u, ZonedDateTime z) { return u.workDays.contains(z.getDayOfWeek().getValue()); }

    /** Is this person inside their working hours at moment t? */
    public static boolean working(User u, long t) {
        ZonedDateTime z = local(u, t);
        int m = minuteOfDay(z);
        return workday(u, z) && m >= u.workStart && m < u.workEnd;
    }

    /** Start of the person's working hours on a local date (as UTC millis). */
    public static long startOn(User u, LocalDate d) {
        return d.atStartOfDay(zone(u)).plusMinutes(u.workStart).toInstant().toEpochMilli();
    }

    public static long endOn(User u, LocalDate d) {
        return d.atStartOfDay(zone(u)).plusMinutes(u.workEnd).toInstant().toEpochMilli();
    }

    /** The first moment at or after t when this person is working (t itself if working now). */
    public static long nextStart(User u, long t) {
        if (working(u, t)) return t;
        LocalDate d = local(u, t).toLocalDate();
        for (int i = 0; i < 10; i++, d = d.plusDays(1)) {
            if (!u.workDays.contains(d.getDayOfWeek().getValue())) continue;
            long s = startOn(u, d);
            if (s > t) return s;
        }
        return t + DAY; // no working days at all: just "tomorrow"
    }

    /** When the current working block ends (or t if not working now). */
    public static long endOfToday(User u, long t) {
        if (!working(u, t)) return t;
        return endOn(u, local(u, t).toLocalDate());
    }

    /** The person's working intervals [start, end) that overlap [from, to). */
    public static List<long[]> windows(User u, long from, long to) {
        List<long[]> out = new ArrayList<>();
        LocalDate d = local(u, from).toLocalDate().minusDays(1);
        LocalDate last = local(u, to).toLocalDate().plusDays(1);
        for (; !d.isAfter(last); d = d.plusDays(1)) {
            if (!u.workDays.contains(d.getDayOfWeek().getValue())) continue;
            long s = Math.max(from, startOn(u, d)), e = Math.min(to, endOn(u, d));
            if (s < e) out.add(new long[] { s, e });
        }
        return out;
    }

    /** How many of the minutes between from and to fall in the person's working hours. */
    public static long workingMillisBetween(User u, long from, long to) {
        long sum = 0;
        for (long[] w : windows(u, from, to)) sum += w[1] - w[0];
        return sum;
    }

    // ------------------------------------------------------------------ meeting cost ("how bad is this time for me?")

    /**
     * Cost of one moment for a person:
     *   0 inside working hours
     *   1 up to 1 hour before/after,  2 up to 2 hours,  3 more than that
     *   4 a day off (weekend),  5 night (22:00–07:00 local)
     */
    public static int stepCost(User u, long t) {
        ZonedDateTime z = local(u, t);
        int m = minuteOfDay(z);
        boolean wd = workday(u, z);
        if (wd && m >= u.workStart && m < u.workEnd) return 0;
        if (m >= 22 * 60 || m < 7 * 60) return 5;
        if (!wd) return 4;
        int away = m < u.workStart ? u.workStart - m : m - u.workEnd;
        if (away <= 60) return 1;
        if (away <= 120) return 2;
        return 3;
    }

    /** Cost of a meeting [s, e): the worst quarter hour of it. */
    public static int cost(User u, long s, long e) {
        int worst = 0;
        for (long t = s; t < e; t += 15 * MIN) worst = Math.max(worst, stepCost(u, t));
        return worst;
    }

    public static String costLabel(int c) {
        return switch (c) {
            case 0 -> "in working hours";
            case 1 -> "a little outside hours";
            case 2 -> "outside hours";
            case 3 -> "well outside hours";
            case 4 -> "a day off";
            default -> "night time";
        };
    }

    // ------------------------------------------------------------------ the team strip

    /** For one UTC day: how many of the people work in each quarter hour (96 numbers). */
    public static int[] counts(Collection<User> people, LocalDate utcDay) {
        long base = utcDay.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        int[] c = new int[SLOTS];
        for (User u : people)
            for (long[] w : windows(u, base, base + DAY)) {
                int a = (int) ((w[0] - base) / (15 * MIN)), b = (int) ((w[1] - base + 15 * MIN - 1) / (15 * MIN));
                for (int i = Math.max(0, a); i < Math.min(SLOTS, b); i++) c[i]++;
            }
        return c;
    }

    /** Longest run of quarter hours where at least `need` people work: {startSlot, length} or {-1, 0}. */
    public static int[] longestRun(int[] counts, int need) {
        int bestStart = -1, bestLen = 0, runStart = -1;
        for (int i = 0; i <= counts.length; i++) {
            boolean ok = i < counts.length && counts[i] >= need && need > 0;
            if (ok && runStart < 0) runStart = i;
            if (!ok && runStart >= 0) {
                if (i - runStart > bestLen) { bestLen = i - runStart; bestStart = runStart; }
                runStart = -1;
            }
        }
        return new int[] { bestStart, bestLen };
    }

    /** Hours per working week that two people are both working (looks at the next 7 UTC days). */
    public static double weeklyOverlapHours(User a, User b, LocalDate fromUtc) {
        long from = fromUtc.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), to = from + 7 * DAY;
        long sum = 0;
        List<long[]> wa = windows(a, from, to), wb = windows(b, from, to);
        int i = 0, j = 0;
        while (i < wa.size() && j < wb.size()) {           // two-pointer merge of sorted intervals
            long s = Math.max(wa.get(i)[0], wb.get(j)[0]), e = Math.min(wa.get(i)[1], wb.get(j)[1]);
            if (s < e) sum += e - s;
            if (wa.get(i)[1] < wb.get(j)[1]) i++; else j++;
        }
        return sum / (double) HOUR;
    }

    /** The "now" card for one person: local time, working or not, and when their next day starts. */
    public static Map<String, Object> clock(User u, long now) {
        ZonedDateTime z = local(u, now);
        boolean w = working(u, now);
        long next = nextStart(u, now);
        return com.cecil.sangam.util.Json.obj(
                "local", String.format("%02d:%02d", z.getHour(), z.getMinute()),
                "day", z.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH),
                "offset", z.getOffset().getId().equals("Z") ? "+00:00" : z.getOffset().getId(),
                "working", w,
                "nextStart", next,
                "endsAt", w ? endOfToday(u, now) : 0,
                "night", stepCost(u, now) == 5);
    }

    /** Is "HH:MM" a valid time? Returns minutes or -1. */
    public static int parseHm(String s) {
        if (s == null || !s.matches("\\d{1,2}:\\d{2}")) return -1;
        String[] p = s.split(":");
        int h = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]);
        if (h > 24 || m > 59 || (h == 24 && m > 0)) return -1;
        return h * 60 + m;
    }
}
