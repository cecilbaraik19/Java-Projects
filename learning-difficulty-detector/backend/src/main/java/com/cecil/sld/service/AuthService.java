package com.cecil.sld.service;

import com.cecil.sld.model.Role;
import com.cecil.sld.model.User;
import com.cecil.sld.store.DataStore;
import com.cecil.sld.util.Passwords;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sign in, sign out and sessions.
 * - 5 wrong passwords lock that username for 5 minutes.
 * - A session ends after 12 hours without use.
 * Sessions live in memory, so restarting the server signs everybody out.
 */
public class AuthService {
    public static final int MAX_TRIES = 5;
    public static final Duration LOCK_TIME = Duration.ofMinutes(5);
    public static final Duration IDLE_LIMIT = Duration.ofHours(12);

    private final DataStore db;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    public AuthService(DataStore db) { this.db = db; }

    /** A signed-in browser. */
    public static final class Session {
        public final String token;
        public final String userId;
        public volatile Instant lastSeen;
        Session(String token, String userId, Instant now) { this.token = token; this.userId = userId; this.lastSeen = now; }
    }

    private static final class Failures {
        int count;
        Instant lockedUntil;
    }

    private Instant now() { return db.clock().instant(); }

    /** Check the password and start a session. Returns the session token. */
    public Session login(String username, String password) {
        String key = username == null ? "" : username.strip().toLowerCase();
        Failures f = failures.get(key);
        if (f != null && f.lockedUntil != null && now().isBefore(f.lockedUntil)) {
            long mins = Math.max(1, Duration.between(now(), f.lockedUntil).toMinutes() + 1);
            throw new ApiError(429, "Too many wrong tries. Try again in " + mins + " minute" + (mins == 1 ? "" : "s") + ".");
        }
        User u = db.userByName(key);
        boolean ok = u != null && u.active && Passwords.verify(password, u.passwordHash);
        if (!ok) {
            Failures nf = failures.computeIfAbsent(key, k -> new Failures());
            synchronized (nf) {
                nf.count++;
                if (nf.count >= MAX_TRIES) {
                    nf.lockedUntil = now().plus(LOCK_TIME);
                    nf.count = 0;
                }
            }
            throw new ApiError(401, "Wrong username or password.");
        }
        failures.remove(key);
        Session s = new Session(Passwords.token(), u.id, now());
        sessions.put(s.token, s);
        synchronized (db) {
            db.log(u.name, "Signed in", u.role.label);
            db.save("events");
        }
        return s;
    }

    /** The user for a token, or null if the token is unknown or too old. */
    public User check(String token) {
        if (token == null) return null;
        Session s = sessions.get(token);
        if (s == null) return null;
        if (Duration.between(s.lastSeen, now()).compareTo(IDLE_LIMIT) > 0) {
            sessions.remove(token);
            return null;
        }
        User u = db.user(s.userId);
        if (u == null || !u.active) {
            sessions.remove(token);
            return null;
        }
        s.lastSeen = now();
        return u;
    }

    public void logout(String token) {
        if (token != null) sessions.remove(token);
    }

    /** Sign out every session of a user (used when a login is turned off or its password reset). */
    public void endSessionsOf(String userId) {
        sessions.values().removeIf(s -> s.userId.equals(userId));
    }

    public static void require(User u, Role... allowed) {
        if (u == null) throw new ApiError(401, "Please sign in.");
        for (Role r : allowed) if (u.role == r) return;
        throw ApiError.forbidden();
    }
}
