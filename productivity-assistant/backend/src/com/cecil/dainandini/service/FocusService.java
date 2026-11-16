package com.cecil.dainandini.service;

import com.cecil.dainandini.ai.WorkTime;
import com.cecil.dainandini.model.FocusSession;
import com.cecil.dainandini.model.Task;
import com.cecil.dainandini.model.TaskStatus;
import com.cecil.dainandini.model.User;
import com.cecil.dainandini.store.DataStore;
import com.cecil.dainandini.util.Clock;
import com.cecil.dainandini.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Focus sessions (the Pomodoro idea): pick ONE task, set a timer (default 25 minutes), work only on that.
 * Pressing "Interrupted" counts interruptions. When it stops, the minutes are added to the task's real time –
 * this is what teaches the estimate coach and the peak-hours model.
 *
 *  - one running session per person;
 *  - a session that is forgotten is stopped by the background job at its planned end (counted as finished).
 */
public class FocusService {
    public static final long FORGOTTEN_MS = 30 * WorkTime.MIN;

    private final DataStore store;
    private final TaskService tasks;
    private final Brain brain;

    public FocusService(DataStore store, TaskService tasks, Brain brain) { this.store = store; this.tasks = tasks; this.brain = brain; }

    public FocusSession start(User u, String taskId, int minutes) {
        if (minutes < 5 || minutes > 120) throw Problem.bad("A focus session is 5 to 120 minutes.");
        synchronized (store) {
            if (store.runningFocus(u.id) != null) throw Problem.conflict("A focus session is already running. Stop it first.");
            Task t = tasks.editable(u, taskId);
            if (!t.open()) throw Problem.conflict("This task is already finished.");
            if (t.status == TaskStatus.TODO) { t.status = TaskStatus.DOING; t.startedAt = Clock.millis(); }
            FocusSession f = new FocusSession();
            f.id = store.nextId("F", 1);
            f.userId = u.id;
            f.taskId = t.id;
            f.start = Clock.millis();
            f.plannedMin = minutes;
            store.focus.put(f.id, f);
            store.save("focus", "tasks");
            return f;
        }
    }

    public FocusSession interrupt(User u) {
        synchronized (store) {
            FocusSession f = store.runningFocus(u.id);
            if (f == null) throw Problem.conflict("No focus session is running.");
            if (f.interruptions < 99) f.interruptions++;
            store.save("focus");
            return f;
        }
    }

    /** Stops the running session. markDone = also finish the task. */
    public FocusSession stop(User u, boolean markDone) {
        synchronized (store) {
            FocusSession f = store.runningFocus(u.id);
            if (f == null) throw Problem.conflict("No focus session is running.");
            end(f, Clock.millis());
            if (markDone) {
                Task t = store.tasks.get(f.taskId);
                if (t != null && t.open()) tasks.done(u, t.id, null);
            }
            store.save("focus", "tasks");
            return f;
        }
    }

    private void end(FocusSession f, long at) {
        long planned = f.start + f.plannedMin * WorkTime.MIN;
        f.end = Math.max(f.start + WorkTime.MIN, Math.min(at, planned + FORGOTTEN_MS));
        f.finished = f.end >= planned - WorkTime.MIN;
        Task t = store.tasks.get(f.taskId);
        if (t != null) t.actualMin += f.minutes();
    }

    /** Background job: stops sessions that were left running long after their planned end. */
    public int stopForgotten() {
        synchronized (store) {
            long now = Clock.millis();
            int n = 0;
            for (FocusSession f : store.focus.values()) {
                if (!f.running()) continue;
                long planned = f.start + f.plannedMin * WorkTime.MIN;
                if (now > planned + FORGOTTEN_MS) { end(f, planned); n++; }
            }
            if (n > 0) store.save("focus", "tasks");
            return n;
        }
    }

    public Map<String, Object> page(User u) {
        synchronized (store) {
            long now = Clock.millis();
            FocusSession run = store.runningFocus(u.id);
            List<FocusSession> recent = store.focusOf(u.id, now - 14 * WorkTime.DAY);
            recent.removeIf(FocusSession::running);
            recent.sort((a, b) -> Long.compare(b.start, a.start));
            List<Object> list = new ArrayList<>();
            for (FocusSession f : recent.subList(0, Math.min(25, recent.size()))) list.add(brain.focusJson(f));
            long dayStart = WorkTime.at(WorkTime.dateOf(now), 0);
            int today = brain.focusMinutes(u, dayStart, now);
            var peak = brain.peak(u);
            int[] bw = peak.bestWindow(u);
            return Json.obj("running", run == null ? null : brain.focusJson(run), "recent", list, "todayMin", today, "goalMin", u.focusGoalMin,
                    "peak", peak.hours(), "learned", peak.learned(), "bestWindow", List.of(bw[0], bw[1]), "now", now);
        }
    }
}
