package com.cecil.swikriti.model;

import com.cecil.swikriti.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One seat of one stage for one revision: "somebody from Finance must sign PR/26-27/0042 rev B".
 * candidates = the people the seat rule gave; delegates and escalation managers are added to `assignees`.
 * Anybody in assignees may decide; the first decision closes the task.
 */
public class Task implements Json.Writable {
    public enum State { PENDING, APPROVED, REJECTED, RETURNED, CANCELLED, NOT_NEEDED;
        static State of(String s) { try { return valueOf(s); } catch (Exception e) { return PENDING; } }
        public boolean open() { return this == PENDING; }
    }

    public String id;
    public String docId;
    public int rev;
    public int stage;               // index in the document's route
    public int seat;                // index in the stage's seats
    public String rule;             // e.g. GROUP:Finance
    public List<String> candidates = new ArrayList<>();
    public List<String> assignees = new ArrayList<>();
    public Map<String, Object> via = new java.util.LinkedHashMap<>();   // assigneeId -> "delegate of U-1" / "escalated"
    public State state = State.PENDING;
    public long createdAt;
    public long dueAt;
    public boolean reminded;
    public boolean escalated;
    public String decidedBy;
    public String onBehalfOf;       // when a delegate signs for a candidate
    public long decidedAt;
    public String comment;

    @Override public Map<String, Object> toJson() {
        return Json.obj("id", id, "docId", docId, "rev", rev, "stage", stage, "seat", seat, "rule", rule,
                "candidates", candidates, "assignees", assignees, "via", via, "state", state, "createdAt", createdAt,
                "dueAt", dueAt, "reminded", reminded, "escalated", escalated, "decidedBy", decidedBy,
                "onBehalfOf", onBehalfOf, "decidedAt", decidedAt, "comment", comment);
    }

    public static Task fromJson(Map<String, Object> m) {
        Task t = new Task();
        t.id = Json.str(m, "id");
        t.docId = Json.str(m, "docId");
        t.rev = (int) Json.num(m, "rev", 1);
        t.stage = (int) Json.num(m, "stage", 0);
        t.seat = (int) Json.num(m, "seat", 0);
        t.rule = Json.str(m, "rule");
        t.candidates = Json.strings(m, "candidates");
        t.assignees = Json.strings(m, "assignees");
        if (m.get("via") instanceof Map<?, ?> v) t.via.putAll(Template.cast(v));
        t.state = State.of(Json.str(m, "state"));
        t.createdAt = Json.num(m, "createdAt", 0);
        t.dueAt = Json.num(m, "dueAt", 0);
        t.reminded = Json.bool(m, "reminded", false);
        t.escalated = Json.bool(m, "escalated", false);
        t.decidedBy = Json.str(m, "decidedBy");
        t.onBehalfOf = Json.str(m, "onBehalfOf");
        t.decidedAt = Json.num(m, "decidedAt", 0);
        t.comment = Json.str(m, "comment");
        return t;
    }
}
