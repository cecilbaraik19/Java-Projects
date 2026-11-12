package com.cecil.sikho.model;

import com.cecil.sikho.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A member of the community.
 * The exact home position (lat, lng) is private: other people only ever see the locality name and a rounded distance.
 * The phone number is shown only to a partner after an exchange has been accepted.
 */
public class User implements Json.Writable {
    public String id;
    public String username;
    public String name;
    public Role role = Role.MEMBER;
    public String passwordHash;
    public String localityId;
    public double lat;
    public double lng;
    public String bio;
    public String phone;
    public String languages;
    /** Free times, e.g. "MON-EVE", "SAT-MORN". Days MON..SUN, parts MORN, AFT, EVE. */
    public List<String> availability = new ArrayList<>();
    /** How far the person is happy to travel, in km. */
    public int maxKm = 5;
    /** People this member never wants to hear from. */
    public List<String> blocked = new ArrayList<>();
    public boolean active = true;
    public boolean suspended;
    public String suspendReason;
    public int warnings;
    public int failedLogins;
    public long lockedUntil;
    public long createdAt;

    public boolean isModerator() { return role == Role.MODERATOR; }

    public String initials() {
        if (name == null || name.isBlank()) return "?";
        String[] w = name.trim().split("\\s+");
        String s = w[0].substring(0, 1);
        if (w.length > 1) s += w[w.length - 1].substring(0, 1);
        return s.toUpperCase();
    }

    public String firstName() {
        return name == null ? "" : name.trim().split("\\s+")[0];
    }

    /** What the person sees about themselves (never the password hash). */
    public Map<String, Object> selfView() {
        return Json.obj("id", id, "username", username, "name", name, "role", role, "roleLabel", role.label,
                "localityId", localityId, "bio", bio, "phone", phone, "languages", languages,
                "availability", availability, "maxKm", maxKm, "blocked", blocked, "initials", initials(),
                "createdAt", createdAt, "warnings", warnings);
    }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "username", username, "name", name, "role", role, "passwordHash", passwordHash,
                "localityId", localityId, "lat", lat, "lng", lng, "bio", bio, "phone", phone, "languages", languages,
                "availability", availability, "maxKm", maxKm, "blocked", blocked, "active", active,
                "suspended", suspended, "suspendReason", suspendReason, "warnings", warnings,
                "failedLogins", failedLogins, "lockedUntil", lockedUntil, "createdAt", createdAt);
    }

    public static User fromJson(Map<String, Object> m) {
        User u = new User();
        u.id = Json.str(m, "id");
        u.username = Json.str(m, "username");
        u.name = Json.str(m, "name");
        Role r = Role.parse(Json.str(m, "role"));
        u.role = r == null ? Role.MEMBER : r;
        u.passwordHash = Json.str(m, "passwordHash");
        u.localityId = Json.str(m, "localityId");
        u.lat = Json.dbl(m, "lat", 0);
        u.lng = Json.dbl(m, "lng", 0);
        u.bio = Json.str(m, "bio");
        u.phone = Json.str(m, "phone");
        u.languages = Json.str(m, "languages");
        u.availability = Json.strings(m, "availability");
        u.maxKm = (int) Json.num(m, "maxKm", 5);
        u.blocked = Json.strings(m, "blocked");
        u.active = Json.bool(m, "active", true);
        u.suspended = Json.bool(m, "suspended", false);
        u.suspendReason = Json.str(m, "suspendReason");
        u.warnings = (int) Json.num(m, "warnings", 0);
        u.failedLogins = (int) Json.num(m, "failedLogins", 0);
        u.lockedUntil = Json.num(m, "lockedUntil", 0);
        u.createdAt = Json.num(m, "createdAt", 0);
        return u;
    }
}
