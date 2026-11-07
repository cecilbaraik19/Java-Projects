package com.cecil.sambandh.model;

import com.cecil.sambandh.util.Json;

import java.time.LocalDate;
import java.util.Map;

/** A reminder to do something for a customer by a certain day ("call back on Friday about the quote"). */
public class FollowUp implements Json.Writable {
    public String id;
    public String customerId;
    public String title;
    public LocalDate due;
    public String priority = "MEDIUM";   // HIGH, MEDIUM, LOW
    public String assigneeId;
    public boolean done;
    public long doneAt;
    public long createdAt;
    public String createdBy;

    public boolean overdue(LocalDate today) { return !done && due.isBefore(today); }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "customerId", customerId, "title", title, "due", due.toString(), "priority", priority,
                "assigneeId", assigneeId, "done", done, "doneAt", doneAt, "createdAt", createdAt, "createdBy", createdBy);
    }

    public static FollowUp fromJson(Map<String, Object> m) {
        FollowUp f = new FollowUp();
        f.id = Json.str(m, "id");
        f.customerId = Json.str(m, "customerId");
        f.title = Json.str(m, "title");
        f.due = LocalDate.parse(Json.str(m, "due"));
        f.priority = Json.str(m, "priority", "MEDIUM");
        f.assigneeId = Json.str(m, "assigneeId");
        f.done = Json.bool(m, "done", false);
        f.doneAt = Json.num(m, "doneAt", 0);
        f.createdAt = Json.num(m, "createdAt", 0);
        f.createdBy = Json.str(m, "createdBy");
        return f;
    }
}
