package com.cecil.energy.model;

import com.cecil.energy.model.Enums.Role;
import com.cecil.energy.util.Json;
import java.util.LinkedHashMap;
import java.util.Map;

/** A person who can sign in. */
public class User {
    public String username;
    public String displayName;
    public Role role = Role.MEMBER;
    public String passwordHash;
    public boolean active = true;
    public String createdAt;

    /** Safe version for the browser (no password hash). */
    public Map<String, Object> toPublic() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", username);
        m.put("displayName", displayName);
        m.put("role", role);
        m.put("roleLabel", role.label);
        m.put("active", active);
        m.put("createdAt", createdAt);
        return m;
    }

    /** Full version for the data file. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = toPublic();
        m.remove("roleLabel");
        m.put("passwordHash", passwordHash);
        return m;
    }

    public static User fromMap(Map<String, Object> m) {
        User u = new User();
        u.username = Json.str(m, "username");
        u.displayName = Json.str(m, "displayName", u.username);
        u.role = Enums.parse(Role.class, Json.str(m, "role"), Role.MEMBER);
        u.passwordHash = Json.str(m, "passwordHash");
        u.active = Json.bool(m, "active", true);
        u.createdAt = Json.str(m, "createdAt");
        return u;
    }
}
