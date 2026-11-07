package com.cecil.sambandh.model;

import com.cecil.sambandh.util.Json;

import java.util.Map;

/** A support request / complaint from a customer. Each priority has a deadline (SLA = service level agreement). */
public class Ticket implements Json.Writable {
    public String id;
    public String customerId;
    public String subject;
    public String priority = "MEDIUM";   // HIGH (24 h), MEDIUM (72 h), LOW (120 h)
    public String status = "OPEN";       // OPEN, IN_PROGRESS, RESOLVED
    public long createdAt;
    public long resolvedAt;
    public String byUserId;

    public static long slaHours(String priority) {
        return switch (priority == null ? "MEDIUM" : priority) {
            case "HIGH" -> 24;
            case "LOW" -> 120;
            default -> 72;
        };
    }

    public long deadline() { return createdAt + slaHours(priority) * 3_600_000L; }

    public boolean open() { return !"RESOLVED".equals(status); }

    /** Still open after its deadline. */
    public boolean breached(long now) { return open() && now > deadline(); }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "customerId", customerId, "subject", subject, "priority", priority, "status", status,
                "createdAt", createdAt, "resolvedAt", resolvedAt, "byUserId", byUserId);
    }

    public static Ticket fromJson(Map<String, Object> m) {
        Ticket t = new Ticket();
        t.id = Json.str(m, "id");
        t.customerId = Json.str(m, "customerId");
        t.subject = Json.str(m, "subject");
        t.priority = Json.str(m, "priority", "MEDIUM");
        t.status = Json.str(m, "status", "OPEN");
        t.createdAt = Json.num(m, "createdAt", 0);
        t.resolvedAt = Json.num(m, "resolvedAt", 0);
        t.byUserId = Json.str(m, "byUserId");
        return t;
    }
}
