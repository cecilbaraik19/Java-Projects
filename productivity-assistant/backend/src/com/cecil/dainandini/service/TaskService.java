package com.cecil.dainandini.service;

import com.cecil.dainandini.ai.EstimateCoach;
import com.cecil.dainandini.ai.Prioritizer;
import com.cecil.dainandini.ai.QuickCapture;
import com.cecil.dainandini.ai.WorkTime;
import com.cecil.dainandini.model.Energy;
import com.cecil.dainandini.model.Importance;
import com.cecil.dainandini.model.Task;
import com.cecil.dainandini.model.TaskStatus;
import com.cecil.dainandini.model.User;
import com.cecil.dainandini.store.DataStore;
import com.cecil.dainandini.util.Clock;
import com.cecil.dainandini.util.Json;
import com.cecil.dainandini.util.Text;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tasks: quick capture, edit, start, finish (with repeating tasks), drop, hand over.
 *
 * Rules:
 *  - you see your own tasks; a team lead also sees the tasks of their team (to balance the workload);
 *  - only a team lead may give a task to a team member ("@meera" in quick capture, or the Team page);
 *  - a finished repeating task makes its next copy straight away.
 */
public class TaskService {
    public static final int MAX_TITLE = 140;
    public static final int MAX_NOTES = 4000;
    public static final int MAX_OPEN = 400;

    private final DataStore store;
    private final Brain brain;

    public TaskService(DataStore store, Brain brain) { this.store = store; this.brain = brain; }

    // ------------------------------------------------------------------ access

    public boolean canSee(User u, Task t) {
        if (t == null) return false;
        if (u.id.equals(t.ownerId) || u.id.equals(t.createdBy) || u.admin()) return true;
        User owner = store.users.get(t.ownerId);
        return u.lead() && owner != null && owner.team.equals(u.team);
    }

    public boolean canEdit(User u, Task t) { return t != null && (u.id.equals(t.ownerId) || u.id.equals(t.createdBy)); }

    /** May this person give work to that person? (leads: their team; admin: everybody) */
    public boolean mayAssign(User u, User to) {
        if (to == null || !to.active) return false;
        if (u.id.equals(to.id)) return true;
        return u.admin() || (u.role == com.cecil.dainandini.model.Role.LEAD && to.team.equals(u.team));
    }

    Task get(User u, String id) {
        Task t = store.tasks.get(id);
        if (!canSee(u, t)) throw Problem.notFound("Task");
        return t;
    }

    Task editable(User u, String id) {
        Task t = get(u, id);
        if (!canEdit(u, t)) throw Problem.forbidden("Only the owner of this task can change it.");
        return t;
    }

    // ------------------------------------------------------------------ quick capture

    /** What quick capture understood (shown live while typing). Nothing is saved. */
    public Map<String, Object> preview(User u, String text) {
        synchronized (store) {
            long now = Clock.millis();
            QuickCapture.Result r = QuickCapture.parse(text, u, now);
            User to = r.mention == null ? null : store.userByMention(r.mention);
            boolean assign = to != null && !r.mentionInSentence && !to.id.equals(u.id) && mayAssign(u, to);
            EstimateCoach.Guess g = r.estimateMin == 0 ? EstimateCoach.guess(r.title, r.tags, store.tasksOf(assign ? to.id : u.id)) : null;
            List<Object> chips = new ArrayList<>();
            for (QuickCapture.Chip c : r.chips) {
                if (c.kind().equals("person")) {
                    if (to == null) chips.add(Json.obj("kind", "person", "label", c.label() + " – nobody with that name"));
                    else if (assign) chips.add(Json.obj("kind", "person", "label", "For " + to.name));
                    else if (!r.mentionInSentence && !to.id.equals(u.id)) chips.add(Json.obj("kind", "person", "label", "Mentions " + to.firstName() + " (only team leads can give tasks)"));
                    else chips.add(Json.obj("kind", "person", "label", "Mentions " + to.firstName()));
                } else chips.add(c.toJson());
            }
            if (g != null) chips.add(Json.obj("kind", "estimate", "label", "~" + QuickCapture.duration(g.minutes()) + " (guess)"));
            return Json.obj("title", assign ? r.title : r.titleWithMention, "due", r.due, "dueHasTime", r.dueHasTime,
                    "importance", r.importance == null ? "MEDIUM" : r.importance, "estimateMin", r.estimateMin > 0 ? r.estimateMin : g != null ? g.minutes() : 0,
                    "estimateGuessed", r.estimateMin == 0 && g != null, "similar", g == null ? List.of() : g.similarTitles(),
                    "tags", r.tags, "energy", r.energy, "energyWord", r.energyWord, "recurrence", r.recurrence,
                    "assignee", assign ? Json.obj("id", to.id, "name", to.name) : null, "chips", chips);
        }
    }

    /** Makes a task from {text} (quick capture) and/or explicit fields. */
    public Task create(User u, Map<String, Object> b) {
        synchronized (store) {
            long now = Clock.millis();
            Task t = new Task();
            t.createdBy = u.id;
            t.ownerId = u.id;
            String text = Json.str(b, "text");
            if (!Text.blank(text)) {
                QuickCapture.Result r = QuickCapture.parse(text, u, now);
                User to = r.mention == null ? null : store.userByMention(r.mention);
                boolean assign = to != null && !r.mentionInSentence && !to.id.equals(u.id) && mayAssign(u, to);
                if (assign) t.ownerId = to.id;
                t.title = assign ? r.title : r.titleWithMention;
                t.due = r.due; t.dueHasTime = r.dueHasTime;
                if (r.importance != null) t.importance = r.importance;
                t.estimateMin = r.estimateMin;
                t.tags.addAll(r.tags);
                t.energy = r.energy;
                t.recurrence = r.recurrence;
            }
            apply(u, t, b, true);
            if (Text.blank(t.title)) throw Problem.bad("Please type what the task is.");
            int open = store.openTasksOf(t.ownerId).size();
            if (open >= MAX_OPEN) throw Problem.bad("That list already has " + MAX_OPEN + " open tasks. Finish or drop some first.");
            if (t.estimateMin == 0) {
                EstimateCoach.Guess g = EstimateCoach.guess(t.title, t.tags, store.tasksOf(t.ownerId));
                if (g != null) { t.estimateMin = g.minutes(); t.estimateGuessed = true; }
                else { t.estimateMin = t.energy == Energy.DEEP ? 60 : 20; t.estimateGuessed = true; }
            }
            t.id = store.nextId("T", 1001);
            t.createdAt = now;
            t.updatedAt = now;
            store.tasks.put(t.id, t);
            store.log(u.id, "task.create", t.id, t.ownerId.equals(u.id) ? t.title : t.title + " → " + store.userName(t.ownerId));
            store.save("tasks", "audit");
            return t;
        }
    }

    /** Copies the fields from the request onto the task (with checks). */
    private void apply(User u, Task t, Map<String, Object> b, boolean isNew) {
        if (b.containsKey("title")) t.title = Text.clean(Json.str(b, "title"), MAX_TITLE);
        if (t.title != null && t.title.length() > MAX_TITLE) t.title = t.title.substring(0, MAX_TITLE);
        if (b.containsKey("notes")) {
            String n = Json.str(b, "notes", "");
            if (n.length() > MAX_NOTES) throw Problem.bad("Notes are too long (max " + MAX_NOTES + " characters).");
            t.notes = n.trim();
        }
        if (b.containsKey("importance")) t.importance = enumOf(Importance.class, Json.str(b, "importance"), "importance");
        if (b.containsKey("energy")) t.energy = enumOf(Energy.class, Json.str(b, "energy"), "kind of work");
        if (b.containsKey("estimateMin")) {
            long e = Json.num(b, "estimateMin", -1);
            if (e < 0 || e > 960) throw Problem.bad("The estimate must be between 0 and 960 minutes (16 hours).");
            if (e != t.estimateMin) t.estimateGuessed = false;
            t.estimateMin = (int) e;
        }
        if (b.containsKey("due")) {
            long d = Json.num(b, "due", 0);
            if (d < 0) throw Problem.bad("Bad due date.");
            t.due = d;
            t.dueHasTime = Json.bool(b, "dueHasTime", d > 0 && WorkTime.minuteOf(d) != store.users.get(t.ownerId).workEnd);
        }
        if (b.containsKey("dueDate")) {   // "2026-11-20" (+ optional "dueTime": "15:30")
            String ds = Json.str(b, "dueDate");
            if (Text.blank(ds)) { t.due = 0; t.dueHasTime = false; }
            else {
                LocalDate d = date(ds);
                String tm = Json.str(b, "dueTime");
                User owner = store.users.get(t.ownerId);
                if (Text.blank(tm)) { t.due = WorkTime.at(d, owner.workEnd); t.dueHasTime = false; }
                else { t.due = WorkTime.at(d, minutes(tm)); t.dueHasTime = true; }
            }
        }
        if (b.containsKey("doDate")) {
            String ds = Json.str(b, "doDate");
            t.doDate = Text.blank(ds) ? null : date(ds).toString();
        }
        if (b.containsKey("tags")) {
            t.tags.clear();
            for (String s : Json.strings(b, "tags")) {
                String tag = s.trim().replaceFirst("^#", "").toLowerCase(Locale.ROOT);
                if (tag.matches("[\\p{L}\\d_-]{1,30}") && !t.tags.contains(tag) && t.tags.size() < 6) t.tags.add(tag);
            }
        }
        if (b.containsKey("recurrence")) {
            String r = Json.str(b, "recurrence", "");
            if (!r.isEmpty() && !r.matches("DAILY|WEEKDAYS|MONTHLY|WEEKLY:[1-7]")) throw Problem.bad("Unknown repeat rule.");
            t.recurrence = r;
        }
        if (isNew && b.containsKey("ownerId") && !Text.blank(Json.str(b, "ownerId"))) {
            User to = store.users.get(Json.str(b, "ownerId"));
            if (!mayAssign(u, to)) throw Problem.forbidden("You can only give tasks to people in your own team.");
            t.ownerId = to.id;
        }
    }

    static <E extends Enum<E>> E enumOf(Class<E> c, String v, String what) {
        try { return Enum.valueOf(c, v == null ? "" : v.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw Problem.bad("Unknown " + what + "."); }
    }

    static LocalDate date(String s) {
        try {
            LocalDate d = LocalDate.parse(s.trim());
            if (d.getYear() < 2000 || d.getYear() > 2100) throw Problem.bad("Unknown date.");
            return d;
        } catch (DateTimeParseException e) { throw Problem.bad("Dates look like 2026-11-20."); }
    }

    static int minutes(String hhmm) {
        if (hhmm == null || !hhmm.trim().matches("([01]?\\d|2[0-3]):[0-5]\\d")) throw Problem.bad("Times look like 14:30.");
        String[] p = hhmm.trim().split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }

    // ------------------------------------------------------------------ changes

    public Task update(User u, String id, Map<String, Object> b) {
        synchronized (store) {
            Task t = editable(u, id);
            apply(u, t, b, false);
            if (Text.blank(t.title)) throw Problem.bad("The title cannot be empty.");
            t.updatedAt = Clock.millis();
            store.log(u.id, "task.edit", t.id, t.title);
            store.save("tasks", "audit");
            return t;
        }
    }

    public Task start(User u, String id) {
        synchronized (store) {
            Task t = editable(u, id);
            if (!t.open()) throw Problem.conflict("This task is already " + t.status.name().toLowerCase(Locale.ROOT) + ".");
            if (t.status == TaskStatus.TODO) { t.status = TaskStatus.DOING; t.startedAt = Clock.millis(); }
            t.updatedAt = Clock.millis();
            store.save("tasks");
            return t;
        }
    }

    /** Finish a task. actualMin (optional) = minutes the person says it took in total. Returns the next copy of a repeating task, or null. */
    public Task done(User u, String id, Map<String, Object> b) {
        synchronized (store) {
            Task t = editable(u, id);
            if (!t.open()) throw Problem.conflict("This task is already " + t.status.name().toLowerCase(Locale.ROOT) + ".");
            long now = Clock.millis();
            if (b != null && b.containsKey("actualMin")) {
                long a = Json.num(b, "actualMin", -1);
                if (a < 0 || a > 6000) throw Problem.bad("Time spent must be between 0 and 6000 minutes.");
                if (a > t.actualMin) t.actualMin = (int) a;
            }
            t.status = TaskStatus.DONE;
            t.doneAt = now;
            t.updatedAt = now;
            Task next = null;
            if (!t.recurrence.isEmpty()) next = repeat(t);
            store.log(u.id, "task.done", t.id, t.title + (t.actualMin > 0 ? " (" + t.actualMin + " min)" : ""));
            store.save("tasks", "audit");
            return next;
        }
    }

    private Task repeat(Task t) {
        User owner = store.users.get(t.ownerId);
        LocalDate base = t.due > 0 ? WorkTime.dateOf(t.due) : WorkTime.dateOf(Clock.millis());
        LocalDate today = WorkTime.dateOf(Clock.millis());
        LocalDate nd = QuickCapture.nextRepeat(t.recurrence, base, owner);
        while (nd != null && nd.isBefore(today)) nd = QuickCapture.nextRepeat(t.recurrence, nd, owner);
        if (nd == null) return null;
        Task n = new Task();
        n.id = store.nextId("T", 1001);
        n.ownerId = t.ownerId; n.createdBy = t.createdBy; n.title = t.title; n.notes = t.notes; n.importance = t.importance;
        n.energy = t.energy; n.estimateMin = t.estimateMin; n.estimateGuessed = t.estimateGuessed; n.tags.addAll(t.tags);
        n.recurrence = t.recurrence; n.dueHasTime = t.dueHasTime;
        int minute = t.due > 0 ? WorkTime.minuteOf(t.due) : owner.workEnd;
        n.due = WorkTime.at(nd, minute);
        n.createdAt = Clock.millis(); n.updatedAt = n.createdAt;
        store.tasks.put(n.id, n);
        return n;
    }

    public Task reopen(User u, String id) {
        synchronized (store) {
            Task t = editable(u, id);
            if (t.open()) return t;
            t.status = t.startedAt > 0 ? TaskStatus.DOING : TaskStatus.TODO;
            t.doneAt = 0;
            t.updatedAt = Clock.millis();
            store.log(u.id, "task.reopen", t.id, t.title);
            store.save("tasks", "audit");
            return t;
        }
    }

    public Task drop(User u, String id) {
        synchronized (store) {
            Task t = editable(u, id);
            if (!t.open()) throw Problem.conflict("Only open tasks can be dropped.");
            t.status = TaskStatus.DROPPED;
            t.doneAt = Clock.millis();
            t.updatedAt = t.doneAt;
            store.log(u.id, "task.drop", t.id, t.title);
            store.save("tasks", "audit");
            return t;
        }
    }

    public void delete(User u, String id) {
        synchronized (store) {
            Task t = editable(u, id);
            store.tasks.remove(t.id);
            store.log(u.id, "task.delete", t.id, t.title);
            store.save("tasks", "audit");
        }
    }

    /** Add minutes spent by hand (work done away from a focus session). */
    public Task logTime(User u, String id, int minutes) {
        if (minutes < 1 || minutes > 600) throw Problem.bad("Add between 1 and 600 minutes.");
        synchronized (store) {
            Task t = editable(u, id);
            t.actualMin += minutes;
            t.updatedAt = Clock.millis();
            store.save("tasks");
            return t;
        }
    }

    /** A lead gives (or moves) a task to someone in the team. */
    public Task assign(User u, String id, String toId) {
        synchronized (store) {
            Task t = get(u, id);
            if (!t.open()) throw Problem.conflict("Only open tasks can be handed over.");
            User to = store.users.get(toId);
            if (!u.id.equals(t.ownerId) && !u.lead()) throw Problem.forbidden("Only the owner or a team lead can hand this task over.");
            if (!mayAssign(u, to)) throw Problem.forbidden("You can only give tasks to people in your own team.");
            String from = store.userName(t.ownerId);
            t.ownerId = to.id;
            if (!u.id.equals(to.id)) t.createdBy = u.id;
            t.updatedAt = Clock.millis();
            store.log(u.id, "task.assign", t.id, t.title + ": " + from + " → " + to.name);
            store.save("tasks", "audit");
            return t;
        }
    }

    // ------------------------------------------------------------------ lists

    /** filter: open (default), today, done, all.  Sorted by priority score. Includes the Eisenhower matrix. */
    public Map<String, Object> list(User u, String filter, String tag) {
        synchronized (store) {
            long now = Clock.millis();
            EstimateCoach.Model m = brain.model(u);
            LocalDate today = WorkTime.dateOf(now);
            long endToday = WorkTime.at(today.plusDays(1), 0);
            List<Map<String, Object>> rows = new ArrayList<>();
            Map<String, Integer> tags = new LinkedHashMap<>();
            String f = filter == null ? "open" : filter;
            for (Task t : store.tasksOf(u.id)) {
                for (String tg : t.tags) if (t.open()) tags.merge(tg, 1, Integer::sum);
                boolean ok = switch (f) {
                    case "done" -> t.status == TaskStatus.DONE || t.status == TaskStatus.DROPPED;
                    case "today" -> t.open() && ((t.due > 0 && t.due < endToday) || (t.doDate != null && !LocalDate.parse(t.doDate).isAfter(today)));
                    case "all" -> true;
                    default -> t.open();
                };
                if (ok && tag != null && !tag.isBlank() && !t.tags.contains(tag)) ok = false;
                if (ok) rows.add(brain.taskJson(t, now, m));
            }
            if (f.equals("done")) rows.sort(Comparator.comparingLong((Map<String, Object> x) -> -((Number) x.get("doneAt")).longValue()));
            else rows.sort(Comparator.comparingInt((Map<String, Object> x) -> -((Number) x.getOrDefault("score", -1)).intValue())
                    .thenComparingLong(x -> ((Number) x.get("due")).longValue() == 0 ? Long.MAX_VALUE : ((Number) x.get("due")).longValue()));
            if (rows.size() > 300) rows = new ArrayList<>(rows.subList(0, 300));
            Map<String, List<Object>> matrix = new LinkedHashMap<>();
            for (Prioritizer.Quadrant q : Prioritizer.Quadrant.values()) matrix.put(q.name(), new ArrayList<>());
            for (Map<String, Object> r : rows) if (r.get("quadrant") != null) matrix.get(String.valueOf(r.get("quadrant"))).add(r);
            return Json.obj("tasks", rows, "matrix", matrix, "tags", tags, "filter", f,
                    "factor", m.overall().value());
        }
    }

    public Map<String, Object> view(User u, String id) {
        synchronized (store) {
            Task t = get(u, id);
            long now = Clock.millis();
            User owner = store.users.get(t.ownerId);
            Map<String, Object> j = brain.taskJson(t, now, brain.model(owner));
            j.put("canEdit", canEdit(u, t));
            List<Object> sessions = new ArrayList<>();
            for (var f : store.focus.values()) if (t.id.equals(f.taskId) && !f.running()) sessions.add(brain.focusJson(f));
            j.put("sessions", sessions);
            j.put("sourceNote", t.sourceNoteId.isEmpty() || store.notes.get(t.sourceNoteId) == null ? null
                    : Json.obj("id", t.sourceNoteId, "title", store.notes.get(t.sourceNoteId).title));
            return j;
        }
    }
}
