package com.cecil.sambandh.service;

import com.cecil.sambandh.model.Role;
import com.cecil.sambandh.model.User;
import com.cecil.sambandh.store.DataStore;
import com.cecil.sambandh.util.Clock;
import com.cecil.sambandh.util.Json;
import com.cecil.sambandh.util.Passwords;
import com.cecil.sambandh.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sign in, sign out, sessions and staff accounts.
 *  - 5 wrong passwords in a row lock the account for 5 minutes.
 *  - The same message is shown for "no such user" and "wrong password" (so nobody can find out usernames).
 *  - A session ends after 12 hours without use. Only a hash of the session token is kept in memory.
 */
public class AuthService {
    public static final int MAX_FAILS = 5;
    public static final long LOCK_MS = 5 * 60_000L;
    public static final long IDLE_MS = 12 * 3_600_000L;
    public static final String BAD_LOGIN = "Wrong username or password.";

    private final DataStore store;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();   // key = SHA-256 of the token

    public static final class Session {
        public final String userId;
        public volatile long lastSeen;
        Session(String userId, long lastSeen) { this.userId = userId; this.lastSeen = lastSeen; }
    }

    public AuthService(DataStore store) { this.store = store; }

    /** Returns a new session token, or throws Problem(401) with a friendly message. */
    public String login(String username, String password) {
        synchronized (store) {
            User u = store.userByName(username);
            long now = Clock.millis();
            if (u == null || !u.active) {
                store.log(null, "login.fail", Text.clean(username, 40), "unknown or switched-off account");
                store.save("audit");
                throw new Problem(401, BAD_LOGIN);
            }
            if (u.lockedUntil > now) {
                long mins = Math.max(1, (u.lockedUntil - now + 59_999) / 60_000);
                throw new Problem(429, "Too many wrong passwords. Try again in " + mins + " minute" + (mins == 1 ? "" : "s") + ".");
            }
            if (!Passwords.verify(password, u.passwordHash)) {
                u.failedLogins++;
                String detail = "wrong password (" + u.failedLogins + ")";
                if (u.failedLogins >= MAX_FAILS) {
                    u.lockedUntil = now + LOCK_MS;
                    u.failedLogins = 0;
                    detail = "account locked for 5 minutes";
                }
                store.log(u.id, "login.fail", u.username, detail);
                store.save("users", "audit");
                throw new Problem(401, BAD_LOGIN);
            }
            u.failedLogins = 0;
            u.lockedUntil = 0;
            store.log(u.id, "login", u.username, null);
            store.save("users", "audit");
            String token = Passwords.token();
            sessions.put(Passwords.sha256(token), new Session(u.id, now));
            return token;
        }
    }

    /** The signed-in user for this token, or null (unknown, expired, or account switched off). */
    public User userFor(String token) {
        if (token == null || token.isBlank()) return null;
        String key = Passwords.sha256(token);
        Session s = sessions.get(key);
        if (s == null) return null;
        long now = Clock.millis();
        if (now - s.lastSeen > IDLE_MS) { sessions.remove(key); return null; }
        User u;
        synchronized (store) { u = store.users.get(s.userId); }
        if (u == null || !u.active) { sessions.remove(key); return null; }
        s.lastSeen = now;
        return u;
    }

    public void logout(String token) {
        if (token != null) sessions.remove(Passwords.sha256(token));
    }

    /** Ends every session of one user (after a password reset or switching the account off). */
    public void endSessionsOf(String userId) {
        sessions.values().removeIf(s -> s.userId.equals(userId));
    }

    public int sessionCount() { return sessions.size(); }

    // ------------------------------------------------------------------ staff accounts (admin)

    public List<Map<String, Object>> listUsers() {
        synchronized (store) {
            List<Map<String, Object>> out = new ArrayList<>();
            long now = Clock.millis();
            for (User u : store.users.values()) {
                Map<String, Object> m = u.publicView();
                m.put("locked", u.lockedUntil > now);
                long owned = store.customers.values().stream().filter(c -> c.visible() && u.id.equals(c.ownerId)).count();
                m.put("customers", owned);
                out.add(m);
            }
            return out;
        }
    }

    public User createUser(User actor, Map<String, Object> body) {
        requireAdmin(actor);
        String username = Text.clean(Json.str(body, "username"), 30);
        String name = Text.clean(Json.str(body, "name"), 60);
        Role role = Role.parse(Json.str(body, "role"));
        String password = Json.str(body, "password");
        if (username == null || !username.matches("[a-zA-Z0-9._-]{3,30}"))
            throw Problem.bad("Username: 3-30 letters, digits, dot, dash or underscore.");
        if (name == null) throw Problem.bad("Please give the person's name.");
        if (role == null) throw Problem.bad("Choose a role.");
        String pw = Passwords.problem(password);
        if (pw != null) throw Problem.bad(pw);
        synchronized (store) {
            if (store.userByName(username) != null) throw Problem.conflict("That username is already taken.");
            User u = new User();
            u.id = store.nextId("U", 11);
            u.username = username.toLowerCase();
            u.name = name;
            u.role = role;
            u.passwordHash = Passwords.hash(password);
            u.createdAt = Clock.millis();
            store.users.put(u.id, u);
            store.log(actor.id, "user.create", u.id, u.username + " as " + role.label);
            store.save("users", "audit");
            return u;
        }
    }

    /** Admin changes a staff account: role, switch on/off, unlock, new password. */
    public User updateUser(User actor, String id, Map<String, Object> body) {
        requireAdmin(actor);
        synchronized (store) {
            User u = store.users.get(id);
            if (u == null) throw Problem.notFound("Staff member");
            List<String> changes = new ArrayList<>();
            if (body.containsKey("role")) {
                Role r = Role.parse(Json.str(body, "role"));
                if (r == null) throw Problem.bad("Unknown role.");
                if (u.id.equals(actor.id) && r != Role.ADMIN) throw Problem.bad("You cannot remove your own admin role.");
                if (r != u.role) { changes.add("role " + u.role.label + " → " + r.label); u.role = r; }
            }
            if (body.containsKey("active")) {
                boolean a = Json.bool(body, "active", true);
                if (u.id.equals(actor.id) && !a) throw Problem.bad("You cannot switch off your own account.");
                if (!a && store.customers.values().stream().anyMatch(c -> c.visible() && u.id.equals(c.ownerId)))
                    throw Problem.bad("Move this person's customers to someone else first (Customers → select → Re-assign).");
                if (a != u.active) { changes.add(a ? "switched on" : "switched off"); u.active = a; }
                if (!a) endSessionsOf(u.id);
            }
            if (Json.bool(body, "unlock", false)) { u.lockedUntil = 0; u.failedLogins = 0; changes.add("unlocked"); }
            String pw = Json.str(body, "password");
            if (pw != null && !pw.isEmpty()) {
                String p = Passwords.problem(pw);
                if (p != null) throw Problem.bad(p);
                u.passwordHash = Passwords.hash(pw);
                endSessionsOf(u.id);
                changes.add("new password");
            }
            if (changes.isEmpty()) throw Problem.bad("Nothing to change.");
            store.log(actor.id, "user.update", u.id, String.join(", ", changes));
            store.save("users", "audit");
            return u;
        }
    }

    /** Anyone signed in can change their own password (needs the current one). */
    public void changeOwnPassword(User actor, String current, String next) {
        synchronized (store) {
            if (!Passwords.verify(current, actor.passwordHash)) throw Problem.bad("Your current password is not right.");
            String p = Passwords.problem(next);
            if (p != null) throw Problem.bad(p);
            actor.passwordHash = Passwords.hash(next);
            store.log(actor.id, "user.password", actor.id, "changed own password");
            store.save("users", "audit");
        }
    }

    private static void requireAdmin(User actor) {
        if (actor.role != Role.ADMIN) throw Problem.forbidden("Only an admin can manage staff accounts.");
    }
}
