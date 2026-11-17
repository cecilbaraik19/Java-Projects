package com.cecil.kaushal.model;

import com.cecil.kaushal.util.Json;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A certificate (AWS, CKA, Scrum ...) with an optional expiry date. Linked to one skill. */
public class Cert implements Json.Writable {
    public static final int EXPIRING_DAYS = 60;

    public String id;
    public String userId;
    public String name;
    public String issuer;
    public String skillId;          // may be null
    public String issued;           // yyyy-mm-dd
    public String expires;          // yyyy-mm-dd or null = never
    public long addedAt;
    /** Reminders already sent: "60", "30", "0" (so each one is sent only once). */
    public List<String> reminded = new ArrayList<>();

    /** VALID, EXPIRING (60 days or less left) or EXPIRED on the given day. */
    public String status(LocalDate today) {
        if (expires == null) return "VALID";
        LocalDate e = LocalDate.parse(expires);
        if (e.isBefore(today)) return "EXPIRED";
        if (!e.isAfter(today.plusDays(EXPIRING_DAYS))) return "EXPIRING";
        return "VALID";
    }

    public long daysLeft(LocalDate today) {
        if (expires == null) return Long.MAX_VALUE;
        return java.time.temporal.ChronoUnit.DAYS.between(today, LocalDate.parse(expires));
    }

    @Override public Map<String, Object> toJson() {
        return Json.obj("id", id, "userId", userId, "name", name, "issuer", issuer, "skillId", skillId, "issued", issued,
                "expires", expires, "addedAt", addedAt, "reminded", reminded);
    }

    public static Cert fromJson(Map<String, Object> m) {
        Cert c = new Cert();
        c.id = Json.str(m, "id");
        c.userId = Json.str(m, "userId");
        c.name = Json.str(m, "name");
        c.issuer = Json.str(m, "issuer");
        c.skillId = Json.str(m, "skillId");
        c.issued = Json.str(m, "issued");
        c.expires = Json.str(m, "expires");
        c.addedAt = Json.num(m, "addedAt", 0);
        c.reminded = Json.strings(m, "reminded");
        return c;
    }
}
