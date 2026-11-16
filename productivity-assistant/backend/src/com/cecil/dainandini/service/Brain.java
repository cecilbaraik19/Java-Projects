package com.cecil.dainandini.service;

import com.cecil.dainandini.ai.EstimateCoach;
import com.cecil.dainandini.ai.PeakHours;
import com.cecil.dainandini.ai.Planner;
import com.cecil.dainandini.ai.Prioritizer;
import com.cecil.dainandini.ai.QuickCapture;
import com.cecil.dainandini.ai.WorkTime;
import com.cecil.dainandini.model.CalEvent;
import com.cecil.dainandini.model.FocusSession;
import com.cecil.dainandini.model.Task;
import com.cecil.dainandini.model.TaskStatus;
import com.cecil.dainandini.model.User;
import com.cecil.dainandini.store.DataStore;
import com.cecil.dainandini.util.Clock;
import com.cecil.dainandini.util.Json;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The "brain": puts the AI parts together for one person – their learned estimate factor, their peak hours,
 * the day plan, the deadline radar, the Today page, the Insights page and the team workload.
 * Nothing here changes data; it only reads and works things out.
 */
public class Brain {
    public static final int TEAM_DAYS = 5;

    private final DataStore store;

    public Brain(DataStore store) { this.store = store; }

    // ------------------------------------------------------------------ learned things

    public EstimateCoach.Model model(User u) {
        synchronized (store) { return EstimateCoach.learn(store.tasksOf(u.id)); }
    }

    public PeakHours.Profile peak(User u) {
        synchronized (store) { return PeakHours.learn(store.focusOf(u.id, Clock.millis() - 42 * WorkTime.DAY)); }
    }

    public List<CalEvent> eventsOn(User u, LocalDate d) {
        return store.eventsOf(u.id, WorkTime.at(d, 0), WorkTime.at(d.plusDays(1), 0));
    }

    public Planner.DayPlan plan(User u, LocalDate day, long now) {
        synchronized (store) {
            return Planner.plan(u, day, now, store.openTasksOf(u.id), eventsOn(u, day), model(u), peak(u));
        }
    }

    public List<Planner.Risk> radar(User u, long now) {
        synchronized (store) {
            return Planner.radar(u, now, store.openTasksOf(u.id), d -> eventsOn(u, d), model(u), 21);
        }
    }

    /** Free focus minutes in the next `days` working days (meetings and lunch taken out, × 75 %). */
    public int capacity(User u, long now, int days) {
        int total = 0, counted = 0;
        LocalDate d = WorkTime.dateOf(now);
        for (int i = 0; i < 21 && counted < days; i++, d = d.plusDays(1)) {
            if (!WorkTime.workday(u, d)) continue;
            counted++;
            long ds = WorkTime.at(d, u.workStart), de = WorkTime.at(d, u.workEnd);
            List<long[]> busy = new ArrayList<>();
            boolean leave = false;
            for (CalEvent e : eventsOn(u, d)) { if (e.kind == CalEvent.Kind.LEAVE) leave = true; busy.add(new long[] { e.start, e.end }); }
            if (leave) continue;
            long ls = WorkTime.at(d, u.lunchStart);
            busy.add(new long[] { ls, ls + u.lunchMin * WorkTime.MIN });
            for (long[] s : WorkTime.free(Math.max(ds, now), de, busy)) total += (int) ((s[1] - s[0]) / WorkTime.MIN);
        }
        return (int) Math.round(total * Planner.FOCUS_SHARE);
    }

    // ------------------------------------------------------------------ JSON for the pages

    public Map<String, Object> taskJson(Task t, long now, EstimateCoach.Model m) {
        User owner = store.users.get(t.ownerId);
        Map<String, Object> j = new LinkedHashMap<>(t.toJson());
        int rem = EstimateCoach.remaining(t, m);
        j.put("ownerName", store.userName(t.ownerId));
        j.put("createdByName", store.userName(t.createdBy));
        j.put("remainingMin", t.open() ? rem : 0);
        j.put("factor", t.estimateGuessed ? 1.0 : m.factorFor(t));
        j.put("repeatLabel", QuickCapture.repeatLabel(t.recurrence));
        if (t.open() && owner != null) {
            Prioritizer.Score s = Prioritizer.score(t, owner, now, rem);
            j.put("score", s.total());
            j.put("quadrant", s.quadrant());
            j.put("reasons", s.reasons());
            j.put("overdue", s.overdue());
            j.put("parts", Json.obj("importance", s.importance(), "urgency", s.urgency(), "age", s.age(), "today", s.today()));
            j.put("slackMin", s.slackMin());
        }
        return j;
    }

    public Map<String, Object> planJson(Planner.DayPlan p) {
        List<Object> blocks = new ArrayList<>();
        for (Planner.Block b : p.blocks) {
            Map<String, Object> j = b.toJson();
            Task t = b.taskId() == null ? null : store.tasks.get(b.taskId());
            j.put("deep", t != null && t.energy == com.cecil.dainandini.model.Energy.DEEP);
            blocks.add(j);
        }
        List<Object> left = new ArrayList<>();
        for (Planner.Unplaced x : p.unplaced) left.add(Json.obj("taskId", x.task().id, "title", x.task().title, "minutes", x.minutes(), "due", x.task().due));
        return Json.obj("date", p.date.toString(), "workday", p.workday, "note", p.note, "blocks", blocks, "unplaced", left,
                "meetingMin", p.meetingMin, "focusMin", p.focusMin, "freeMin", p.freeMin, "windowMin", p.windowMin);
    }

    // ------------------------------------------------------------------ Today page

    public Map<String, Object> today(User u) {
        synchronized (store) {
            long now = Clock.millis();
            LocalDate today = WorkTime.dateOf(now);
            EstimateCoach.Model m = model(u);
            Planner.DayPlan plan = plan(u, today, now);
            List<Planner.Risk> risks = radar(u, now);

            Map<String, Object> doNow = doNow(u, plan, now, m);
            List<Task> open = store.openTasksOf(u.id);
            int overdue = 0, dueToday = 0;
            long endToday = WorkTime.at(today.plusDays(1), 0);
            for (Task t : open) {
                if (t.due > 0 && t.due < now) overdue++;
                else if (t.due > 0 && t.due < endToday) dueToday++;
            }
            int doneToday = 0;
            for (Task t : store.tasksOf(u.id)) if (t.status == TaskStatus.DONE && t.doneAt >= WorkTime.at(today, 0)) doneToday++;
            int focusToday = focusMinutes(u, WorkTime.at(today, 0), now);
            FocusSession running = store.runningFocus(u.id);

            List<CalEvent> evs = eventsOn(u, today);
            CalEvent next = null;
            int meetings = 0;
            for (CalEvent e : evs) if (e.kind == CalEvent.Kind.MEETING) { meetings++; if (e.start > now && next == null) next = e; }

            // top 5 tasks by score
            List<Map<String, Object>> top = new ArrayList<>();
            for (Task t : open) top.add(taskJson(t, now, m));
            top.sort(Comparator.comparingInt((Map<String, Object> x) -> -((Number) x.get("score")).intValue()));
            if (top.size() > 6) top = new ArrayList<>(top.subList(0, 6));

            List<Object> riskJson = new ArrayList<>();
            for (Planner.Risk r : risks) riskJson.add(r.toJson());

            PeakHours.Profile peak = peak(u);
            int[] bw = peak.bestWindow(u);
            String hello = greeting(now) + ", " + u.firstName() + ".";
            List<String> brief = new ArrayList<>();
            if (!plan.workday) brief.add(plan.note + " – enjoy the day.");
            else {
                brief.add("Today has " + meetings + " meeting" + (meetings == 1 ? "" : "s") + " (" + QuickCapture.duration(plan.meetingMin)
                        + ") and about " + QuickCapture.duration(plan.focusMin + plan.freeMin) + " of free time left.");
                if (!top.isEmpty()) brief.add("Most important: “" + top.get(0).get("title") + "” – " + String.join(", ", castList(top.get(0).get("reasons")).subList(0, Math.min(2, castList(top.get(0).get("reasons")).size()))) + ".");
                if (overdue > 0) brief.add(overdue + " task" + (overdue == 1 ? " is" : "s are") + " overdue.");
                if (!risks.isEmpty()) brief.add(risks.size() + " deadline" + (risks.size() == 1 ? " is" : "s are") + " at risk – see the radar.");
                brief.add("Your best focus time is " + QuickCapture.hhmm(bw[0]) + "–" + QuickCapture.hhmm(bw[1]) + (peak.learned() ? " (learned from your focus sessions)." : " (a normal office day – it will learn from your focus sessions)."));
            }
            return Json.obj("hello", hello, "brief", brief, "doNow", doNow, "plan", planJson(plan), "top", top, "risks", riskJson,
                    "counts", Json.obj("open", open.size(), "overdue", overdue, "dueToday", dueToday, "doneToday", doneToday,
                            "focusToday", focusToday, "focusGoal", u.focusGoalMin, "meetings", meetings),
                    "nextMeeting", next == null ? null : Json.obj("title", next.title, "start", next.start, "end", next.end),
                    "running", running == null ? null : focusJson(running), "now", now);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object o) { return (List<String>) o; }

    /** "What should I do right now?" – read from the plan at this moment. */
    public Map<String, Object> doNow(User u, Planner.DayPlan plan, long now, EstimateCoach.Model m) {
        FocusSession running = store.runningFocus(u.id);
        if (running != null) {
            Task t = store.tasks.get(running.taskId);
            return Json.obj("kind", "FOCUS", "title", t == null ? "Focus" : t.title, "taskId", running.taskId,
                    "until", running.start + running.plannedMin * WorkTime.MIN, "reason", "you are in a focus session – keep going");
        }
        if (!plan.workday) return Json.obj("kind", "OFF", "title", "Day off", "reason", plan.note);
        long dayEnd = WorkTime.at(plan.date, u.workEnd), dayStart = WorkTime.at(plan.date, u.workStart);
        if (now >= dayEnd) return Json.obj("kind", "OFF", "title", "Your working day is over", "reason", "rest well – tomorrow is planned already");
        Planner.Block b = plan.at(now);
        if (b != null && (b.type().equals("MEETING") || b.type().equals("BLOCK") || b.type().equals("LUNCH")))
            return Json.obj("kind", b.type(), "title", b.title(), "until", b.end(), "reason", b.type().equals("LUNCH") ? "take a real break" : "in the calendar");
        if (b != null && b.type().equals("TASK")) {
            Task t = store.tasks.get(b.taskId());
            return Json.obj("kind", "TASK", "title", b.title(), "taskId", b.taskId(), "until", b.end(), "reason", b.reason(),
                    "task", t == null ? null : taskJson(t, now, m));
        }
        Planner.Block next = plan.nextTask(now);
        if (next != null) {
            Task t = store.tasks.get(next.taskId());
            boolean before = now < dayStart;
            return Json.obj("kind", "TASK", "title", next.title(), "taskId", next.taskId(), "from", next.start(), "until", next.end(),
                    "reason", (before ? "first thing today: " : "") + next.reason(), "task", t == null ? null : taskJson(t, now, m));
        }
        return Json.obj("kind", "FREE", "title", "Nothing urgent", "reason", "all planned work is done – pick something from Later, or rest");
    }

    public static String greeting(long now) {
        int m = WorkTime.minuteOf(now);
        return m < 12 * 60 ? "Good morning" : m < 17 * 60 ? "Good afternoon" : "Good evening";
    }

    public Map<String, Object> focusJson(FocusSession f) {
        Task t = store.tasks.get(f.taskId);
        return Json.obj("id", f.id, "taskId", f.taskId, "title", t == null ? "(task removed)" : t.title, "start", f.start,
                "end", f.end, "plannedMin", f.plannedMin, "interruptions", f.interruptions, "finished", f.finished, "minutes", f.minutes());
    }

    int focusMinutes(User u, long from, long to) {
        int sum = 0;
        for (FocusSession f : store.focusOf(u.id, from - WorkTime.DAY)) {
            long s = Math.max(f.start, from), e = Math.min(f.running() ? to : f.end, to);
            if (e > s) sum += (int) ((e - s) / WorkTime.MIN);
        }
        return sum;
    }

    // ------------------------------------------------------------------ Insights page

    public Map<String, Object> insights(User u, int days) {
        synchronized (store) {
            long now = Clock.millis();
            LocalDate today = WorkTime.dateOf(now);
            LocalDate first = today.minusDays(days - 1);
            long from = WorkTime.at(first, 0);
            List<String> labels = new ArrayList<>();
            List<Integer> focus = new ArrayList<>(), meetings = new ArrayList<>(), done = new ArrayList<>();
            int workdays = 0, goalDays = 0, streak = 0, bestStreak = 0, meetingTotal = 0, windowTotal = 0;
            for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) {
                labels.add(d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + d.getDayOfMonth());
                long ds = WorkTime.at(d, 0), de = WorkTime.at(d.plusDays(1), 0);
                int f = focusMinutes(u, ds, Math.min(de, now));
                int mt = 0;
                for (CalEvent e : eventsOn(u, d)) if (e.kind == CalEvent.Kind.MEETING) mt += (int) ((Math.min(e.end, de) - Math.max(e.start, ds)) / WorkTime.MIN);
                int dn = 0;
                for (Task t : store.tasksOf(u.id)) if (t.status == TaskStatus.DONE && t.doneAt >= ds && t.doneAt < de) dn++;
                focus.add(f); meetings.add(mt); done.add(dn);
                if (WorkTime.workday(u, d)) {
                    workdays++;
                    meetingTotal += mt;
                    windowTotal += u.workEnd - u.workStart - u.lunchMin;
                    if (f >= u.focusGoalMin) { goalDays++; streak++; bestStreak = Math.max(bestStreak, streak); }
                    else if (!d.equals(today)) streak = 0;
                }
            }
            int focusTotal = focus.stream().mapToInt(Integer::intValue).sum();
            int doneTotal = done.stream().mapToInt(Integer::intValue).sum();

            // finished tasks: on time, cycle time
            int withDue = 0, onTime = 0;
            List<Double> cycle = new ArrayList<>();
            for (Task t : store.tasksOf(u.id)) {
                if (t.status != TaskStatus.DONE || t.doneAt < from) continue;
                if (t.due > 0) { withDue++; if (t.doneAt <= t.due) onTime++; }
                cycle.add((t.doneAt - t.createdAt) / (double) WorkTime.DAY);
            }
            double onTimeRate = withDue == 0 ? 1.0 : onTime / (double) withDue;

            // focus sessions: interruptions, after-hours, heat map (rows Mon..Sun, columns hours 7..20)
            int sessions = 0, interruptions = 0, afterHours = 0, finished = 0;
            int[][] heat = new int[7][14];
            for (FocusSession f : store.focusOf(u.id, from)) {
                if (f.running()) continue;
                sessions++; interruptions += f.interruptions; if (f.finished) finished++;
                for (long t = f.start; t < f.end; t += WorkTime.MIN) {
                    LocalDate d = WorkTime.dateOf(t);
                    int mm = WorkTime.minuteOf(t);
                    int row = d.getDayOfWeek().getValue() - 1, col = mm / 60 - 7;
                    if (col >= 0 && col < 14) heat[row][col]++;
                    if (!WorkTime.workday(u, d) || mm < u.workStart || mm >= u.workEnd) afterHours++;
                }
            }

            EstimateCoach.Model model = model(u);
            List<Object> tagFactors = new ArrayList<>();
            model.byTag().forEach((tag, f) -> tagFactors.add(Json.obj("tag", tag, "factor", f.value(), "median", f.median(), "samples", f.samples())));
            tagFactors.sort(Comparator.comparingDouble((Object o) -> -Math.abs(((Number) ((Map<?, ?>) o).get("factor")).doubleValue() - 1)));

            PeakHours.Profile peak = peak(u);
            int[] bw = peak.bestWindow(u);

            // BALANCE SCORE (0-100): four parts, each explained
            double avgFocus = workdays == 0 ? 0 : focusTotal / (double) workdays;
            double focusPart = Math.min(1, avgFocus / Math.max(30, u.focusGoalMin)) * 40;
            double onTimePart = onTimeRate * 30;
            double meetShare = windowTotal == 0 ? 0 : meetingTotal / (double) windowTotal;
            double meetPart = meetShare <= 0.3 ? 15 : meetShare >= 0.7 ? 0 : 15 * (0.7 - meetShare) / 0.4;
            double weeks = Math.max(1, days / 7.0);
            double afterPart = 15 * (1 - Math.min(1, afterHours / weeks / 300.0));
            int balance = (int) Math.round(focusPart + onTimePart + meetPart + afterPart);

            List<Object> parts = List.of(
                    Json.obj("name", "Focus time", "points", Math.round(focusPart), "max", 40, "text", "average " + QuickCapture.duration((int) avgFocus) + " a working day, goal " + QuickCapture.duration(u.focusGoalMin)),
                    Json.obj("name", "On time", "points", Math.round(onTimePart), "max", 30, "text", withDue == 0 ? "no finished tasks with a deadline yet" : onTime + " of " + withDue + " deadlines met"),
                    Json.obj("name", "Meeting load", "points", Math.round(meetPart), "max", 15, "text", Math.round(meetShare * 100) + "% of working time in meetings (up to 30% is healthy)"),
                    Json.obj("name", "Rest", "points", Math.round(afterPart), "max", 15, "text", QuickCapture.duration(afterHours) + " of focus outside working hours"));

            List<String> tips = tips(u, focus, meetings, labels, meetShare, model, peak, bw, afterHours, now, onTimeRate, withDue, sessions, interruptions);

            return Json.obj("days", days, "labels", labels, "focus", focus, "meetings", meetings, "done", done,
                    "totals", Json.obj("focusMin", focusTotal, "meetingMin", meetingTotal, "done", doneTotal, "onTimeRate", Math.round(onTimeRate * 100),
                            "withDue", withDue, "cycleDays", cycle.isEmpty() ? 0 : Math.round(EstimateCoach.median(cycle) * 10) / 10.0,
                            "sessions", sessions, "finishedSessions", finished, "interruptions", interruptions, "afterHoursMin", afterHours,
                            "goalDays", goalDays, "workdays", workdays, "bestStreak", bestStreak),
                    "heat", heat, "peak", peak.hours(), "peakLearned", peak.learned(), "bestWindow", List.of(bw[0], bw[1]),
                    "estimate", Json.obj("factor", model.overall().value(), "median", model.overall().median(), "samples", model.overall().samples(), "tags", tagFactors),
                    "balance", balance, "parts", parts, "tips", tips);
        }
    }

    /** Plain-English advice made from the numbers (each tip is a simple rule). */
    List<String> tips(User u, List<Integer> focus, List<Integer> meetings, List<String> labels, double meetShare,
                      EstimateCoach.Model model, PeakHours.Profile peak, int[] bw, int afterHours, long now,
                      double onTimeRate, int withDue, int sessions, int interruptions) {
        List<String> tips = new ArrayList<>();
        // busiest meeting weekday
        int[] byDow = new int[8]; int[] cnt = new int[8];
        LocalDate today = WorkTime.dateOf(now);
        for (int i = 0; i < meetings.size(); i++) {
            LocalDate d = today.minusDays(meetings.size() - 1 - i);
            byDow[d.getDayOfWeek().getValue()] += meetings.get(i); cnt[d.getDayOfWeek().getValue()]++;
        }
        int worst = 1;
        for (int d = 1; d <= 5; d++) if (cnt[d] > 0 && byDow[d] / (double) cnt[d] > byDow[worst] / (double) Math.max(1, cnt[worst])) worst = d;
        double worstAvg = cnt[worst] == 0 ? 0 : byDow[worst] / (double) cnt[worst];
        if (worstAvg >= 150)
            tips.add(DayOfWeek.of(worst).getDisplayName(TextStyle.FULL, Locale.ENGLISH) + "s average " + QuickCapture.duration((int) worstAvg)
                    + " of meetings. Block " + QuickCapture.hhmm(bw[0]) + "–" + QuickCapture.hhmm(bw[1]) + " as focus time on that day.");
        if (meetShare > 0.4) tips.add(Math.round(meetShare * 100) + "% of your working time is meetings. Ask for an agenda, or reply async where you can.");
        if (peak.learned()) tips.add("You focus best " + QuickCapture.hhmm(bw[0]) + "–" + QuickCapture.hhmm(bw[1]) + ". The planner puts deep work there – keep it free of meetings.");
        if (model.overall().samples() >= 3 && model.overall().value() >= 1.15)
            tips.add("Tasks take you about " + Math.round((model.overall().value() - 1) * 100) + "% longer than you estimate. The planner already adds this.");
        model.byTag().forEach((tag, f) -> {
            if (f.value() >= 1.25) tips.add("#" + tag + " work takes " + Math.round((f.value() - 1) * 100) + "% longer than planned (" + f.samples() + " tasks). Split it into smaller steps.");
        });
        if (sessions >= 5 && interruptions / (double) sessions >= 1.0)
            tips.add("Focus sessions get " + String.format(Locale.ENGLISH, "%.1f", interruptions / (double) sessions) + " interruptions on average. Try turning chat off during a session.");
        if (afterHours >= 120) tips.add(QuickCapture.duration(afterHours) + " of focus was outside working hours. Protect your evenings – the plan is built to fit the day.");
        if (withDue >= 3 && onTimeRate < 0.7) tips.add("Only " + Math.round(onTimeRate * 100) + "% of deadlines were met. Check the deadline radar every morning.");
        int stale = 0;
        for (Task t : store.openTasksOf(u.id)) if (t.due == 0 && now - t.createdAt > 14 * WorkTime.DAY) stale++;
        if (stale > 0) tips.add(stale + " task" + (stale == 1 ? " has" : "s have") + " waited over 2 weeks with no deadline. Give each a day – or drop it.");
        if (tips.isEmpty()) tips.add("Nice balance. Keep planning the day each morning.");
        return tips;
    }

    // ------------------------------------------------------------------ team (leads only)

    public List<User> teamOf(User lead) {
        List<User> out = new ArrayList<>();
        for (User x : store.users.values())
            if (x.active && (lead.admin() ? x.role != com.cecil.dainandini.model.Role.ADMIN : x.team.equals(lead.team))) out.add(x);
        return out;
    }

    public Map<String, Object> team(User lead) {
        if (!lead.lead()) throw Problem.forbidden("Only team leads can see the team page.");
        synchronized (store) {
            long now = Clock.millis();
            List<Object> rows = new ArrayList<>();
            for (User m : teamOf(lead)) rows.add(memberLoad(m, now));
            return Json.obj("team", lead.admin() ? "Everybody" : lead.team, "days", TEAM_DAYS, "members", rows);
        }
    }

    Map<String, Object> memberLoad(User m, long now) {
        EstimateCoach.Model model = model(m);
        List<Task> open = store.openTasksOf(m.id);
        int work = 0, overdue = 0, soon = 0;
        long horizon = WorkTime.at(WorkTime.dateOf(now).plusDays(7), 0);
        List<Object> tasks = new ArrayList<>();
        open.sort(Comparator.comparingLong(t -> t.due == 0 ? Long.MAX_VALUE : t.due));
        for (Task t : open) {
            int r = EstimateCoach.remaining(t, model);
            if (t.due > 0 && t.due < now) overdue++;
            if (t.due == 0 || t.due < horizon) { work += r; soon++; }
            if (tasks.size() < 4) tasks.add(Json.obj("id", t.id, "title", t.title, "due", t.due, "remainingMin", r));
        }
        int cap = capacity(m, now, TEAM_DAYS);
        int meet = 0;
        LocalDate d = WorkTime.dateOf(now);
        for (int i = 0; i < 7; i++) for (CalEvent e : eventsOn(m, d.plusDays(i))) if (e.kind == CalEvent.Kind.MEETING && e.start >= WorkTime.at(d, 0)) meet += e.minutes();
        int load = cap <= 0 ? (work > 0 ? 999 : 0) : (int) Math.round(work * 100.0 / cap);
        int doneWeek = 0;
        for (Task t : store.tasksOf(m.id)) if (t.status == TaskStatus.DONE && t.doneAt > now - 7 * WorkTime.DAY) doneWeek++;
        return Json.obj("user", m.card(), "open", open.size(), "overdue", overdue, "workMin", work, "capacityMin", cap, "load", load,
                "meetingMin", meet, "doneWeek", doneWeek, "tasks", tasks,
                "state", load > 110 ? "over" : load >= 80 ? "full" : "ok");
    }

    /**
     * WHO SHOULD DO IT? For handing a task to someone in the team:
     *   skill    = how many finished tasks (last 90 days) look like this one (title words / tags), 3 or more = 1.0
     *   capacity = 1 − load (load = work ÷ free focus time in the next 5 working days)
     *   score    = 0.6 × skill + 0.4 × capacity
     */
    public List<Map<String, Object>> suggestOwners(User lead, String title, List<String> tags, String exceptUserId) {
        if (!lead.lead()) throw Problem.forbidden("Only team leads can hand out tasks.");
        synchronized (store) {
            long now = Clock.millis();
            Set<String> words = QuickCapture.words(title);
            List<Map<String, Object>> out = new ArrayList<>();
            for (User m : teamOf(lead)) {
                if (m.id.equals(exceptUserId)) continue;
                int similar = 0;
                String example = null;
                for (Task t : store.tasksOf(m.id)) {
                    if (t.status != TaskStatus.DONE || t.doneAt < now - 90 * WorkTime.DAY) continue;
                    double sim = com.cecil.dainandini.ai.EstimateCoach.jaccard(words, QuickCapture.words(t.title));
                    for (String tag : tags) if (t.tags.contains(tag)) sim += 0.3;
                    if (sim >= 0.25) { similar++; if (example == null) example = t.title; }
                }
                double skill = Math.min(1, similar / 3.0);
                Map<String, Object> load = memberLoad(m, now);
                int l = ((Number) load.get("load")).intValue();
                double cap = 1 - Math.min(1, l / 100.0);
                double score = 0.6 * skill + 0.4 * cap;
                List<String> why = new ArrayList<>();
                why.add(similar == 0 ? "has not done similar work yet" : "did " + similar + " similar task" + (similar == 1 ? "" : "s") + (example != null ? " (e.g. “" + example + "”)" : ""));
                why.add(l > 100 ? "already over-loaded (" + l + "%)" : "load " + l + "% for the next " + TEAM_DAYS + " working days");
                out.add(Json.obj("user", m.card(), "score", Math.round(score * 100), "skill", Math.round(skill * 100), "capacity", Math.round(cap * 100),
                        "load", l, "why", why));
            }
            out.sort(Comparator.comparingLong((Map<String, Object> x) -> -((Number) x.get("score")).longValue()));
            return out;
        }
    }
}
