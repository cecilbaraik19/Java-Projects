package com.cecil.sangam.model;

import com.cecil.sangam.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A person in the workspace. Every person lives in their own time zone and has their own working hours.
 * The password is kept only as a PBKDF2 hash.
 */
public class User implements Json.Writable {
    public String id;
    public String username;
    public String name;
    public String title;
    public String city;
    /** IANA time zone, for example "Asia/Kolkata" or "Europe/Berlin". */
    public String zone = "Asia/Kolkata";
    /** Working hours in the person's own local time, as minutes after midnight (540 = 09:00). */
    public int workStart = 9 * 60;
    public int workEnd = 18 * 60;
    /** Working days, ISO numbers: 1 = Monday ... 7 = Sunday. */
    public List<Integer> workDays = new ArrayList<>(List.of(1, 2, 3, 4, 5));
    public Role role = Role.MEMBER;
    public String passwordHash;
    public int failedLogins;
    public long lockedUntil;
    public boolean active = true;
    public long createdAt;
    /** Last time the browser talked to us (for "online / away / offline"). */
    public long lastSeen;
    /** "Focus time": notifications wait until this moment (0 = not focusing). */
    public long focusUntil;
    public String statusText = "";
    /** Meeting "inconvenience points" taken so far. Higher = this person was asked to meet at bad hours more often. */
    public double pain;
    /** For each channel: the highest message number this person has read. */
    public Map<String, Long> readUpTo = new LinkedHashMap<>();

    public boolean admin() { return role == Role.ADMIN; }
    public boolean guest() { return role == Role.GUEST; }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "username", username, "name", name, "title", title, "city", city, "zone", zone,
                "workStart", workStart, "workEnd", workEnd, "workDays", workDays, "role", role,
                "passwordHash", passwordHash, "failedLogins", failedLogins, "lockedUntil", lockedUntil,
                "active", active, "createdAt", createdAt, "lastSeen", lastSeen, "focusUntil", focusUntil,
                "statusText", statusText, "pain", pain, "readUpTo", readUpTo);
    }

    public static User fromJson(Map<String, Object> m) {
        User u = new User();
        u.id = Json.str(m, "id");
        u.username = Json.str(m, "username");
        u.name = Json.str(m, "name");
        u.title = Json.str(m, "title", "");
        u.city = Json.str(m, "city", "");
        u.zone = Json.str(m, "zone", "Asia/Kolkata");
        u.workStart = (int) Json.num(m, "workStart", 540);
        u.workEnd = (int) Json.num(m, "workEnd", 1080);
        u.workDays = new ArrayList<>();
        for (Object o : Json.list(m, "workDays")) if (o instanceof Number n) u.workDays.add(n.intValue());
        u.role = Role.valueOf(Json.str(m, "role", "MEMBER"));
        u.passwordHash = Json.str(m, "passwordHash");
        u.failedLogins = (int) Json.num(m, "failedLogins", 0);
        u.lockedUntil = Json.num(m, "lockedUntil", 0);
        u.active = Json.bool(m, "active", true);
        u.createdAt = Json.num(m, "createdAt", 0);
        u.lastSeen = Json.num(m, "lastSeen", 0);
        u.focusUntil = Json.num(m, "focusUntil", 0);
        u.statusText = Json.str(m, "statusText", "");
        u.pain = Json.dbl(m, "pain", 0);
        if (m.get("readUpTo") instanceof Map<?, ?> r)
            for (Map.Entry<?, ?> e : r.entrySet()) if (e.getValue() instanceof Number n) u.readUpTo.put(String.valueOf(e.getKey()), n.longValue());
        return u;
    }
}
