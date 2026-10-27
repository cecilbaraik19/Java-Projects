package com.cecil.pipeline.model;

import com.cecil.pipeline.model.Enums.ActivityType;
import com.cecil.pipeline.util.Json;

import java.time.LocalDate;
import java.util.Map;

/** A call, email, meeting or task linked to a deal. Can be planned (due date) and then marked done. */
public class Activity {

    public String id;
    public String dealId;
    public ActivityType type = ActivityType.TASK;
    public String subject;
    public String notes;
    public LocalDate dueDate;
    public boolean done;
    public String doneAt;
    public String outcome;    // what happened, filled in when completed
    public String owner;      // who has to do it
    public String createdBy;
    public String createdAt;

    public boolean isOverdue(LocalDate today) {
        return !done && dueDate != null && dueDate.isBefore(today);
    }

    public Map<String, Object> toMap() {
        return Json.obj(
            "id", id,
            "dealId", dealId,
            "type", type,
            "subject", subject,
            "notes", notes,
            "dueDate", dueDate == null ? null : dueDate.toString(),
            "done", done,
            "doneAt", doneAt,
            "outcome", outcome,
            "owner", owner,
            "createdBy", createdBy,
            "createdAt", createdAt);
    }

    public static Activity fromMap(Map<String, Object> m) {
        Activity a = new Activity();
        a.id = Json.str(m, "id");
        a.dealId = Json.str(m, "dealId");
        a.type = Enums.parse(ActivityType.class, Json.str(m, "type"), ActivityType.TASK);
        a.subject = Json.str(m, "subject", "");
        a.notes = Json.str(m, "notes", "");
        String due = Json.str(m, "dueDate");
        a.dueDate = due == null || due.isBlank() ? null : LocalDate.parse(due);
        a.done = Json.bool(m, "done", false);
        a.doneAt = Json.str(m, "doneAt");
        a.outcome = Json.str(m, "outcome", "");
        a.owner = Json.str(m, "owner");
        a.createdBy = Json.str(m, "createdBy");
        a.createdAt = Json.str(m, "createdAt");
        return a;
    }
}
