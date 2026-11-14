package com.cecil.kaksh.service;

import com.cecil.kaksh.model.User;
import com.cecil.kaksh.store.DataStore;
import com.cecil.kaksh.util.Clock;
import com.cecil.kaksh.util.Passwords;
import com.cecil.kaksh.util.Text;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Signing in and out, and sessions.
 *  - 5 wrong passwords in a row lock the account for 5 minutes.
 *  - The same message for "no such user" and "wrong password" (so nobody can find out usernames).
 *  - A session ends after 10 hours without use. Only the SHA-256 hash of the session token is kept.
 * There is no "join" page: in an office, accounts are made by the facility manager (here: the demo data).
 */
public class AuthService {
    public static final int MAX_FAILS = 5;
    public static final long LOCK_MS = 5 * 60_000L;
    public static final long IDLE_MS = 10 * 3_600_000L;
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
                store.log(null, "login.fail", Text.clean(username, 40), "unknown account");
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

    /** The signed-in person for this token, or null. */
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

    public void changePassword(User u, String current, String next) {
        if (!Passwords.verify(current, u.passwordHash)) throw Problem.bad("Your current password is not right.");
        String p = Passwords.problem(next);
        if (p != null) throw Problem.bad(p);
        synchronized (store) {
            u.passwordHash = Passwords.hash(next);
            store.log(u.id, "password.change", u.username, null);
            store.save("users", "audit");
        }
        sessions.values().removeIf(s -> s.userId.equals(u.id));
    }
}
