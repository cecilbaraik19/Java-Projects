package com.cecil.certify.model;

import com.cecil.certify.util.Json;

import java.util.Map;

public final class User implements Json.JsonWritable {
    public String username;
    public String displayName;
    public Role role;
    /** For ISSUER staff: which institution they work for. */
    public String issuerId = "";
    public String passwordHash;
    public int failedLogins;
    public long lockedUntil;
    public long createdAt;

    @Override
    public Object toJson() {
        return Json.map("username", username, "displayName", displayName, "role", role.name(), "issuerId", issuerId,
                "passwordHash", passwordHash, "failedLogins", failedLogins, "lockedUntil", lockedUntil,
                "createdAt", createdAt);
    }

    /** What the browser is allowed to see (never the password hash). */
    public Map<String, Object> publicView() {
        return Json.map("username", username, "displayName", displayName, "role", role.name(), "issuerId", issuerId);
    }

    public static User fromJson(Map<String, Object> m) {
        User u = new User();
        u.username = Json.str(m, "username");
        u.displayName = Json.str(m, "displayName", u.username);
        u.role = Role.valueOf(Json.str(m, "role", "STUDENT"));
        u.issuerId = Json.str(m, "issuerId", "");
        u.passwordHash = Json.str(m, "passwordHash");
        u.failedLogins = (int) Json.num(m, "failedLogins", 0);
        u.lockedUntil = Json.num(m, "lockedUntil", 0);
        u.createdAt = Json.num(m, "createdAt", 0);
        return u;
    }
}
