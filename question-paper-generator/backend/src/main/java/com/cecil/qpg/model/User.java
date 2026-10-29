package com.cecil.qpg.model;

import com.cecil.qpg.model.Enums.Role;
import com.cecil.qpg.util.Json;

import java.util.Map;

/** A person who can sign in. */
public class User implements Json.JsonAble {
    public String username;
    public String displayName;
    public String department;
    public Role role;
    public String passwordHash;
    public boolean active = true;
    public String createdAt;

    /** What the browser is allowed to see (never the password hash). */
    public Map<String, Object> publicView() {
        return Json.map("username", username, "displayName", displayName, "department", department,
                "role", role, "roleLabel", role.label, "active", active);
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
        u.department = Json.str(m, "department", "");
        u.role = Role.valueOf(Json.str(m, "role", "FACULTY"));
        u.passwordHash = Json.str(m, "passwordHash");
        u.active = Json.bool(m, "active", true);
        u.createdAt = Json.str(m, "createdAt");
        return u;
    }
}
