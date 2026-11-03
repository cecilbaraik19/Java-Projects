package com.cecil.mail.model;

import com.cecil.mail.util.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/** A person who can sign in. "title" is printed under their name in reply signatures. */
public class User implements Json.JsonWritable {

    public String username;
    public String displayName;
    public String title;
    public Role role;
    public String passwordHash;
    public int failedLogins;
    public long lockedUntil;      // epoch millis; 0 = not locked

    public User() { }

    public User(String username, String displayName, String title, Role role, String passwordHash) {
        this.username = username;
        this.displayName = displayName;
        this.title = title;
        this.role = role;
        this.passwordHash = passwordHash;
    }

    /** Everything, including the password hash. Only used for saving to disk. */
    @Override
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", username);
        m.put("displayName", displayName);
        m.put("title", title);
        m.put("role", role);
        m.put("passwordHash", passwordHash);
        m.put("failedLogins", failedLogins);
        m.put("lockedUntil", lockedUntil);
        return m;
    }

    /** Safe version sent to the browser: no password hash. */
    public Map<String, Object> toPublicJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", username);
        m.put("displayName", displayName);
        m.put("title", title);
        m.put("role", role);
        return m;
    }

    public static User fromJson(Map<String, Object> m) {
        User u = new User();
        u.username = Json.str(m, "username");
        u.displayName = Json.str(m, "displayName");
        u.title = Json.str(m, "title");
        u.role = Role.valueOf(Json.str(m, "role"));
        u.passwordHash = Json.str(m, "passwordHash");
        u.failedLogins = (int) Json.num(m, "failedLogins", 0);
        u.lockedUntil = Json.num(m, "lockedUntil", 0);
        return u;
    }
}
