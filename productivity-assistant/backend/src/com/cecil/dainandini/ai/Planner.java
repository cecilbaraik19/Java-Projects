package com.cecil.dainandini.ai;

import com.cecil.dainandini.model.CalEvent;
import com.cecil.dainandini.model.Energy;
import com.cecil.dainandini.model.Task;
import com.cecil.dainandini.model.User;
import com.cecil.dainandini.util.Json;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * DAY PLANNER – fills the free time of a day with tasks, best first ("time blocking").
 *
 *  1. The day window = working hours (today: from now). Meetings and lunch are taken out → FREE SLOTS.
 *  2. Work that is overdue or due TODAY goes first, earliest deadline first, and only into slots that end before
 *     its deadline. Then all other open tasks, by priority score (see Prioritizer). Tasks planned for a later day wait.
 *  3. Each task gets blocks in the free slots (a GREEDY algorithm: take the best task, give it the best slot):
 *       - DEEP work goes to the slot with the best focus quality (PeakHours), in pieces of 25–90 minutes,
 *         with a 10-minute break after a 90-minute piece;
 *       - QUICK work goes to a slot outside the peak hours (they are kept for deep work), pieces of 10–45 minutes.
 *  4. What does not fit is listed with the minutes that are missing.
 */
public final class Planner {
    public static final int DEEP_MIN = 25, DEEP_MAX = 90, QUICK_MIN = 10, QUICK_MAX = 45, BREAK = 10;

    private Planner() { }

    public record Block(String type, long start, long end, String taskId, String title, String reason, int score, boolean part) {
        public Map<String, Object> toJson() {
            return Json.obj("type", type, "start", start, "end", end, "taskId", taskId, "title", title, "reason", reason,
                    "score", score, "part", part, "minutes", (end - start) / WorkTime.MIN);
        }
    }

    public record Unplaced(Task task, int minutes) { }

    public static final class DayPlan {
        public LocalDate date;
        public boolean workday = true;
        public String note = "";
        public final List<Block> blocks = new ArrayList<>();
        public final List<Unplaced> unplaced = new ArrayList<>();
        public int meetingMin, focusMin, freeMin, windowMin;

        public Block at(long t) {
            for (Block b : blocks) if (b.start() <= t && t < b.end()) return b;
            return null;
        }

        public Block nextTask(long t) {
            for (Block b : blocks) if (b.type().equals("TASK") && b.end() > t) return b;
            return null;
        }
    }

    public static DayPlan plan(User u, LocalDate day, long now, List<Task> open, List<CalEvent> events,
                               EstimateCoach.Model model, PeakHours.Profile peak) {
        DayPlan p = new DayPlan();
        p.date = day;
        LocalDate today = WorkTime.dateOf(now);
        long dayStart = WorkTime.at(day, u.workStart), dayEnd = WorkTime.at(day, u.workEnd);
        for (CalEvent e : events)
            if (e.kind == CalEvent.Kind.LEAVE && e.start <= dayStart + WorkTime.HOUR && e.end >= dayEnd - WorkTime.HOUR) {
                p.workday = false; p.note = "On leave: " + e.title; return p;
            }
        if (!WorkTime.workday(u, day)) { p.workday = false; p.note = "Not a working day"; return p; }

        // meetings and lunch (shown even when they are already over)
        List<long[]> busy = new ArrayList<>();
        for (CalEvent e : events) {
            long s = Math.max(e.start, dayStart), en = Math.min(e.end, dayEnd);
            if (en <= s) continue;
            busy.add(new long[] { s, en });
            p.blocks.add(new Block(e.kind == CalEvent.Kind.BLOCK ? "BLOCK" : "MEETING", s, en, null, e.title,
                    e.kind == CalEvent.Kind.BLOCK ? "time you blocked" : e.attendeeIds.size() + 1 + " people", 0, false));
            p.meetingMin += (int) ((en - s) / WorkTime.MIN);
        }
        long ls = WorkTime.at(day, u.lunchStart), le = ls + u.lunchMin * WorkTime.MIN;
        if (u.lunchMin > 0 && le > dayStart && ls < dayEnd) {
            busy.add(new long[] { ls, le });
            p.blocks.add(new Block("LUNCH", ls, le, null, "Lunch", "away from the screen", 0, false));
        }

        long from = day.isBefore(today) ? dayEnd : day.equals(today) ? Math.max(dayStart, WorkTime.ceil5(now)) : dayStart;
        List<long[]> slots = WorkTime.free(from, dayEnd, busy);
        for (long[] s : slots) p.windowMin += (int) ((s[1] - s[0]) / WorkTime.MIN);

        // tasks, best first
        long scoreAt = Math.max(now, dayStart);
        record Cand(Task t, Prioritizer.Score s, int rank) { }
        List<Cand> cands = new ArrayList<>();
        for (Task t : open) {
            if (!t.open()) continue;
            if (t.doDate != null && LocalDate.parse(t.doDate).isAfter(day)) continue;
            Prioritizer.Score s = Prioritizer.score(t, u, scoreAt, EstimateCoach.remaining(t, model));
            int bonus = t.doDate != null && LocalDate.parse(t.doDate).equals(day) ? 15 : 0;
            cands.add(new Cand(t, s, s.total() + bonus));
        }
        cands.sort(Comparator.comparingInt((Cand c) -> -c.rank()).thenComparingLong(c -> c.t().due == 0 ? Long.MAX_VALUE : c.t().due)
                .thenComparing(c -> c.t().id));
        // Phase 1: work that is overdue or due by the end of this day, earliest deadline first (EDF).
        // Phase 2: everything else, best score first.
        List<Cand> order = new ArrayList<>();
        List<Cand> dueNow = new ArrayList<>();
        for (Cand c : cands) if (c.t().due > 0 && c.t().due <= dayEnd) dueNow.add(c);
        dueNow.sort(Comparator.comparingLong((Cand c) -> c.t().due).thenComparing(c -> c.t().id));
        order.addAll(dueNow);
        for (Cand c : cands) if (!dueNow.contains(c)) order.add(c);

        for (Cand c : order) {
            Task t = c.t();
            int rem = EstimateCoach.remaining(t, model);
            boolean deep = t.energy == Energy.DEEP;
            // a task due later today must be finished BEFORE its deadline (overdue work: as soon as possible)
            long limit = t.due > now && t.due <= dayEnd ? t.due : Long.MAX_VALUE;
            int pieces = 0;
            while (rem > 0) {
                int minChunk = Math.min(rem, deep ? DEEP_MIN : QUICK_MIN);
                if (pieces > 0) minChunk = Math.max(minChunk, Math.min(10, rem + 10));   // no silly 5-minute leftovers
                // quick work is not cut into pieces when a slot can hold all of it
                if (!deep && rem <= QUICK_MAX) {
                    for (long[] s : slots) if ((Math.min(s[1], limit) - s[0]) / WorkTime.MIN >= rem) { minChunk = rem; break; }
                }
                long[] best = null; double bestQ = 0;
                long bestStart = 0;
                for (long[] s : slots) {
                    long end = Math.min(s[1], limit);
                    if ((end - s[0]) / WorkTime.MIN < minChunk) continue;
                    if (deep) {
                        // deep work: try every start (15-minute steps) inside the slot, keep the best focus quality
                        for (long st = s[0]; st + minChunk * WorkTime.MIN <= end; st += 15 * WorkTime.MIN) {
                            double q = peak.scoreFor(st, Math.min(end, st + Math.min(rem, 60) * WorkTime.MIN));
                            if (best == null || q > bestQ + 1e-9) { best = s; bestQ = q; bestStart = st; }
                        }
                    } else {
                        // quick work: a slot OUTSIDE the peak hours (keep those for deep work); on a tie the earlier slot
                        double q = -peak.scoreFor(s[0], end);
                        if (best == null || q > bestQ + 0.05) { best = s; bestQ = q; bestStart = s[0]; }
                    }
                }
                if (best == null) break;
                if (bestStart > best[0]) {
                    // the piece starts inside the slot: keep the part before it as its own free slot
                    if (bestStart - best[0] >= 5 * WorkTime.MIN) slots.add(new long[] { best[0], bestStart });
                    best[0] = bestStart;
                    slots.sort(Comparator.comparingLong(x -> x[0]));
                }
                long len = (Math.min(best[1], limit) - best[0]) / WorkTime.MIN;
                int take = (int) Math.min(rem, Math.min(len, deep ? DEEP_MAX : QUICK_MAX));
                if (rem - take > 0 && rem - take < 15 && len >= rem) take = rem;   // finish it now instead of leaving a tiny tail
                long s = best[0], e = s + take * WorkTime.MIN;
                String reason = String.join(", ", c.s().reasons().subList(0, Math.min(2, c.s().reasons().size())));
                if (deep && peak.scoreFor(s, e) >= 0.75) reason += ", in your best focus hours";
                if (limit != Long.MAX_VALUE) reason += ", finishes before its " + QuickCapture.hhmm(WorkTime.minuteOf(t.due)) + " deadline";
                pieces++;
                p.blocks.add(new Block("TASK", s, e, t.id, t.title, reason, c.s().total(), false));
                p.focusMin += take;
                best[0] = e;
                rem -= take;
                if (deep && take >= DEEP_MAX && best[1] - best[0] >= BREAK * WorkTime.MIN) {
                    p.blocks.add(new Block("BREAK", best[0], best[0] + BREAK * WorkTime.MIN, null, "Short break", "after 90 minutes of focus", 0, false));
                    best[0] += BREAK * WorkTime.MIN;
                }
                slots.removeIf(x -> x[1] - x[0] < 5 * WorkTime.MIN);
            }
            if (pieces > 1) markParts(p.blocks, t.id);
            if (rem > 0 && (t.due > 0 && WorkTime.dateOf(t.due).isBefore(day.plusDays(1)) || pieces > 0 || c.s().urgent()))
                p.unplaced.add(new Unplaced(t, rem));
        }
        for (long[] s : slots) {
            long len = (s[1] - s[0]) / WorkTime.MIN;
            if (len >= 15) { p.blocks.add(new Block("FREE", s[0], s[1], null, "Free", "room for new work", 0, false)); p.freeMin += (int) len; }
        }
        p.blocks.sort(Comparator.comparingLong(Block::start));
        return p;
    }

    private static void markParts(List<Block> blocks, String taskId) {
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            if (taskId.equals(b.taskId())) blocks.set(i, new Block(b.type(), b.start(), b.end(), b.taskId(), b.title(), b.reason(), b.score(), true));
        }
    }

    // ------------------------------------------------------------------ deadline radar

    public record Risk(Task task, int workBefore, int capacityBefore, int shortfall) {
        public Map<String, Object> toJson() {
            return Json.obj("taskId", task.id, "title", task.title, "due", task.due, "workBefore", workBefore,
                    "capacityBefore", capacityBefore, "shortfall", shortfall);
        }
    }

    /** Only this share of free time really becomes focused work (e-mail, chats and small things eat the rest). */
    public static final double FOCUS_SHARE = 0.75;

    /**
     * DEADLINE RADAR – "Earliest Deadline First" check for the next weeks.
     * Sort the tasks with deadlines by deadline. Walk through them adding up the work. For each deadline compare
     * the work that must be done BEFORE it with the free focus time before it. If work > time, the deadline is at
     * risk – even when each task alone looks fine. (EDF is the best order when all deadlines can be met, so if
     * EDF misses one, no order can meet them all.)
     */
    public static List<Risk> radar(User u, long now, List<Task> open, Function<LocalDate, List<CalEvent>> eventsOn,
                                   EstimateCoach.Model model, int horizonDays) {
        List<Task> withDue = new ArrayList<>();
        for (Task t : open) if (t.open() && t.due > 0) withDue.add(t);
        withDue.sort(Comparator.comparingLong((Task t) -> t.due).thenComparing(t -> t.id));
        List<long[]> free = new ArrayList<>();
        LocalDate d = WorkTime.dateOf(now);
        for (int i = 0; i < horizonDays; i++, d = d.plusDays(1)) {
            if (!WorkTime.workday(u, d)) continue;
            long ds = WorkTime.at(d, u.workStart), de = WorkTime.at(d, u.workEnd);
            List<long[]> busy = new ArrayList<>();
            boolean leave = false;
            for (CalEvent e : eventsOn.apply(d)) {
                if (e.kind == CalEvent.Kind.LEAVE) leave = true;
                busy.add(new long[] { e.start, e.end });
            }
            if (leave) continue;
            long ls = WorkTime.at(d, u.lunchStart);
            busy.add(new long[] { ls, ls + u.lunchMin * WorkTime.MIN });
            free.addAll(WorkTime.free(Math.max(ds, now), de, busy));
        }
        List<Risk> risks = new ArrayList<>();
        int work = 0;
        long horizonEnd = WorkTime.at(WorkTime.dateOf(now).plusDays(horizonDays), 0);
        for (Task t : withDue) {
            work += EstimateCoach.remaining(t, model);
            if (t.due > horizonEnd) break;
            long cap = 0;
            for (long[] s : free) if (s[0] < t.due) cap += (Math.min(s[1], t.due) - s[0]) / WorkTime.MIN;
            int capacity = (int) Math.round(cap * FOCUS_SHARE);
            if (t.due < now) risks.add(new Risk(t, work, 0, EstimateCoach.remaining(t, model)));
            else if (work > capacity) risks.add(new Risk(t, work, capacity, work - capacity));
        }
        return risks;
    }
}
