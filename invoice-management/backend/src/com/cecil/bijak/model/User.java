package com.cecil.bijak.model;

import com.cecil.bijak.util.Json;

import java.util.Map;

/** A person who signs in to Bijak (a member of staff of the seller company). */
public class User implements Json.Writable {
    public String id, username, name, email, passwordHash;
    public Role role;
    public boolean active = true;
    public int failedLogins;
    public long lockedUntil, lastSeen, createdAt;

    public boolean owner() { return role == Role.OWNER; }

    public String initials() {
        String[] p = name == null ? new String[0] : name.trim().split("\\s+");
        if (p.length == 0 || p[0].isEmpty()) return "?";
        return (p[0].substring(0, 1) + (p.length > 1 ? p[p.length - 1].substring(0, 1) : "")).toUpperCase();
    }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "username", username, "name", name, "email", email, "role", role,
                "passwordHash", passwordHash, "active", active, "failedLogins", failedLogins,
                "lockedUntil", lockedUntil, "lastSeen", lastSeen, "createdAt", createdAt);
    }

    public static User fromJson(Map<String, Object> m) {
        User u = new User();
        u.id = Json.str(m, "id");
        u.username = Json.str(m, "username");
        u.name = Json.str(m, "name");
        u.email = Json.str(m, "email");
        u.role = Role.of(Json.str(m, "role"));
        u.passwordHash = Json.str(m, "passwordHash");
        u.active = Json.bool(m, "active", true);
        u.failedLogins = (int) Json.num(m, "failedLogins", 0);
        u.lockedUntil = Json.num(m, "lockedUntil", 0);
        u.lastSeen = Json.num(m, "lastSeen", 0);
        u.createdAt = Json.num(m, "createdAt", 0);
        return u;
    }
}
