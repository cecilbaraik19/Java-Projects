package com.cecil.parisar.model;

import com.cecil.parisar.util.Json;

import java.util.Map;

/** A person who can sign in (admin, faculty member or student). */
public class User implements Json.Writable {
    public String id;
    public String username;
    public String name;
    public Role role;
    public String passwordHash;
    public String studentId;     // only for students: which Student record is theirs
    public String department;    // faculty: e.g. "Information Technology"
    public boolean active = true;
    public int failedLogins;
    public long lockedUntil;     // epoch millis; 0 = not locked
    public long createdAt;

    /** The safe view sent to the browser - never includes the password hash. */
    public Map<String, Object> publicView() {
        return Json.obj("id", id, "username", username, "name", name, "role", role, "roleLabel", role.label,
                "studentId", studentId, "department", department, "active", active, "initials", initials());
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
                "studentId", studentId, "department", department, "active", active, "failedLogins", failedLogins,
                "lockedUntil", lockedUntil, "createdAt", createdAt);
    }

    public static User fromJson(Map<String, Object> m) {
        User u = new User();
        u.id = Json.str(m, "id");
        u.username = Json.str(m, "username");
        u.name = Json.str(m, "name");
        u.role = Role.parse(Json.str(m, "role"));
        if (u.role == null) u.role = Role.STUDENT;
        u.passwordHash = Json.str(m, "passwordHash");
        u.studentId = Json.str(m, "studentId");
        u.department = Json.str(m, "department");
        u.active = Json.bool(m, "active", true);
        u.failedLogins = (int) Json.num(m, "failedLogins", 0);
        u.lockedUntil = Json.num(m, "lockedUntil", 0);
        u.createdAt = Json.num(m, "createdAt", 0);
        return u;
    }
}
