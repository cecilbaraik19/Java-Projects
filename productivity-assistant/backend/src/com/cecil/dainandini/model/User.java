package com.cecil.dainandini.model;

import com.cecil.dainandini.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A person who uses the app. Times of day are minutes after midnight in India time (540 = 09:00). */
public class User implements Json.Writable {
    public String id;
    public String username;
    public String name;
    public String title = "";
    public String team = "";
    public Role role = Role.MEMBER;
    public String passwordHash;
    public int failedLogins;
    public long lockedUntil;
    public boolean active = true;
    public long createdAt;
    public int workStart = 9 * 60 + 30;
    public int workEnd = 18 * 60;
    /** ISO day numbers: 1 = Monday ... 7 = Sunday. */
    public List<Integer> workDays = new ArrayList<>(List.of(1, 2, 3, 4, 5));
    public int lunchStart = 13 * 60;
    public int lunchMin = 45;
    /** How many minutes of focused work the person wants each day. */
    public int focusGoalMin = 240;

    public boolean admin() { return role == Role.ADMIN; }
    public boolean lead() { return role == Role.LEAD || role == Role.ADMIN; }
    public String firstName() { return name == null ? "" : name.split(" ")[0]; }
    public String initials() {
        StringBuilder sb = new StringBuilder();
        for (String p : name.split("\\s+")) if (!p.isEmpty() && sb.length() < 2) sb.append(Character.toUpperCase(p.charAt(0)));
        return sb.toString();
    }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "username", username, "name", name, "title", title, "team", team, "role", role,
                "passwordHash", passwordHash, "failedLogins", failedLogins, "lockedUntil", lockedUntil, "active", active,
                "createdAt", createdAt, "workStart", workStart, "workEnd", workEnd, "workDays", workDays,
                "lunchStart", lunchStart, "lunchMin", lunchMin, "focusGoalMin", focusGoalMin);
    }

    /** What the browser may see (no password hash). */
    public Map<String, Object> card() {
        return Json.obj("id", id, "username", username, "name", name, "initials", initials(), "title", title, "team", team,
                "role", role, "workStart", workStart, "workEnd", workEnd, "workDays", workDays, "lunchStart", lunchStart,
                "lunchMin", lunchMin, "focusGoalMin", focusGoalMin, "active", active);
    }

    public static User fromJson(Map<String, Object> m) {
        User u = new User();
        u.id = Json.str(m, "id");
        u.username = Json.str(m, "username");
        u.name = Json.str(m, "name");
        u.title = Json.str(m, "title", "");
        u.team = Json.str(m, "team", "");
        u.role = Role.valueOf(Json.str(m, "role", "MEMBER"));
        u.passwordHash = Json.str(m, "passwordHash");
        u.failedLogins = (int) Json.num(m, "failedLogins", 0);
        u.lockedUntil = Json.num(m, "lockedUntil", 0);
        u.active = Json.bool(m, "active", true);
        u.createdAt = Json.num(m, "createdAt", 0);
        u.workStart = (int) Json.num(m, "workStart", 570);
        u.workEnd = (int) Json.num(m, "workEnd", 1080);
        u.workDays = new ArrayList<>();
        for (Object o : Json.list(m, "workDays")) if (o instanceof Number n) u.workDays.add(n.intValue());
        u.lunchStart = (int) Json.num(m, "lunchStart", 780);
        u.lunchMin = (int) Json.num(m, "lunchMin", 45);
        u.focusGoalMin = (int) Json.num(m, "focusGoalMin", 240);
        return u;
    }
}
