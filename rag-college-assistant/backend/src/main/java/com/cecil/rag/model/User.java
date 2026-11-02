package com.cecil.rag.model;

import com.cecil.rag.util.Json;
import java.util.Map;

/** A person who can sign in. */
public class User implements Json.JsonAware {
    public String id;
    public String username;
    public String name;
    public Role role = Role.STUDENT;
    public String dept = "";
    public String programme = "";      // e.g. "B.Sc. IT - Semester 3" (students)
    public String passwordHash;
    public boolean active = true;
    public long createdAt;
    public long lastLogin;
    public int failed;                 // wrong passwords in a row
    public long lockedUntil;

    @Override
    public Map<String, Object> toJson() {
        return Json.map("id", id, "username", username, "name", name, "role", role, "dept", dept,
                "programme", programme, "passwordHash", passwordHash, "active", active, "createdAt", createdAt,
                "lastLogin", lastLogin, "failed", failed, "lockedUntil", lockedUntil);
    }

    /** What the browser is allowed to see (no password hash). */
    public Map<String, Object> publicJson() {
        return Json.map("id", id, "username", username, "name", name, "role", role, "roleLabel", role.label,
                "dept", dept, "programme", programme, "active", active, "lastLogin", lastLogin, "createdAt", createdAt);
    }

    public static User from(Map<String, Object> m) {
        User u = new User();
        u.id = Json.str(m, "id", null);
        u.username = Json.str(m, "username", "");
        u.name = Json.str(m, "name", "");
        u.role = Role.parse(Json.str(m, "role", "STUDENT"));
        u.dept = Json.str(m, "dept", "");
        u.programme = Json.str(m, "programme", "");
        u.passwordHash = Json.str(m, "passwordHash", null);
        u.active = Json.bool(m, "active", true);
        u.createdAt = Json.num(m, "createdAt", 0);
        u.lastLogin = Json.num(m, "lastLogin", 0);
        u.failed = (int) Json.num(m, "failed", 0);
        u.lockedUntil = Json.num(m, "lockedUntil", 0);
        return u;
    }
}
