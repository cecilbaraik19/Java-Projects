package com.cecil.sangam.service;

import com.cecil.sangam.core.DependencyGraph;
import com.cecil.sangam.core.EventBus;
import com.cecil.sangam.core.Mentions;
import com.cecil.sangam.model.Channel;
import com.cecil.sangam.model.Priority;
import com.cecil.sangam.model.Project;
import com.cecil.sangam.model.Task;
import com.cecil.sangam.model.TaskStatus;
import com.cecil.sangam.model.User;
import com.cecil.sangam.store.DataStore;
import com.cecil.sangam.util.Clock;
import com.cecil.sangam.util.Json;
import com.cecil.sangam.util.Text;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Projects and tasks: the shared board.
 *
 * Board rules:
 *  - WIP limits: a column may hold at most N tasks ("Doing" and "Review"), and one person at most 2 tasks in Doing.
 *    Remote teams start too many things at once; limits make people finish first.
 *  - A task that waits for an unfinished task cannot be started.
 *  - Review: when the project asks for it, a task goes Doing → Review → Done, and the person who finishes the review
 *    must be someone other than the assignee ("four eyes").
 *  - Two people editing the same task: a field-by-field merge. If they changed different fields, both changes are
 *    kept. Only when both changed the SAME field differently does the second person get asked to choose.
 */
public class WorkService {
    public static final int PERSONAL_WIP = 2;
    public static final int MAX_BLOCKERS = 8;
    public static final List<String> EDITABLE = List.of("title", "description", "priority", "assigneeId", "due", "estimate");
    public static final List<String> COLORS = List.of("red", "blue", "green", "ochre", "violet", "teal");

    private final DataStore store;
    private final EventBus events;

    public WorkService(DataStore store, EventBus events) {
        this.store = store;
        this.events = events;
    }

    // ------------------------------------------------------------------ projects

    public Project createProject(User admin, Map<String, Object> b) {
        Access.admin(admin);
        synchronized (store) {
            String key = Text.clean(Json.str(b, "key"), 5);
            key = key == null ? "" : key.toUpperCase();
            if (!key.matches("[A-Z]{2,5}")) throw Problem.bad("The project code is 2 to 5 capital letters, like ORB.");
            for (Project p : store.projects.values()) if (p.key.equals(key)) throw Problem.conflict("The code " + key + " is already used.");
            String name = Text.clean(Json.str(b, "name"), 60);
            if (Text.blank(name) || name.length() < 3) throw Problem.bad("Give the project a name (3 to 60 characters).");
            Project p = new Project();
            p.id = store.nextId("P", 1);
            p.key = key;
            p.name = name;
            p.description = Text.clean(Json.str(b, "description", ""), 300);
            String color = Json.str(b, "color", "red");
            p.color = COLORS.contains(color) ? color : "red";
            p.createdAt = Clock.millis();
            p.memberIds.add(admin.id);
            for (String id : Json.strings(b, "memberIds")) if (store.users.containsKey(id) && !p.memberIds.contains(id)) p.memberIds.add(id);
            applyLimits(p, b);
            Channel c = new Channel();
            c.id = store.nextId("C", 1);
            c.name = key.toLowerCase();
            c.topic = "Everything about " + name;
            c.kind = Channel.Kind.PROJECT;
            c.projectId = p.id;
            c.createdBy = admin.id;
            c.createdAt = p.createdAt;
            p.channelId = c.id;
            store.projects.put(p.id, p);
            store.channels.put(c.id, c);
            for (String m : p.memberIds) if (!m.equals(admin.id)) store.notify(m, admin.name + " added you to the project " + name, "#/project/" + p.id, false);
            store.log(admin.id, "project.create", key, name);
            store.save("projects", "channels", "notes", "audit");
            events.publish("project", null, null, p.id, Json.obj("id", p.id));
            return p;
        }
    }

    public Project updateProject(User admin, String id, Map<String, Object> b) {
        Access.admin(admin);
        synchronized (store) {
            Project p = store.projects.get(id);
            if (p == null) throw Problem.notFound("Project");
            if (b.containsKey("name")) {
                String name = Text.clean(Json.str(b, "name"), 60);
                if (Text.blank(name) || name.length() < 3) throw Problem.bad("Give the project a name (3 to 60 characters).");
                p.name = name;
            }
            if (b.containsKey("description")) p.description = Text.clean(Json.str(b, "description", ""), 300);
            if (b.containsKey("color") && COLORS.contains(Json.str(b, "color"))) p.color = Json.str(b, "color");
            if (b.containsKey("archived")) p.archived = Json.bool(b, "archived", false);
            applyLimits(p, b);
            if (b.containsKey("memberIds")) {
                List<String> next = new ArrayList<>();
                for (String m : Json.strings(b, "memberIds")) if (store.users.containsKey(m) && !next.contains(m)) next.add(m);
                if (next.isEmpty()) throw Problem.bad("A project needs at least one member.");
                for (String m : next) if (!p.memberIds.contains(m)) store.notify(m, admin.name + " added you to the project " + p.name, "#/project/" + p.id, false);
                for (Task t : store.tasks.values())
                    if (t.projectId.equals(p.id) && t.assigneeId != null && !next.contains(t.assigneeId) && t.status != TaskStatus.DONE) {
                        t.assigneeId = null;   // somebody left: their open tasks become unassigned
                        t.version++;
                        hist(t, admin, "unassigned (person left the project)");
                    }
                p.memberIds = next;
            }
            store.log(admin.id, "project.update", p.key, null);
            store.save("projects", "tasks", "notes", "audit");
            events.publish("project", null, null, p.id, Json.obj("id", p.id));
            return p;
        }
    }

    private static void applyLimits(Project p, Map<String, Object> b) {
        if (b.containsKey("wipDoing")) p.wipDoing = (int) Math.max(0, Math.min(30, Json.num(b, "wipDoing", p.wipDoing)));
        if (b.containsKey("wipReview")) p.wipReview = (int) Math.max(0, Math.min(30, Json.num(b, "wipReview", p.wipReview)));
        if (b.containsKey("reviewRequired")) p.reviewRequired = Json.bool(b, "reviewRequired", p.reviewRequired);
    }

    // ------------------------------------------------------------------ tasks

    public Task create(User u, String projectId, Map<String, Object> b) {
        synchronized (store) {
            Project p = Access.project(store, u, projectId);
            if (!Access.workOn(u, p)) throw Problem.forbidden("Guests can read and comment, but not add tasks.");
            Task t = new Task();
            t.id = store.nextId("T", 1001);
            t.projectId = p.id;
            t.number = p.nextNumber;
            t.key = p.key + "-" + t.number;
            t.title = title(Json.str(b, "title"));
            t.description = Text.blank(Json.str(b, "description")) ? "" : clip(Json.str(b, "description"), 4000);
            t.priority = priority(Json.str(b, "priority", "NORMAL"));
            t.assigneeId = assignee(p, Json.str(b, "assigneeId"));
            t.due = due(Json.str(b, "due"), true);
            t.estimate = estimate(Json.num(b, "estimate", 4));
            t.createdBy = u.id;
            t.createdAt = t.updatedAt = Clock.millis();
            t.updatedBy = u.id;
            t.rank = bottom(p.id, TaskStatus.TODO);
            List<String> blockers = Json.strings(b, "blockedBy");
            store.tasks.put(t.id, t);      // must be in the map for the loop check
            try {
                setBlockersInside(t, blockers);
            } catch (Problem e) {
                store.tasks.remove(t.id);
                throw e;
            }
            p.nextNumber++;
            hist(t, u, "created");
            if (t.assigneeId != null && !t.assigneeId.equals(u.id))
                store.notify(t.assigneeId, u.name + " gave you " + t.key + ": " + t.title, link(t), t.priority == Priority.URGENT);
            store.log(u.id, "task.create", t.key, t.title);
            store.save("tasks", "projects", "notes", "audit");
            changed(t);
            return t;
        }
    }

    /**
     * Edit some fields. The browser sends the new values ("changes") AND the values it saw when the person started
     * editing ("base"). For each field: if nobody else changed it since (base == now), we apply it. If somebody did,
     * and to something different, that field is a conflict – nothing is saved and the person is shown both values.
     */
    public Task update(User u, String id, Map<String, Object> b) {
        synchronized (store) {
            Task t = task(u, id);
            Project p = store.projects.get(t.projectId);
            if (!Access.workOn(u, p)) throw Problem.forbidden("Guests can read and comment, but not edit tasks.");
            Map<String, Object> changes = b.get("changes") instanceof Map<?, ?> ? cast(b.get("changes")) : Map.of();
            Map<String, Object> base = b.get("base") instanceof Map<?, ?> ? cast(b.get("base")) : Map.of();
            Map<String, String> apply = new LinkedHashMap<>();
            List<Map<String, Object>> conflicts = new ArrayList<>();
            for (Map.Entry<String, Object> e : changes.entrySet()) {
                String f = e.getKey();
                if (!EDITABLE.contains(f)) throw Problem.bad("The field " + f + " cannot be edited here.");
                String mine = norm(e.getValue()), now = field(t, f);
                if (!base.containsKey(f)) throw Problem.bad("Send the old value of " + f + " too (base).");
                String was = norm(base.get(f));
                if (Objects.equals(mine, now)) continue;                       // already like that
                if (Objects.equals(was, now)) apply.put(f, mine);              // nobody else touched it
                else conflicts.add(Json.obj("field", f, "yours", mine, "theirs", now, "base", was));
            }
            if (!conflicts.isEmpty())
                throw Problem.conflict(store.userName(t.updatedBy) + " changed this task while you were editing it. Choose which values to keep.")
                        .with(Json.obj("conflicts", conflicts, "version", t.version));
            if (apply.isEmpty()) return t;
            // check everything before changing anything
            String title = apply.containsKey("title") ? title(apply.get("title")) : t.title;
            String desc = apply.containsKey("description") ? (apply.get("description") == null ? "" : clip(apply.get("description"), 4000)) : t.description;
            Priority pr = apply.containsKey("priority") ? priority(apply.get("priority")) : t.priority;
            String who = apply.containsKey("assigneeId") ? assignee(p, apply.get("assigneeId")) : t.assigneeId;
            String due = apply.containsKey("due") ? due(apply.get("due"), false) : t.due;
            int est = apply.containsKey("estimate") ? estimate(parseLong(apply.get("estimate"))) : t.estimate;
            if (who != null && !who.equals(t.assigneeId) && t.status == TaskStatus.DOING && doingCount(who, t.id) >= PERSONAL_WIP)
                throw Problem.conflict(store.userName(who) + " already has " + PERSONAL_WIP + " tasks in Doing.");
            String oldWho = t.assigneeId;
            t.title = title;
            t.description = desc;
            t.priority = pr;
            t.assigneeId = who;
            t.due = due;
            t.estimate = est;
            t.version++;
            t.updatedAt = Clock.millis();
            t.updatedBy = u.id;
            hist(t, u, "changed " + String.join(", ", apply.keySet()));
            if (who != null && !who.equals(oldWho) && !who.equals(u.id))
                store.notify(who, u.name + " gave you " + t.key + ": " + t.title, link(t), t.priority == Priority.URGENT);
            store.log(u.id, "task.edit", t.key, String.join(", ", apply.keySet()));
            store.save("tasks", "notes", "audit");
            changed(t);
            return t;
        }
    }

    /** Move a task to a column (and optionally place it just above another task). */
    public Task move(User u, String id, String statusName, String beforeId) {
        synchronized (store) {
            Task t = task(u, id);
            Project p = store.projects.get(t.projectId);
            if (!Access.workOn(u, p)) throw Problem.forbidden("Guests can read and comment, but not move tasks.");
            TaskStatus to;
            try { to = TaskStatus.valueOf(String.valueOf(statusName)); } catch (IllegalArgumentException e) { throw Problem.bad("Unknown column."); }
            TaskStatus from = t.status;
            if (to != from) checkMove(u, p, t, from, to);
            if (to == TaskStatus.DOING && t.assigneeId == null) t.assigneeId = u.id;
            t.rank = rankBefore(p.id, to, beforeId, t.id);
            long now = Clock.millis();
            if (to != from) {
                t.status = to;
                if (to == TaskStatus.DOING && t.startedAt == 0) t.startedAt = now;
                if (to == TaskStatus.DONE) t.doneAt = now;
                if (from == TaskStatus.DONE) t.doneAt = 0;
                hist(t, u, "moved " + from.label + " → " + to.label);
                store.log(u.id, "task.move", t.key, from + " → " + to);
                afterMove(u, p, t, from, to);
            }
            t.version++;
            t.updatedAt = now;
            t.updatedBy = u.id;
            store.save("tasks", "notes", "audit");
            changed(t);
            return t;
        }
    }

    private void checkMove(User u, Project p, Task t, TaskStatus from, TaskStatus to) {
        if (to != TaskStatus.TODO && DependencyGraph.blocked(store.tasks, t)) {
            List<String> waiting = new ArrayList<>();
            for (String b : t.blockedBy) {
                Task x = store.tasks.get(b);
                if (x != null && x.status != TaskStatus.DONE) waiting.add(x.key + " (" + x.status.label + ")");
            }
            throw Problem.conflict(t.key + " is waiting for " + String.join(", ", waiting) + ". Finish that first.")
                    .with(Json.obj("rule", "blocked"));
        }
        if (to == TaskStatus.DOING) {
            String who = t.assigneeId == null ? u.id : t.assigneeId;
            if (doingCount(who, t.id) >= PERSONAL_WIP)
                throw Problem.conflict(store.userName(who) + " already has " + PERSONAL_WIP + " tasks in Doing. Finish one first – it is the limit per person.")
                        .with(Json.obj("rule", "personal-wip"));
            if (p.wipDoing > 0 && columnCount(p.id, TaskStatus.DOING, t.id) >= p.wipDoing)
                throw Problem.conflict("Doing is full (" + p.wipDoing + " tasks). Help finish something before starting more.")
                        .with(Json.obj("rule", "wip"));
        }
        if (to == TaskStatus.REVIEW) {
            if (t.assigneeId == null) throw Problem.bad("Give the task to somebody before sending it to review.");
            if (p.wipReview > 0 && columnCount(p.id, TaskStatus.REVIEW, t.id) >= p.wipReview)
                throw Problem.conflict("Review is full (" + p.wipReview + " tasks). Review someone's work first.")
                        .with(Json.obj("rule", "wip"));
        }
        if (to == TaskStatus.DONE && p.reviewRequired) {
            if (from != TaskStatus.REVIEW) throw Problem.conflict("This project needs a review: move the task to Review first.").with(Json.obj("rule", "review"));
            if (u.id.equals(t.assigneeId)) throw Problem.forbidden("Someone else must finish the review of your own work (four-eyes rule).");
        }
    }

    private void afterMove(User u, Project p, Task t, TaskStatus from, TaskStatus to) {
        if (to == TaskStatus.REVIEW && t.createdBy != null && !t.createdBy.equals(u.id))
            store.notify(t.createdBy, t.key + " is ready for review: " + t.title, link(t), false);
        if (to == TaskStatus.DONE) {
            if (t.assigneeId != null && !t.assigneeId.equals(u.id))
                store.notify(t.assigneeId, u.name + " approved and finished " + t.key, link(t), false);
            for (Task w : store.tasks.values()) {
                if (!w.blockedBy.contains(t.id) || w.status == TaskStatus.DONE) continue;
                if (!DependencyGraph.blocked(store.tasks, w) && w.assigneeId != null)
                    store.notify(w.assigneeId, w.key + " can start now: " + t.key + " is done.", link(w), false);
            }
        }
        if (from == TaskStatus.DONE)
            for (Task w : store.tasks.values())
                if (w.blockedBy.contains(t.id) && w.status != TaskStatus.TODO && w.assigneeId != null)
                    store.notify(w.assigneeId, t.key + " was opened again – " + w.key + " depends on it.", link(w), false);
    }

    /** Replace the list of tasks this one waits for. */
    public Task setBlockers(User u, String id, List<String> blockerIds) {
        synchronized (store) {
            Task t = task(u, id);
            if (!Access.workOn(u, store.projects.get(t.projectId))) throw Problem.forbidden("Guests cannot change tasks.");
            List<String> before = new ArrayList<>(t.blockedBy);
            setBlockersInside(t, blockerIds);
            if (!before.equals(t.blockedBy)) {
                t.version++;
                t.updatedAt = Clock.millis();
                t.updatedBy = u.id;
                hist(t, u, t.blockedBy.isEmpty() ? "waits for nothing now" : "waits for " + keys(t.blockedBy));
                store.log(u.id, "task.blockers", t.key, keys(t.blockedBy));
                store.save("tasks", "audit");
                changed(t);
            }
            return t;
        }
    }

    private void setBlockersInside(Task t, List<String> ids) {
        Set<String> clean = new LinkedHashSet<>();
        for (String raw : ids) {
            Task b = store.tasks.get(raw);
            if (b == null) b = store.taskByKey(raw);
            if (b == null || !b.projectId.equals(t.projectId)) throw Problem.bad("Unknown task " + raw + " (it must be in the same project).");
            if (b.id.equals(t.id)) throw Problem.bad("A task cannot wait for itself.");
            clean.add(b.id);
        }
        if (clean.size() > MAX_BLOCKERS) throw Problem.bad("At most " + MAX_BLOCKERS + " tasks to wait for.");
        List<String> old = t.blockedBy;
        t.blockedBy = new ArrayList<>();
        for (String b : clean) {
            if (DependencyGraph.wouldLoop(store.tasks, t.id, b)) {
                t.blockedBy = old;
                throw Problem.conflict("That makes a loop: " + store.tasks.get(b).key + " already waits (maybe through others) for " + t.key + ".")
                        .with(Json.obj("rule", "loop"));
            }
            t.blockedBy.add(b);
        }
        if (t.status.active() && DependencyGraph.blocked(store.tasks, t)) {
            t.blockedBy = old;
            throw Problem.conflict(t.key + " is already started; it cannot wait for unfinished work now.");
        }
    }

    public Task comment(User u, String id, String text) {
        synchronized (store) {
            Task t = task(u, id);
            String body = clip(text, 2000);
            if (Text.blank(body)) throw Problem.bad("Write something first.");
            t.comments.add(Json.obj("by", u.id, "at", Clock.millis(), "text", body));
            t.updatedAt = Clock.millis();
            Project p = store.projects.get(t.projectId);
            Set<String> told = new LinkedHashSet<>();
            for (String name : Mentions.names(body)) {
                User m = store.userByName(name);
                if (m != null && !m.id.equals(u.id) && p.has(m.id) && told.add(m.id))
                    store.notify(m.id, u.name + " mentioned you on " + t.key + ": " + Text.clean(body, 80), link(t), false);
            }
            for (String w : new String[] { t.assigneeId, t.createdBy })
                if (w != null && !w.equals(u.id) && told.add(w)) store.notify(w, u.name + " commented on " + t.key, link(t), false);
            store.save("tasks", "notes");
            changed(t);
            return t;
        }
    }

    /** Daily job: tell people about their tasks due today (once per task per day, at the start of their day). */
    public void dueReminders() {
        synchronized (store) {
            String today = Clock.today().toString();
            boolean any = false;
            for (Task t : store.tasks.values()) {
                if (t.status == TaskStatus.DONE || t.assigneeId == null || t.due == null) continue;
                if (t.due.compareTo(today) > 0) continue;
                String flag = "due." + t.id + "." + today;
                if (store.meta.containsKey(flag)) continue;
                store.meta.put(flag, true);
                store.notify(t.assigneeId, (t.due.equals(today) ? "Due today: " : "Overdue: ") + t.key + " " + t.title, link(t), false);
                any = true;
            }
            store.meta.keySet().removeIf(k -> k.startsWith("due.") && !k.endsWith(today));
            if (any) store.save("notes");
        }
    }

    // ------------------------------------------------------------------ helpers

    Task task(User u, String id) {
        Task t = store.tasks.get(id);
        if (t == null) t = store.taskByKey(id);
        if (t == null || !Access.seeProject(u, store.projects.get(t.projectId))) throw Problem.notFound("Task");
        return t;
    }

    private void changed(Task t) {
        events.publish("task", null, null, t.projectId, Json.obj("id", t.id, "key", t.key, "status", t.status, "version", t.version));
    }

    private static void hist(Task t, User u, String what) {
        t.history.add(Json.obj("at", Clock.millis(), "by", u.id, "what", what));
        while (t.history.size() > 60) t.history.remove(0);
    }

    int doingCount(String userId, String except) {
        int n = 0;
        for (Task x : store.tasks.values()) if (x.status == TaskStatus.DOING && userId.equals(x.assigneeId) && !x.id.equals(except)) n++;
        return n;
    }

    int columnCount(String projectId, TaskStatus s, String except) {
        int n = 0;
        for (Task x : store.tasks.values()) if (x.projectId.equals(projectId) && x.status == s && !x.id.equals(except)) n++;
        return n;
    }

    private double bottom(String projectId, TaskStatus s) {
        double max = 0;
        for (Task x : store.tasks.values()) if (x.projectId.equals(projectId) && x.status == s) max = Math.max(max, x.rank);
        return max + 1;
    }

    /** A rank that puts the task just above `beforeId` (or at the bottom). Halving the gap = no renumbering needed. */
    private double rankBefore(String projectId, TaskStatus s, String beforeId, String self) {
        Task before = beforeId == null ? null : store.tasks.get(beforeId);
        if (before == null || before.id.equals(self) || !before.projectId.equals(projectId) || before.status != s) {
            double max = 0;
            for (Task x : store.tasks.values()) if (x.projectId.equals(projectId) && x.status == s && !x.id.equals(self)) max = Math.max(max, x.rank);
            return max + 1;
        }
        double prev = before.rank - 1;
        for (Task x : store.tasks.values())
            if (x.projectId.equals(projectId) && x.status == s && !x.id.equals(self) && x.rank < before.rank && x.rank > prev) prev = x.rank;
        return (prev + before.rank) / 2;
    }

    private String keys(List<String> ids) {
        List<String> k = new ArrayList<>();
        for (String id : ids) { Task t = store.tasks.get(id); k.add(t == null ? id : t.key); }
        return String.join(", ", k);
    }

    static String link(Task t) { return "#/task/" + t.id; }

    static String field(Task t, String f) {
        return switch (f) {
            case "title" -> t.title;
            case "description" -> t.description == null || t.description.isEmpty() ? null : t.description;
            case "priority" -> t.priority.name();
            case "assigneeId" -> t.assigneeId;
            case "due" -> t.due;
            case "estimate" -> String.valueOf(t.estimate);
            default -> null;
        };
    }

    /** Values from the browser as text, with "" meaning null, so "4" and 4 compare equal. */
    static String norm(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return String.valueOf(n.longValue());
        String s = String.valueOf(v);
        return s.isEmpty() ? null : s;
    }

    private static long parseLong(String s) {
        try { return s == null ? 4 : Long.parseLong(s.trim()); } catch (NumberFormatException e) { throw Problem.bad("Estimate is a whole number of hours."); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) { return (Map<String, Object>) o; }

    private static String title(String s) {
        String t = Text.clean(s, 120);
        if (Text.blank(t) || t.length() < 3) throw Problem.bad("A task title needs 3 to 120 characters.");
        return t;
    }

    static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.replace("\r\n", "\n").strip();
        StringBuilder sb = new StringBuilder();
        for (char c : t.toCharArray()) if (c == '\n' || c == '\t' || !Character.isISOControl(c)) sb.append(c);
        return sb.length() > max ? sb.substring(0, max) : sb.toString();
    }

    private static Priority priority(String s) {
        try { return Priority.valueOf(s == null ? "NORMAL" : s); } catch (IllegalArgumentException e) { throw Problem.bad("Unknown priority."); }
    }

    private String assignee(Project p, String id) {
        if (Text.blank(id)) return null;
        User a = store.users.get(id);
        if (a == null || !a.active || !p.has(id)) throw Problem.bad("The person must be a member of the project.");
        if (a.guest()) throw Problem.bad("Tasks cannot be given to guests.");
        return id;
    }

    private static String due(String s, boolean mustBeFuture) {
        if (Text.blank(s)) return null;
        try {
            LocalDate d = LocalDate.parse(s.trim());
            if (d.isAfter(Clock.today().plusYears(2))) throw Problem.bad("The due date is too far away.");
            if (mustBeFuture && d.isBefore(Clock.today())) throw Problem.bad("The due date is in the past.");
            return d.toString();
        } catch (DateTimeParseException e) {
            throw Problem.bad("Dates look like 2026-11-20.");
        }
    }

    private static int estimate(long h) {
        if (h < 1 || h > 80) throw Problem.bad("Estimate: 1 to 80 hours.");
        return (int) h;
    }
}
