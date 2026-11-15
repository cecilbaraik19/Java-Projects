package com.cecil.sangam.core;

import com.cecil.sangam.model.Meeting;
import com.cecil.sangam.model.User;
import com.cecil.sangam.util.Json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Finds call times for people in different time zones – and is FAIR about it.
 *
 * For every half-hour start in the next few days we ask each person "how bad is this for you?" (0–5, see
 * WorkHours.stepCost). A plain sum would always pick the same victims (the team in the "odd" time zone would
 * always get the 07:00 or 22:00 call). So each person's cost is multiplied by how much pain they already took:
 *
 *     score = Σ cost(person) × (1 + pain(person) / 3)
 *
 * People who suffered before are protected now – the bad hours rotate around the team.
 * When nothing is good for everyone, the answer says so and suggests going async instead.
 */
public final class MeetingFinder {
    public static final long STEP = 30 * WorkHours.MIN;
    public static final double PAIN_WEIGHT = 3.0;
    /** A best time costing someone this much or more is "bad for somebody": we suggest an async update. */
    public static final int ASYNC_HINT = 3;

    private MeetingFinder() { }

    public record Option(long start, long end, double score, int worst, int total, Map<String, Integer> costs) { }

    /**
     * @param people   everybody who should join (organiser included)
     * @param meetings existing meetings (to skip times when someone is already in a call)
     */
    public static List<Option> suggest(List<User> people, Collection<Meeting> meetings, long from, long to, int minutes, int limit) {
        long len = minutes * WorkHours.MIN;
        long first = ((from + STEP - 1) / STEP) * STEP;
        List<Option> all = new ArrayList<>();
        for (long s = first; s + len <= to; s += STEP) {
            long e = s + len;
            if (busy(people, meetings, s, e) != null) continue;
            Map<String, Integer> costs = new java.util.LinkedHashMap<>();
            double score = 0;
            int worst = 0, total = 0;
            for (User u : people) {
                int c = WorkHours.cost(u, s, e);
                costs.put(u.id, c);
                score += c * (1 + u.pain / PAIN_WEIGHT);
                worst = Math.max(worst, c);
                total += c;
            }
            all.add(new Option(s, e, score, worst, total, costs));
        }
        all.sort(Comparator.comparingDouble(Option::score).thenComparingInt(Option::worst).thenComparingLong(Option::start));
        // keep the best ones, but not three neighbours of the same slot (at least 2 hours apart)
        List<Option> out = new ArrayList<>();
        for (Option o : all) {
            boolean near = false;
            for (Option k : out) if (Math.abs(k.start() - o.start()) < 2 * WorkHours.HOUR) { near = true; break; }
            if (!near) out.add(o);
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** The first person already in another meeting during [s, e), or null. */
    public static User busy(List<User> people, Collection<Meeting> meetings, long s, long e) {
        for (Meeting m : meetings) {
            if (m.cancelled || m.start >= e || m.end <= s) continue;
            for (User u : people) if (m.involves(u.id)) return u;
        }
        return null;
    }

    /** After a meeting is booked: add each person's cost to their pain; people who had a good time recover a bit. */
    public static void charge(List<User> people, Map<String, Integer> costs) {
        for (User u : people) {
            int c = costs.getOrDefault(u.id, 0);
            if (c > 0) u.pain += c;
            else u.pain = Math.max(0, u.pain - 0.5);
        }
    }

    /** Undo charge() (when a future meeting is cancelled). */
    public static void refund(List<User> people, Map<String, Integer> costs) {
        for (User u : people) {
            int c = costs.getOrDefault(u.id, 0);
            if (c > 0) u.pain = Math.max(0, u.pain - c);
            else u.pain += 0.5;
        }
    }

    public static Map<String, Object> describe(Option o, List<User> people) {
        List<Map<String, Object>> who = new ArrayList<>();
        for (User u : people) {
            var z = WorkHours.local(u, o.start());
            var ze = WorkHours.local(u, o.end());
            int c = o.costs().getOrDefault(u.id, 0);
            who.add(Json.obj("userId", u.id, "name", u.name, "city", u.city,
                    "local", String.format("%s %02d:%02d–%02d:%02d", z.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH),
                            z.getHour(), z.getMinute(), ze.getHour(), ze.getMinute()),
                    "cost", c, "label", WorkHours.costLabel(c), "pain", Math.round(u.pain * 10) / 10.0));
        }
        return Json.obj("start", o.start(), "end", o.end(), "score", Math.round(o.score() * 10) / 10.0,
                "worst", o.worst(), "total", o.total(), "people", who);
    }
}
