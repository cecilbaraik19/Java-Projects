package com.cecil.complaints.model;

import com.cecil.complaints.model.Enums.Role;
import com.cecil.complaints.util.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A support agent or admin who can log in to the console.
 * Passwords are stored as salted PBKDF2 hashes, never as plain text.
 */
public class User {

    public String username;
    public String displayName;
    public Role role = Role.AGENT;
    public String salt;
    public String passwordHash;
    public boolean active = true;

    /** Safe view for the API: no salt or hash. */
    public Map<String, Object> toPublicMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", username);
        m.put("displayName", displayName);
        m.put("role", role);
        m.put("active", active);
        return m;
    }

    /** Full view for saving to disk. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = toPublicMap();
        m.put("salt", salt);
        m.put("passwordHash", passwordHash);
        return m;
    }

    public static User fromMap(Map<String, Object> m) {
        User u = new User();
        u.username = Json.str(m, "username");
        u.displayName = Json.str(m, "displayName");
        u.role = Enums.parse(Role.class, Json.str(m, "role"), Role.AGENT);
        u.salt = Json.str(m, "salt");
        u.passwordHash = Json.str(m, "passwordHash");
        u.active = Json.bool(m, "active", true);
        return u;
    }
}
