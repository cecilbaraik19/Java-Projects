package com.cecil.kaushal.model;

import com.cecil.kaushal.util.Json;

import java.util.Map;

/** A person at the company. Everybody is made up. */
public class User implements Json.Writable {
    public String id;
    public String username;
    public String name;
    public String title;          // e.g. "Senior Backend Engineer"
    public String dept;           // e.g. "Engineering"
    public String location;       // e.g. "Ranchi"
    public Role role = Role.EMPLOYEE;
    public String managerId;      // who verifies their skills first; null for the top
    public String jobRoleId;      // the job role they hold now
    public String targetRoleId;   // the job role they want next (career goal)
    public String joined;         // yyyy-mm-dd
    public String passwordHash;
    public int failedLogins;
    public long lockedUntil;
    public boolean active = true;
    public long lastSeen;

    public boolean admin() { return role == Role.ADMIN; }
    public boolean manager() { return role == Role.MANAGER || role == Role.ADMIN; }

    public String initials() {
        String[] p = name.trim().split("\\s+");
        String a = p[0].substring(0, 1);
        String b = p.length > 1 ? p[p.length - 1].substring(0, 1) : (p[0].length() > 1 ? p[0].substring(1, 2) : "");
        return (a + b).toUpperCase();
    }

    @Override public Map<String, Object> toJson() {
        return Json.obj("id", id, "username", username, "name", name, "title", title, "dept", dept, "location", location,
                "role", role, "managerId", managerId, "jobRoleId", jobRoleId, "targetRoleId", targetRoleId, "joined", joined,
                "passwordHash", passwordHash, "failedLogins", failedLogins, "lockedUntil", lockedUntil, "active", active,
                "lastSeen", lastSeen);
    }

    public static User fromJson(Map<String, Object> m) {
        User u = new User();
        u.id = Json.str(m, "id");
        u.username = Json.str(m, "username");
        u.name = Json.str(m, "name");
        u.title = Json.str(m, "title");
        u.dept = Json.str(m, "dept");
        u.location = Json.str(m, "location");
        u.role = Role.of(Json.str(m, "role"));
        u.managerId = Json.str(m, "managerId");
        u.jobRoleId = Json.str(m, "jobRoleId");
        u.targetRoleId = Json.str(m, "targetRoleId");
        u.joined = Json.str(m, "joined");
        u.passwordHash = Json.str(m, "passwordHash");
        u.failedLogins = (int) Json.num(m, "failedLogins", 0);
        u.lockedUntil = Json.num(m, "lockedUntil", 0);
        u.active = Json.bool(m, "active", true);
        u.lastSeen = Json.num(m, "lastSeen", 0);
        return u;
    }
}
