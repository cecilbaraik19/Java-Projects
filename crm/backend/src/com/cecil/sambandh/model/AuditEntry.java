package com.cecil.sambandh.model;

import com.cecil.sambandh.util.Json;

import java.util.Map;

/** One line in the activity log: who did what, to which record, and when. */
public class AuditEntry implements Json.Writable {
    public long at;
    public String userId;
    public String action;   // e.g. "customer.create", "customer.merge", "login.fail"
    public String target;   // e.g. "C-1004"
    public String detail;

    public AuditEntry() { }

    public AuditEntry(long at, String userId, String action, String target, String detail) {
        this.at = at; this.userId = userId; this.action = action; this.target = target; this.detail = detail;
    }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("at", at, "userId", userId, "action", action, "target", target, "detail", detail);
    }

    public static AuditEntry fromJson(Map<String, Object> m) {
        return new AuditEntry(Json.num(m, "at", 0), Json.str(m, "userId"), Json.str(m, "action"),
                Json.str(m, "target"), Json.str(m, "detail"));
    }
}
