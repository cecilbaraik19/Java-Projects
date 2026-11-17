package com.cecil.kaushal.model;

import com.cecil.kaushal.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A learning goal: "Kubernetes from level 1 to 3 by 15 Feb", with small steps to tick off. */
public class Goal implements Json.Writable {
    public String id;
    public String userId;
    public String skillId;
    public int fromLevel;
    public int target;
    public String due;              // yyyy-mm-dd
    public String status = "OPEN";  // OPEN, DONE, DROPPED
    public String reason;           // e.g. "Gap for Senior Backend Engineer"
    public String createdBy;
    public long createdAt;
    public long doneAt;
    /** Each step: {text, done}. */
    public List<Map<String, Object>> steps = new ArrayList<>();

    public int stepsDone() {
        int n = 0;
        for (Map<String, Object> s : steps) if (Json.bool(s, "done", false)) n++;
        return n;
    }

    @Override public Map<String, Object> toJson() {
        return Json.obj("id", id, "userId", userId, "skillId", skillId, "fromLevel", fromLevel, "target", target, "due", due,
                "status", status, "reason", reason, "createdBy", createdBy, "createdAt", createdAt, "doneAt", doneAt, "steps", steps);
    }

    @SuppressWarnings("unchecked")
    public static Goal fromJson(Map<String, Object> m) {
        Goal g = new Goal();
        g.id = Json.str(m, "id");
        g.userId = Json.str(m, "userId");
        g.skillId = Json.str(m, "skillId");
        g.fromLevel = (int) Json.num(m, "fromLevel", 0);
        g.target = (int) Json.num(m, "target", 1);
        g.due = Json.str(m, "due");
        g.status = Json.str(m, "status", "OPEN");
        g.reason = Json.str(m, "reason");
        g.createdBy = Json.str(m, "createdBy");
        g.createdAt = Json.num(m, "createdAt", 0);
        g.doneAt = Json.num(m, "doneAt", 0);
        for (Object o : Json.list(m, "steps")) if (o instanceof Map) g.steps.add((Map<String, Object>) o);
        return g;
    }
}
