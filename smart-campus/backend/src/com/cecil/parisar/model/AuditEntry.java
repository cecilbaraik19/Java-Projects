package com.cecil.parisar.model;

import com.cecil.parisar.util.Json;

import java.util.Map;

/** One line of the activity log: who did what, to what, and when. */
public class AuditEntry implements Json.Writable {
    public final long at;
    public final String userId;
    public final String action;
    public final String target;
    public final String detail;

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
