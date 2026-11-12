package com.cecil.sikho.model;

import com.cecil.sikho.util.Json;

import java.util.Map;

/** A member tells the moderators about a problem with another member. */
public class Report implements Json.Writable {
    public String id;
    public String reporterId;
    public String targetId;
    public String exchangeId;
    public String reason;
    public String text;
    public String status = "OPEN";
    public long createdAt;
    public String resolvedBy;
    public String action;
    public String note;
    public long resolvedAt;

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "reporterId", reporterId, "targetId", targetId, "exchangeId", exchangeId, "reason", reason,
                "text", text, "status", status, "createdAt", createdAt, "resolvedBy", resolvedBy, "action", action,
                "note", note, "resolvedAt", resolvedAt);
    }

    public static Report fromJson(Map<String, Object> m) {
        Report r = new Report();
        r.id = Json.str(m, "id");
        r.reporterId = Json.str(m, "reporterId");
        r.targetId = Json.str(m, "targetId");
        r.exchangeId = Json.str(m, "exchangeId");
        r.reason = Json.str(m, "reason");
        r.text = Json.str(m, "text");
        r.status = Json.str(m, "status", "OPEN");
        r.createdAt = Json.num(m, "createdAt", 0);
        r.resolvedBy = Json.str(m, "resolvedBy");
        r.action = Json.str(m, "action");
        r.note = Json.str(m, "note");
        r.resolvedAt = Json.num(m, "resolvedAt", 0);
        return r;
    }
}
