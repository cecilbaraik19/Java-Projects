package com.cecil.edge.service;

import com.cecil.edge.model.Enums.Role;
import com.cecil.edge.model.User;
import com.cecil.edge.util.Passwords;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sign in, sessions and the team list.
 * - 5 wrong passwords lock the username for 5 minutes.
 * - A session is a random 256-bit token; it ends after 12 hours without use.
 */
public class AuthService {
    public static final int MAX_FAILS = 5;
    public static final long LOCK_MS = 5 * 60_000L;
    public static final long SESSION_IDLE_MS = 12 * 3_600_000L;

    private final Hub hub;
    private final Map<String, Session> sessions = new HashMap<>();

    private static final class Session {
        String username;
        long lastUsed;
    }

    AuthService(Hub hub) {
        this.hub = hub;
    }

    public static Map<String, Object> view(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", u.username);
        m.put("displayName", u.displayName);
        m.put("role", u.role);
        m.put("roleLabel", u.role.label);
        m.put("active", u.active);
        m.put("createdAt", u.createdAt);
        m.put("lastLogin", u.lastLogin);
        return m;
    }

    /** Returns {token, user} or throws. The same message is used for every failure so names cannot be guessed. */
    public Map<String, Object> login(String username, String password) {
        long now = hub.now();
        if (username == null || password == null) throw new ServiceException(401, "Wrong username or password");
        User u = hub.store.users.get(username.trim().toLowerCase());
        if (u != null && u.lockedUntil > now) {
            long min = Math.max(1, (u.lockedUntil - now + 59_999) / 60_000);
            throw new ServiceException(429, "Too many wrong tries. Try again in " + min + " minute" + (min == 1 ? "" : "s") + ".");
        }
        if (u == null || !Passwords.matches(password, u.salt, u.hash)) {
            if (u != null) {
                u.failedLogins++;
                if (u.failedLogins >= MAX_FAILS) {
                    u.lockedUntil = now + LOCK_MS;
                    u.failedLogins = 0;
                    hub.store.addEvent(now, u.username, "security", null, "Account locked for 5 minutes after 5 wrong passwords");
                }
                hub.store.markDirty("users");
            }
            throw new ServiceException(401, "Wrong username or password");
        }
        if (!u.active) throw new ServiceException(403, "This account is turned off. Ask an admin.");
        u.failedLogins = 0;
        u.lockedUntil = 0;
        u.lastLogin = now;
        hub.store.markDirty("users");
        String token = Passwords.token();
        Session s = new Session();
        s.username = u.username;
        s.lastUsed = now;
        sessions.put(token, s);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("user", view(u));
        return out;
    }

    public void logout(String token) {
        if (token != null) sessions.remove(token);
    }

    /** The signed-in user for a token, or null if the token is unknown, expired or the account is off. */
    public User userFor(String token) {
        if (token == null) return null;
        Session s = sessions.get(token);
        long now = hub.now();
        if (s == null) return null;
        if (now - s.lastUsed > SESSION_IDLE_MS) {
            sessions.remove(token);
            return null;
        }
        User u = hub.store.users.get(s.username);
        if (u == null || !u.active) {
            sessions.remove(token);
            return null;
        }
        s.lastUsed = now;
        return u;
    }

    private void endSessionsOf(String username, String keepToken) {
        sessions.entrySet().removeIf(e -> e.getValue().username.equals(username) && !e.getKey().equals(keepToken));
    }

    public void changePassword(User u, String token, String current, String next) {
        if (!Passwords.matches(current == null ? "" : current, u.salt, u.hash)) throw ServiceException.bad("Current password is wrong");
        checkPassword(next);
        u.salt = Passwords.newSalt();
        u.hash = Passwords.hash(next, u.salt);
        endSessionsOf(u.username, token);
        hub.store.markDirty("users");
        hub.store.addEvent(hub.now(), u.username, "security", null, "Changed own password");
    }

    static void checkPassword(String p) {
        if (p == null || p.length() < 8) throw ServiceException.bad("Password must be at least 8 characters");
        if (p.length() > 128) throw ServiceException.bad("Password is too long");
    }

    // ------------------------------------------------------------------ team (admin)

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (User u : hub.store.users.values()) out.add(view(u));
        return out;
    }

    public Map<String, Object> create(User me, Map<String, Object> b) {
        Check.role(me, Role.ADMIN);
        String username = Check.text(b, "username", "Username", 32, true).toLowerCase();
        if (!username.matches("[a-z][a-z0-9._-]{2,31}")) throw ServiceException.bad("Username: 3-32 letters, numbers, . _ - and starting with a letter");
        if (hub.store.users.containsKey(username)) throw ServiceException.conflict("That username is taken");
        String name = Check.text(b, "displayName", "Name", 60, true);
        Role role = Check.choice(b, "role", Role.class, "Role", true);
        String pw = Check.text(b, "password", "Password", 128, true);
        checkPassword(pw);
        User u = new User();
        u.username = username;
        u.displayName = name;
        u.role = role;
        u.salt = Passwords.newSalt();
        u.hash = Passwords.hash(pw, u.salt);
        u.createdAt = hub.now();
        hub.store.users.put(username, u);
        hub.store.markDirty("users");
        hub.store.addEvent(hub.now(), me.username, "team", null, "Added " + name + " (" + role.label + ")");
        return view(u);
    }

    public Map<String, Object> update(User me, String username, Map<String, Object> b) {
        Check.role(me, Role.ADMIN);
        User u = hub.store.users.get(username);
        if (u == null) throw ServiceException.notFound("Person");
        String name = Check.text(b, "displayName", "Name", 60, false);
        Role role = Check.choice(b, "role", Role.class, "Role", false);
        Boolean active = b.containsKey("active") ? Boolean.TRUE.equals(b.get("active")) : null;
        String pw = Check.text(b, "password", "Password", 128, false);
        boolean losesAdmin = (role != null && role != Role.ADMIN) || Boolean.FALSE.equals(active);
        if (u.role == Role.ADMIN && losesAdmin) {
            long admins = hub.store.users.values().stream().filter(x -> x.role == Role.ADMIN && x.active).count();
            if (admins <= 1) throw ServiceException.bad("There must be at least one active admin");
            if (u.username.equals(me.username)) throw ServiceException.bad("You cannot remove your own admin access");
        }
        if (name != null) u.displayName = name;
        if (role != null) u.role = role;
        if (active != null) {
            u.active = active;
            if (!active) endSessionsOf(u.username, null);
        }
        if (pw != null) {
            checkPassword(pw);
            u.salt = Passwords.newSalt();
            u.hash = Passwords.hash(pw, u.salt);
            u.lockedUntil = 0;
            u.failedLogins = 0;
            endSessionsOf(u.username, null);
        }
        hub.store.markDirty("users");
        hub.store.addEvent(hub.now(), me.username, "team", null, "Updated " + u.displayName
                + (pw != null ? " (password reset)" : "") + (active != null ? (active ? " (turned on)" : " (turned off)") : ""));
        return view(u);
    }
}
