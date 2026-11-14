package com.cecil.kaksh.model;

import com.cecil.kaksh.util.Json;

import java.util.Map;

/** A person in the office. The password is kept only as a PBKDF2 hash. */
public class User implements Json.Writable {
    public String id;
    public String username;
    public String name;
    public String email;
    public String team;
    /** The floor where this person sits (rooms on the same floor score a little higher). */
    public int floor;
    public Role role = Role.EMPLOYEE;
    public String passwordHash;
    public int failedLogins;
    public long lockedUntil;
    public boolean active = true;
    public long createdAt;

    public boolean admin() { return role == Role.ADMIN; }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "username", username, "name", name, "email", email, "team", team, "floor", floor,
                "role", role, "passwordHash", passwordHash, "failedLogins", failedLogins, "lockedUntil", lockedUntil,
                "active", active, "createdAt", createdAt);
    }

    public static User fromJson(Map<String, Object> m) {
        User u = new User();
        u.id = Json.str(m, "id");
        u.username = Json.str(m, "username");
        u.name = Json.str(m, "name");
        u.email = Json.str(m, "email", "");
        u.team = Json.str(m, "team", "");
        u.floor = (int) Json.num(m, "floor", 1);
        u.role = Role.valueOf(Json.str(m, "role", "EMPLOYEE"));
        u.passwordHash = Json.str(m, "passwordHash");
        u.failedLogins = (int) Json.num(m, "failedLogins", 0);
        u.lockedUntil = Json.num(m, "lockedUntil", 0);
        u.active = Json.bool(m, "active", true);
        u.createdAt = Json.num(m, "createdAt", 0);
        return u;
    }

    /** What other people may see about this person. */
    public Map<String, Object> pub() {
        return Json.obj("id", id, "name", name, "team", team, "floor", floor);
    }
}
