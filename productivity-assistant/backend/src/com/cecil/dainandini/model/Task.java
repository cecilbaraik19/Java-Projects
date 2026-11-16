package com.cecil.dainandini.model;

import com.cecil.dainandini.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One piece of work. Times are epoch milliseconds (0 = not set). */
public class Task implements Json.Writable {
    public String id;
    public String ownerId;
    /** Who made the task (a lead who handed it over, or the owner). */
    public String createdBy;
    public String title;
    public String notes = "";
    public TaskStatus status = TaskStatus.TODO;
    public Importance importance = Importance.MEDIUM;
    public Energy energy = Energy.SHALLOW;
    public long due;
    /** false = only a date was given ("by Friday"): due is the end of that working day. */
    public boolean dueHasTime;
    /** The day the person plans to do it (yyyy-mm-dd) or null. The planner does not plan it before this day. */
    public String doDate;
    public int estimateMin;
    /** true when the estimate is the app's guess (from similar finished tasks), not typed by the person. */
    public boolean estimateGuessed;
    /** Minutes really spent (focus sessions + time typed in by hand). */
    public int actualMin;
    public List<String> tags = new ArrayList<>();
    /** "" (none), DAILY, WEEKDAYS, WEEKLY:1..7, MONTHLY */
    public String recurrence = "";
    /** Where it came from: "" or a meeting note id. */
    public String sourceNoteId = "";
    public long createdAt;
    public long startedAt;
    public long doneAt;
    public long updatedAt;

    public boolean open() { return status.open(); }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "ownerId", ownerId, "createdBy", createdBy, "title", title, "notes", notes,
                "status", status, "importance", importance, "energy", energy, "due", due, "dueHasTime", dueHasTime,
                "doDate", doDate, "estimateMin", estimateMin, "estimateGuessed", estimateGuessed, "actualMin", actualMin,
                "tags", tags, "recurrence", recurrence, "sourceNoteId", sourceNoteId, "createdAt", createdAt,
                "startedAt", startedAt, "doneAt", doneAt, "updatedAt", updatedAt);
    }

    public static Task fromJson(Map<String, Object> m) {
        Task t = new Task();
        t.id = Json.str(m, "id");
        t.ownerId = Json.str(m, "ownerId");
        t.createdBy = Json.str(m, "createdBy");
        t.title = Json.str(m, "title", "");
        t.notes = Json.str(m, "notes", "");
        t.status = TaskStatus.valueOf(Json.str(m, "status", "TODO"));
        t.importance = Importance.valueOf(Json.str(m, "importance", "MEDIUM"));
        t.energy = Energy.valueOf(Json.str(m, "energy", "SHALLOW"));
        t.due = Json.num(m, "due", 0);
        t.dueHasTime = Json.bool(m, "dueHasTime", false);
        t.doDate = Json.str(m, "doDate");
        t.estimateMin = (int) Json.num(m, "estimateMin", 0);
        t.estimateGuessed = Json.bool(m, "estimateGuessed", false);
        t.actualMin = (int) Json.num(m, "actualMin", 0);
        t.tags = new ArrayList<>(Json.strings(m, "tags"));
        t.recurrence = Json.str(m, "recurrence", "");
        t.sourceNoteId = Json.str(m, "sourceNoteId", "");
        t.createdAt = Json.num(m, "createdAt", 0);
        t.startedAt = Json.num(m, "startedAt", 0);
        t.doneAt = Json.num(m, "doneAt", 0);
        t.updatedAt = Json.num(m, "updatedAt", 0);
        return t;
    }
}
