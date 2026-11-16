package com.cecil.dainandini.model;

import com.cecil.dainandini.util.Json;

import java.util.Map;

/** One focus session (like a Pomodoro): working on one task without switching. end = 0 while it is running. */
public class FocusSession implements Json.Writable {
    public String id;
    public String userId;
    public String taskId;
    public long start;
    public long end;
    public int plannedMin = 25;
    public int interruptions;
    /** true = the person stayed until the planned time was up. */
    public boolean finished;

    public boolean running() { return end == 0; }
    public int minutes() { return end == 0 ? 0 : (int) Math.round((end - start) / 60_000.0); }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "userId", userId, "taskId", taskId, "start", start, "end", end,
                "plannedMin", plannedMin, "interruptions", interruptions, "finished", finished);
    }

    public static FocusSession fromJson(Map<String, Object> m) {
        FocusSession f = new FocusSession();
        f.id = Json.str(m, "id");
        f.userId = Json.str(m, "userId");
        f.taskId = Json.str(m, "taskId");
        f.start = Json.num(m, "start", 0);
        f.end = Json.num(m, "end", 0);
        f.plannedMin = (int) Json.num(m, "plannedMin", 25);
        f.interruptions = (int) Json.num(m, "interruptions", 0);
        f.finished = Json.bool(m, "finished", false);
        return f;
    }
}
