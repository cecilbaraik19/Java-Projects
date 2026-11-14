package com.cecil.kaksh.model;

import com.cecil.kaksh.util.Json;

import java.util.Map;

/** One line of the activity log: who did what, to what, when. */
public record AuditEntry(long at, String userId, String action, String target, String detail) implements Json.Writable {
    @Override
    public Map<String, Object> toJson() {
        return Json.obj("at", at, "userId", userId, "action", action, "target", target, "detail", detail);
    }

    public static AuditEntry fromJson(Map<String, Object> m) {
        return new AuditEntry(Json.num(m, "at", 0), Json.str(m, "userId"), Json.str(m, "action", ""),
                Json.str(m, "target"), Json.str(m, "detail", ""));
    }
}
