package com.cecil.sanket.model;

import com.cecil.sanket.util.Json;

import java.util.Map;

/** A person who can sign in (admin, operations staff or customer). */
public class User implements Json.Writable {
    public String id;
    public String username;
    public String name;
    public Role role;
    public String passwordHash;
    public String customerId;    // only for customers: which Customer record is theirs
    public boolean active = true;
    public int failedLogins;
    public long lockedUntil;     // epoch millis; 0 = not locked
    public long createdAt;

    /** The safe view sent to the browser - never includes the password hash. */
    public Map<String, Object> publicView() {
        return Json.obj("id", id, "username", username, "name", name, "role", role, "roleLabel", role.label,
                "customerId", customerId, "active", active, "initials", initials());
    }

    public String initials() {
        if (name == null || name.isBlank()) return "?";
        String[] w = name.trim().split("\\s+");
        String s = w[0].substring(0, 1);
        if (w.length > 1) s += w[w.length - 1].substring(0, 1);
        return s.toUpperCase();
    }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "username", username, "name", name, "role", role, "passwordHash", passwordHash,
                "customerId", customerId, "active", active, "failedLogins", failedLogins,
                "lockedUntil", lockedUntil, "createdAt", createdAt);
    }

    public static User fromJson(Map<String, Object> m) {
        User u = new User();
        u.id = Json.str(m, "id");
        u.username = Json.str(m, "username");
        u.name = Json.str(m, "name");
        u.role = Role.parse(Json.str(m, "role"));
        if (u.role == null) u.role = Role.CUSTOMER;
        u.passwordHash = Json.str(m, "passwordHash");
        u.customerId = Json.str(m, "customerId");
        u.active = Json.bool(m, "active", true);
        u.failedLogins = (int) Json.num(m, "failedLogins", 0);
        u.lockedUntil = Json.num(m, "lockedUntil", 0);
        u.createdAt = Json.num(m, "createdAt", 0);
        return u;
    }
}
