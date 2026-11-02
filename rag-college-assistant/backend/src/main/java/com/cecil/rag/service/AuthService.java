package com.cecil.rag.service;

import com.cecil.rag.model.User;
import com.cecil.rag.store.DataStore;
import com.cecil.rag.util.Clock;
import com.cecil.rag.util.Passwords;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sign in / sign out and sessions.
 *  - passwords are checked against the PBKDF2 hash
 *  - 5 wrong passwords lock the account for 5 minutes
 *  - a session ends after 12 hours without use
 */
public final class AuthService {
    public static final long IDLE_LIMIT = 12L * 60 * 60 * 1000;
    public static final int MAX_FAILS = 5;
    public static final long LOCK_TIME = 5L * 60 * 1000;

    private final DataStore store;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public static final class Session {
        public final String token;
        public final String userId;
        public long lastSeen;
        Session(String token, String userId) { this.token = token; this.userId = userId; this.lastSeen = Clock.now(); }
    }

    public AuthService(DataStore store) { this.store = store; }

    public Session login(String username, String password) {
        if (username == null || password == null || username.length() > 60 || password.length() > 200)
            throw new ApiError(401, "Wrong username or password.");
        User u;
        synchronized (store) {
            u = store.userByName(username.strip());
            if (u == null || !u.active) {
                Passwords.verify(password, Passwords.hash("dummy-password")); // same time as a real check
                throw new ApiError(401, "Wrong username or password.");
            }
            long now = Clock.now();
            if (u.lockedUntil > now) {
                long mins = Math.max(1, (u.lockedUntil - now + 59_999) / 60_000);
                throw new ApiError(423, "Too many wrong passwords. Try again in " + mins + " minute(s).");
            }
            if (!Passwords.verify(password, u.passwordHash)) {
                u.failed++;
                if (u.failed >= MAX_FAILS) { u.lockedUntil = now + LOCK_TIME; u.failed = 0; }
                store.saveUsers();
                throw new ApiError(401, "Wrong username or password.");
            }
            u.failed = 0;
            u.lockedUntil = 0;
            u.lastLogin = now;
            store.saveUsers();
        }
        Session s = new Session(Passwords.token(), u.id);
        sessions.put(s.token, s);
        return s;
    }

    /** Finds the signed-in user for a session token, or null. */
    public User user(String token) {
        if (token == null) return null;
        Session s = sessions.get(token);
        if (s == null) return null;
        long now = Clock.now();
        if (now - s.lastSeen > IDLE_LIMIT) { sessions.remove(token); return null; }
        User u = store.users.get(s.userId);
        if (u == null || !u.active) { sessions.remove(token); return null; }
        s.lastSeen = now;
        return u;
    }

    public void logout(String token) { if (token != null) sessions.remove(token); }

    public void logoutEverywhere(String userId) { sessions.values().removeIf(s -> s.userId.equals(userId)); }

    public void changePassword(User u, String oldPw, String newPw) {
        if (!Passwords.verify(oldPw, u.passwordHash)) throw ApiError.bad("The current password is wrong.");
        String problem = Passwords.problem(newPw);
        if (problem != null) throw ApiError.bad(problem);
        synchronized (store) {
            u.passwordHash = Passwords.hash(newPw);
            store.saveUsers();
        }
    }
}
