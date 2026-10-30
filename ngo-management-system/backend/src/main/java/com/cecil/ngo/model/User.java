package com.cecil.ngo.model;

import com.cecil.ngo.model.Enums.Role;
import com.cecil.ngo.util.Json;

import java.util.Map;

/** A person who can sign in. A volunteer account is linked to one Volunteer record. */
public class User implements Json.JsonAble {
    public String username;
    public String displayName;
    public Role role;
    public String passwordHash;
    public boolean active = true;
    /** Only for the VOLUNTEER role: which volunteer record this login belongs to. */
    public String volunteerId;
    public String createdAt;

    /** What the browser is allowed to see (never the password hash). */
    public Map<String, Object> publicView() {
        return Json.map("username", username, "displayName", displayName, "role", role, "roleLabel", role.label,
                "active", active, "volunteerId", volunteerId);
    }

    @Override
    public Object toJson() {
        Map<String, Object> m = publicView();
        m.put("passwordHash", passwordHash);
        m.put("createdAt", createdAt);
        return m;
    }

    public static User fromJson(Map<String, Object> m) {
        User u = new User();
        u.username = Json.str(m, "username");
        u.displayName = Json.str(m, "displayName", u.username);
        u.role = Role.valueOf(Json.str(m, "role", "VOLUNTEER"));
        u.passwordHash = Json.str(m, "passwordHash");
        u.active = Json.bool(m, "active", true);
        u.volunteerId = Json.str(m, "volunteerId");
        u.createdAt = Json.str(m, "createdAt");
        return u;
    }
}
