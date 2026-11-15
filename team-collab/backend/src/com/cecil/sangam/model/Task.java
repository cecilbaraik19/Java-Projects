package com.cecil.sangam.model;

import com.cecil.sangam.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A task on a project board.
 * "version" goes up by one on every change: it lets two people edit at the same time without losing work
 * (see WorkService.update – a field-by-field merge).
 */
public class Task implements Json.Writable {
    public String id;
    public String projectId;
    public int number;
    /** For example "ORB-12". */
    public String key;
    public String title;
    public String description = "";
    public TaskStatus status = TaskStatus.TODO;
    public Priority priority = Priority.NORMAL;
    public String assigneeId;
    public String createdBy;
    /** Due date (yyyy-mm-dd) or null. */
    public String due;
    /** Estimated work in hours (used for the critical path). */
    public int estimate = 4;
    /** Ids of tasks that must be done before this one can start. */
    public List<String> blockedBy = new ArrayList<>();
    public long version = 1;
    public long createdAt, updatedAt, startedAt, doneAt;
    public String updatedBy;
    /** Order inside a column (smaller = higher up). */
    public double rank;
    /** Each comment: {by, at, text}. */
    public List<Map<String, Object>> comments = new ArrayList<>();
    /** Each line: {at, by, what}. */
    public List<Map<String, Object>> history = new ArrayList<>();

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "projectId", projectId, "number", number, "key", key, "title", title,
                "description", description, "status", status, "priority", priority, "assigneeId", assigneeId,
                "createdBy", createdBy, "due", due, "estimate", estimate, "blockedBy", blockedBy, "version", version,
                "createdAt", createdAt, "updatedAt", updatedAt, "startedAt", startedAt, "doneAt", doneAt,
                "updatedBy", updatedBy, "rank", rank, "comments", comments, "history", history);
    }

    @SuppressWarnings("unchecked")
    public static Task fromJson(Map<String, Object> m) {
        Task t = new Task();
        t.id = Json.str(m, "id");
        t.projectId = Json.str(m, "projectId");
        t.number = (int) Json.num(m, "number", 0);
        t.key = Json.str(m, "key");
        t.title = Json.str(m, "title");
        t.description = Json.str(m, "description", "");
        t.status = TaskStatus.valueOf(Json.str(m, "status", "TODO"));
        t.priority = Priority.valueOf(Json.str(m, "priority", "NORMAL"));
        t.assigneeId = Json.str(m, "assigneeId");
        t.createdBy = Json.str(m, "createdBy");
        t.due = Json.str(m, "due");
        t.estimate = (int) Json.num(m, "estimate", 4);
        t.blockedBy = new ArrayList<>(Json.strings(m, "blockedBy"));
        t.version = Json.num(m, "version", 1);
        t.createdAt = Json.num(m, "createdAt", 0);
        t.updatedAt = Json.num(m, "updatedAt", 0);
        t.startedAt = Json.num(m, "startedAt", 0);
        t.doneAt = Json.num(m, "doneAt", 0);
        t.updatedBy = Json.str(m, "updatedBy");
        t.rank = Json.dbl(m, "rank", 0);
        for (Object o : Json.list(m, "comments")) if (o instanceof Map) t.comments.add((Map<String, Object>) o);
        for (Object o : Json.list(m, "history")) if (o instanceof Map) t.history.add((Map<String, Object>) o);
        return t;
    }
}
