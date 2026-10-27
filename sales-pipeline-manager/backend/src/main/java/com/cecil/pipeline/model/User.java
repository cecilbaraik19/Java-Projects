package com.cecil.pipeline.model;

import com.cecil.pipeline.model.Enums.Role;
import com.cecil.pipeline.util.Json;

import java.util.Map;

/** A person who signs in to the app (admin, manager or sales rep). */
public class User {

    public String username;
    public String displayName;
    public String email;
    public Role role = Role.SALES_REP;
    public String passwordHash;
    public boolean active = true;
    /** Monthly sales target in rupees (used for the leaderboard). */
    public long monthlyTarget;
    public String createdAt;

    public boolean isAdmin() { return role == Role.ADMIN; }
    /** Managers and admins can edit any deal. */
    public boolean canEditAll() { return role == Role.ADMIN || role == Role.MANAGER; }

    /** What other people and the browser see (never includes the password hash). */
    public Map<String, Object> toPublic() {
        return Json.obj(
            "username", username,
            "displayName", displayName,
            "email", email,
            "role", role,
            "active", active,
            "monthlyTarget", monthlyTarget,
            "createdAt", createdAt);
    }

    /** Full record for the data file. */
    public Map<String, Object> toStore() {
        Map<String, Object> m = toPublic();
        m.put("passwordHash", passwordHash);
        return m;
    }

    public static User fromMap(Map<String, Object> m) {
        User u = new User();
        u.username = Json.str(m, "username");
        u.displayName = Json.str(m, "displayName", u.username);
        u.email = Json.str(m, "email", "");
        u.role = Enums.parse(Role.class, Json.str(m, "role"), Role.SALES_REP);
        u.passwordHash = Json.str(m, "passwordHash");
        u.active = Json.bool(m, "active", true);
        u.monthlyTarget = Json.lng(m, "monthlyTarget", 0);
        u.createdAt = Json.str(m, "createdAt");
        return u;
    }
}
