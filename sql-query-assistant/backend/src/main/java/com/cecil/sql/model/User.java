package com.cecil.sql.model;

import com.cecil.sql.util.Json;

import java.util.Map;

/** A person who can sign in. The password is stored only as a PBKDF2 hash. */
public final class User implements Json.JsonWritable {
    public final String username;
    public String displayName;
    public Role role;
    public String passwordHash;
    public int failedLogins;
    public long lockedUntil;      // epoch millis, 0 = not locked
    public final String createdAt;

    public User(String username, String displayName, Role role, String passwordHash, String createdAt) {
        this.username = username;
        this.displayName = displayName;
        this.role = role;
        this.passwordHash = passwordHash;
        this.createdAt = createdAt;
    }

    public boolean can(Role needed) {
        if (needed == Role.VIEWER) return true;
        if (needed == Role.ANALYST) return role == Role.ANALYST || role == Role.ADMIN;
        return role == Role.ADMIN;
    }

    /** For the browser: never includes the hash. */
    public Map<String, Object> publicView() {
        return Json.map("username", username, "displayName", displayName, "role", role, "createdAt", createdAt,
                "locked", lockedUntil > System.currentTimeMillis());
    }

    @Override public Object toJson() {
        return Json.map("username", username, "displayName", displayName, "role", role, "passwordHash", passwordHash,
                "failedLogins", failedLogins, "lockedUntil", lockedUntil, "createdAt", createdAt);
    }

    public static User fromJson(Map<String, Object> m) {
        User u = new User(Json.str(m, "username"), Json.str(m, "displayName"), Role.valueOf(Json.str(m, "role", "VIEWER")),
                Json.str(m, "passwordHash"), Json.str(m, "createdAt"));
        u.failedLogins = (int) Json.num(m, "failedLogins", 0);
        u.lockedUntil = Json.num(m, "lockedUntil", 0);
        return u;
    }
}
