package com.cecil.kaksh.core;

import com.cecil.kaksh.model.Booking;
import com.cecil.kaksh.model.Room;
import com.cecil.kaksh.model.User;
import com.cecil.kaksh.service.Problem;
import com.cecil.kaksh.store.DataStore;
import com.cecil.kaksh.util.Clock;
import com.cecil.kaksh.util.Json;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds what IS possible when the first choice is not:
 *
 *   1. nearestTimes  – the same room, the nearest free times (earlier or later the same day, then the same time
 *                      on the next open days). Times when all the invited people are also free come first.
 *   2. rooms         – other rooms at the same time, ranked by a "room fit" score (0–100) that explains itself.
 *   3. commonTimes   – "when are all these people free?": merges everybody's busy times and walks through the
 *                      gaps (a sweep line), then picks the best free room for each gap.
 *   4. freeGaps      – the free stretches of one room on one day.
 */
public class SlotFinder {
    private final DataStore store;
    private final RoomCalendar cal;

    public SlotFinder(DataStore store, RoomCalendar cal) {
        this.store = store;
        this.cal = cal;
    }

    // ------------------------------------------------------------------ people

    /** Bookings (in any room) in which one of these people takes part and which overlap [s, e). */
    public List<Map<String, Object>> peopleClashes(Collection<String> people, long s, long e, String ignoreId) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (people.isEmpty()) return out;
        synchronized (store) {
            for (Booking b : store.bookings.values()) {
                if (!b.status.open() || b.id.equals(ignoreId) || b.end <= s || b.start >= e) continue;
                for (String p : people) {
                    if (b.involves(p)) out.add(Json.obj("userId", p, "name", store.userName(p), "bookingId", b.id,
                            "start", b.start, "end", b.end, "room", store.roomName(b.roomId)));
                }
            }
        }
        out.sort(Comparator.comparingLong(m -> (Long) m.get("start")));
        return out;
    }

    /** Busy times of these people from..to, merged into one sorted list of non-overlapping [s, e) pairs. */
    public List<long[]> mergedBusy(Collection<String> people, long from, long to) {
        List<long[]> list = new ArrayList<>();
        synchronized (store) {
            for (Booking b : store.bookings.values()) {
                if (!b.status.open() || b.end <= from || b.start >= to) continue;
                for (String p : people) if (b.involves(p)) { list.add(new long[] { b.start, b.end }); break; }
            }
        }
        list.sort(Comparator.comparingLong(x -> x[0]));
        List<long[]> merged = new ArrayList<>();
        for (long[] x : list) {
            if (!merged.isEmpty() && x[0] <= merged.get(merged.size() - 1)[1]) {
                long[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], x[1]);
            } else merged.add(new long[] { x[0], x[1] });
        }
        return merged;
    }

    // ------------------------------------------------------------------ 1. nearest free times in the same room

    public List<Map<String, Object>> nearestTimes(Room room, long s, long e, Collection<String> people, User who,
                                                   String ignoreId, int want) {
        long len = e - s;
        long step = Rules.STEP_MIN * Rules.MINUTE;
        record Cand(long start, long shift, boolean peopleFree) { }
        List<Cand> cands = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        LocalDate day = Rules.dayOf(s);
        long timeOfDay = s - Rules.opens(day);
        // the same day (both directions), then the same time on the next 3 open days and around it
        List<LocalDate> days = new ArrayList<>();
        days.add(day);
        LocalDate d = day;
        for (int i = 0; i < 3; i++) { d = Rules.nextOpenDay(d.plusDays(1)); days.add(d); }
        for (LocalDate dd : days) {
            long base = Rules.opens(dd) + timeOfDay;
            for (int k = 0; k <= 48; k++) {
                for (int sign : new int[] { 1, -1 }) {
                    if (k == 0 && sign < 0) continue;
                    long st = base + sign * k * step;
                    if (st == s || !seen.add(st)) continue;
                    if (!allowed(st, st + len, who)) continue;
                    if (!cal.free(room, st, st + len, ignoreId)) continue;
                    boolean pf = peopleClashes(people, st, st + len, ignoreId).isEmpty();
                    cands.add(new Cand(st, Math.abs(st - s) / Rules.MINUTE, pf));
                }
                if (cands.size() > want * 6) break;
            }
        }
        // closer is better; a time when somebody invited is busy counts as 2 hours further away
        cands.sort(Comparator.comparingLong(c -> c.shift() + (c.peopleFree() ? 0 : 120)));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Cand c : cands) {
            if (out.size() >= want) break;
            out.add(Json.obj("start", c.start(), "end", c.start() + len, "shiftMin", (c.start() - s) / Rules.MINUTE,
                    "peopleFree", c.peopleFree()));
        }
        return out;
    }

    private static boolean allowed(long s, long e, User who) {
        try {
            Rules.checkTime(s, e, who);
            return true;
        } catch (Problem p) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 2. rooms at the same time, with a fit score

    /**
     * Room fit (0–100):
     *   45  size      – best when the group fills 50–100% of the seats; a room far too big wastes space
     *   25  equipment – everything asked for is there (a room without it is not offered at all)
     *   20  nearness  – same floor as the organiser 20, one floor away 10, else 0
     *   10  instant   – does not need the facility manager's approval
     */
    public Map<String, Object> fit(Room r, int headcount, List<String> needs, User who) {
        List<Map<String, Object>> parts = new ArrayList<>();
        double fill = (double) headcount / Math.max(1, r.capacity);
        double size = fill > 1 ? 0 : fill >= 0.5 ? 1 : 0.35 + 1.3 * fill;   // 0 people -> 0.35, half full -> 1
        size = Math.min(1, Math.max(0, size));
        parts.add(Json.obj("name", "Size", "max", 45, "points", Math.round(45 * size),
                "why", headcount + " people in " + r.capacity + " seats (" + Math.round(Math.min(fill, 9.99) * 100) + "% full)"));
        int haveNeeds = 0;
        for (String n : needs) if (r.has(n)) haveNeeds++;
        parts.add(Json.obj("name", "Equipment", "max", 25, "points", needs.isEmpty() ? 25 : Math.round(25.0 * haveNeeds / needs.size()),
                "why", needs.isEmpty() ? "nothing special asked for" : haveNeeds + " of " + needs.size() + " asked for"));
        int floorGap = who == null ? 0 : Math.abs(who.floor - r.floor);
        parts.add(Json.obj("name", "Nearness", "max", 20, "points", (long) (floorGap == 0 ? 20 : floorGap == 1 ? 10 : 0),
                "why", floorGap == 0 ? "on your floor" : floorGap + " floor" + (floorGap == 1 ? "" : "s") + " away"));
        parts.add(Json.obj("name", "Instant", "max", 10, "points", (long) (r.needsApproval ? 0 : 10),
                "why", r.needsApproval ? "needs the facility manager's OK" : "confirmed at once"));
        long score = 0;
        for (Map<String, Object> p : parts) score += ((Number) p.get("points")).longValue();
        return Json.obj("score", score, "parts", parts);
    }

    /** Every active room for [s, e): is it free, does it fit, and its score. Usable rooms first, best score first. */
    public List<Map<String, Object>> rooms(long s, long e, int headcount, List<String> needs, User who, String ignoreId) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Room> all;
        synchronized (store) { all = new ArrayList<>(store.rooms.values()); }
        for (Room r : all) {
            if (!r.active) continue;
            List<String> why = new ArrayList<>();
            if (r.capacity < headcount) why.add("only " + r.capacity + " seats");
            for (String n : needs) if (!r.has(n)) why.add("no " + n.toLowerCase().replace('_', ' '));
            List<RoomCalendar.Busy> clash = cal.clashes(r.id, s, e, r.bufferMin * Rules.MINUTE, ignoreId);
            if (!clash.isEmpty()) why.add(clash.get(0).kind().equals("BLOCK") ? "closed for maintenance" : "already booked");
            Map<String, Object> f = fit(r, headcount, needs, who);
            out.add(Json.obj("roomId", r.id, "name", r.name, "code", r.code, "floor", r.floor, "capacity", r.capacity,
                    "equipment", r.equipment, "needsApproval", r.needsApproval, "usable", why.isEmpty(), "why", why,
                    "score", f.get("score"), "parts", f.get("parts")));
        }
        out.sort(Comparator.<Map<String, Object>, Boolean>comparing(m -> !(Boolean) m.get("usable"))
                .thenComparing(m -> -(Long) m.get("score")));
        return out;
    }

    // ------------------------------------------------------------------ 3. a time when everybody is free

    /**
     * Looks at `days` open days starting from `from`. For each day: busy = everybody's merged busy times; the free
     * gaps between them (inside office hours) are walked in quarter-hour steps; every start where the meeting fits
     * gets the best free room. At most 3 suggestions per gap so the list stays varied.
     */
    public List<Map<String, Object>> commonTimes(Collection<String> people, LocalDate from, int days, int minutes,
                                                 int headcount, List<String> needs, User who, int want) {
        List<Map<String, Object>> out = new ArrayList<>();
        long len = minutes * Rules.MINUTE, step = Rules.STEP_MIN * Rules.MINUTE;
        LocalDate d = Rules.nextOpenDay(from);
        for (int i = 0; i < days && out.size() < want; i++, d = Rules.nextOpenDay(d.plusDays(1))) {
            long open = Math.max(Rules.opens(d), Rules.ceilStep(Clock.millis()));
            long close = Rules.closes(d);
            if (open >= close) continue;
            List<long[]> busy = mergedBusy(people, open, close);
            long cursor = open;
            List<long[]> gaps = new ArrayList<>();
            for (long[] b : busy) {
                if (b[0] > cursor) gaps.add(new long[] { cursor, Math.min(b[0], close) });
                cursor = Math.max(cursor, b[1]);
            }
            if (cursor < close) gaps.add(new long[] { cursor, close });
            for (long[] g : gaps) {
                int perGap = 0;
                for (long st = Rules.ceilStep(g[0]); st + len <= g[1] && perGap < 3 && out.size() < want; st += step) {
                    if (!allowed(st, st + len, who)) continue;
                    Map<String, Object> best = null;
                    for (Map<String, Object> r : rooms(st, st + len, headcount, needs, who, null))
                        if ((Boolean) r.get("usable")) { best = r; break; }
                    if (best == null) continue;
                    out.add(Json.obj("start", st, "end", st + len, "room", best));
                    perGap++;
                    st += 3 * step;   // spread the suggestions a little inside one gap
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 4. free stretches of one room

    public List<Map<String, Object>> freeGaps(Room r, LocalDate d) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Rules.openDay(d)) return out;
        long open = Math.max(Rules.opens(d), Rules.ceilStep(Clock.millis())), close = Rules.closes(d);
        long buf = r.bufferMin * Rules.MINUTE;
        long cursor = open;
        for (RoomCalendar.Busy b : cal.between(r.id, Rules.opens(d), close)) {
            long lastEnd = Rules.floorStep(b.start() - buf);   // a meeting must end (plus buffer) before this busy time
            if (lastEnd - cursor >= Rules.MIN_MIN * Rules.MINUTE) out.add(Json.obj("start", cursor, "end", lastEnd));
            cursor = Math.max(cursor, Rules.ceilStep(b.end() + buf));
        }
        if (cursor + Rules.MIN_MIN * Rules.MINUTE <= close) out.add(Json.obj("start", cursor, "end", close));
        return out;
    }
}
