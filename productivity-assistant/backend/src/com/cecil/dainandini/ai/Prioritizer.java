package com.cecil.dainandini.ai;

import com.cecil.dainandini.model.Energy;
import com.cecil.dainandini.model.Importance;
import com.cecil.dainandini.model.Task;
import com.cecil.dainandini.model.User;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * PRIORITY SCORE (0–100) and the EISENHOWER MATRIX.
 *
 *   score = importance (10 / 25 / 40)
 *         + urgency    (0 … 45: overdue 45 · not enough working time left (SLACK below zero) 40 · due today 32 ·
 *                       due by the next working day 22 · within a working week 12 · later 4)
 *                       SLACK = working minutes left before the deadline − minutes of work still to do
 *         + age        (0 … 10, one point for every 3 days it has waited – so old tasks do not wait for ever)
 *         + planned for today (+5 when the person picked today as the day to do it)
 *
 * Every point comes with a reason in plain words, so the person can see WHY a task is on top
 * (explainable AI instead of a black box).
 *
 * Eisenhower matrix:  urgent = overdue or due by the end of the next working day;  important = HIGH importance,
 * or MEDIUM deep work.  DO = both · PLAN = important only · HAND OVER = urgent only · LATER = neither.
 */
public final class Prioritizer {
    private Prioritizer() { }

    public enum Quadrant { DO, PLAN, DELEGATE, LATER }

    public record Score(int total, int importance, int urgency, int age, int today, long slackMin, boolean overdue,
                        boolean urgent, boolean important, Quadrant quadrant, List<String> reasons) { }

    /** remainingMin = work still to do (already corrected for how the person usually under-estimates). */
    public static Score score(Task t, User u, long now, int remainingMin) {
        List<String> why = new ArrayList<>();
        int imp = t.importance.points;
        why.add(switch (t.importance) {
            case HIGH -> "marked important";
            case MEDIUM -> "normal importance";
            case LOW -> "low importance";
        });

        int urg = 0;
        long slack = Long.MAX_VALUE;
        boolean overdue = false;
        LocalDate today = WorkTime.dateOf(now);
        LocalDate nextWork = WorkTime.nextWorkday(u, today.plusDays(1));
        boolean urgent = false;
        if (t.due > 0) {
            long left = WorkTime.workingMinutes(u, now, t.due);
            slack = left - remainingMin;
            LocalDate dd = WorkTime.dateOf(t.due);
            long days = java.time.temporal.ChronoUnit.DAYS.between(today, dd);
            String when = days == 0 ? "due today" : days == 1 ? "due tomorrow" : "due in " + days + " days";
            urgent = !dd.isAfter(nextWork);
            if (t.due < now) { urg = 45; overdue = true; urgent = true; why.add("overdue"); }
            else if (slack < 0) { urg = 40; why.add(when + " – not enough working time left (" + QuickCapture.duration((int) -slack) + " short)"); }
            else if (!dd.isAfter(today)) { urg = 32; why.add(when); }
            else if (!dd.isAfter(nextWork)) { urg = 22; why.add(when); }
            else if (left <= 5L * workday(u)) { urg = 12; why.add(when); }
            else { urg = 4; why.add(when); }
        } else {
            why.add("no deadline");
        }

        int days = (int) Math.max(0, (now - t.createdAt) / WorkTime.DAY);
        int age = Math.min(10, days / 3);
        if (age >= 3) why.add("waiting for " + days + " days");

        int todayPts = 0;
        if (t.doDate != null && !LocalDate.parse(t.doDate).isAfter(WorkTime.dateOf(now))) { todayPts = 5; why.add("planned for today"); }

        int total = Math.min(100, imp + urg + age + todayPts);
        boolean important = t.importance == Importance.HIGH || (t.importance == Importance.MEDIUM && t.energy == Energy.DEEP);
        Quadrant q = urgent && important ? Quadrant.DO : important ? Quadrant.PLAN : urgent ? Quadrant.DELEGATE : Quadrant.LATER;
        return new Score(total, imp, urg, age, todayPts, slack == Long.MAX_VALUE ? -1 : slack, overdue, urgent, important, q, why);
    }

    static long workday(User u) { return Math.max(60, u.workEnd - u.workStart - u.lunchMin); }
}
