package com.cecil.ngo.service;

import com.cecil.ngo.model.Enums.Role;
import com.cecil.ngo.model.User;
import com.cecil.ngo.store.DataStore;
import com.cecil.ngo.util.Passwords;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sign in, sign out and sessions.
 * - 5 wrong passwords lock that username for 5 minutes.
 * - A session token is random (256 bits) and ends after 12 hours of no use.
 */
public class AuthService {
    public static final int MAX_FAILS = 5;
    public static final Duration LOCK_TIME = Duration.ofMinutes(5);
    public static final Duration IDLE_LIMIT = Duration.ofHours(12);

    private final DataStore store;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Fails> fails = new ConcurrentHashMap<>();

    public static class Session {
        public final String token;
        public final String username;
        volatile Instant lastSeen;
        Session(String t, String u, Instant now) { token = t; username = u; lastSeen = now; }
    }

    private static class Fails {
        int count;
        Instant lockedUntil;
    }

    public AuthService(DataStore store) { this.store = store; }

    private Instant now() { return store.clock.instant(); }

    public Session login(String username, String password) {
        if (username == null || username.isBlank() || password == null) throw ApiError.bad("Enter your username and password");
        String key = username.trim().toLowerCase();
        Fails f = fails.get(key);
        if (f != null && f.lockedUntil != null && now().isBefore(f.lockedUntil)) {
            long min = Math.max(1, Duration.between(now(), f.lockedUntil).toMinutes() + 1);
            throw new ApiError(429, "Too many wrong tries. Try again in " + min + " minute" + (min == 1 ? "" : "s") + ".");
        }
        User u;
        synchronized (store) { u = store.user(key); }
        // check the password even when the user does not exist, so timing does not reveal valid usernames
        boolean ok = Passwords.verify(password, u != null ? u.passwordHash : "pbkdf2$1000$AAAAAAAAAAAAAAAAAAAAAA==$AAAA");
        if (u == null || !ok || !u.active) {
            Fails nf = fails.computeIfAbsent(key, k -> new Fails());
            synchronized (nf) {
                nf.count++;
                if (nf.count >= MAX_FAILS) { nf.lockedUntil = now().plus(LOCK_TIME); nf.count = 0; }
            }
            if (u != null && ok && !u.active) throw new ApiError(401, "This account is turned off. Ask the admin.");
            throw new ApiError(401, "Wrong username or password");
        }
        fails.remove(key);
        Session s = new Session(Passwords.newToken(), u.username, now());
        sessions.put(s.token, s);
        store.log(u.username, "signed in", "signed in", null);
        return s;
    }

    public void logout(String token) {
        if (token != null) sessions.remove(token);
    }

    /** Finds the signed-in user for a token, or throws 401. */
    public User require(String token) {
        if (token == null) throw new ApiError(401, "Please sign in");
        Session s = sessions.get(token);
        if (s == null) throw new ApiError(401, "Please sign in");
        if (Duration.between(s.lastSeen, now()).compareTo(IDLE_LIMIT) > 0) {
            sessions.remove(token);
            throw new ApiError(401, "Your session ended. Please sign in again.");
        }
        User u;
        synchronized (store) { u = store.user(s.username); }
        if (u == null || !u.active) {
            sessions.remove(token);
            throw new ApiError(401, "Please sign in");
        }
        s.lastSeen = now();
        return u;
    }

    public void changePassword(User u, String current, String next, String keepToken) {
        if (!Passwords.verify(current, u.passwordHash)) throw ApiError.bad("Current password is wrong");
        try { Passwords.checkStrength(next); } catch (IllegalArgumentException e) { throw ApiError.bad(e.getMessage()); }
        synchronized (store) {
            u.passwordHash = Passwords.hash(next);
            store.saveUsers();
        }
        endSessionsOf(u.username, keepToken);
        store.log(u.username, "password", "changed their password", null);
    }

    /** Signs a person out everywhere (keepToken stays, so changing your own password keeps you signed in here). */
    public void endSessionsOf(String username, String keepToken) {
        Iterator<Session> it = sessions.values().iterator();
        while (it.hasNext()) {
            Session s = it.next();
            if (s.username.equalsIgnoreCase(username) && !s.token.equals(keepToken)) it.remove();
        }
    }

    /** Removes old sessions (called every few minutes). */
    public void sweep() {
        Instant n = now();
        sessions.values().removeIf(s -> Duration.between(s.lastSeen, n).compareTo(IDLE_LIMIT) > 0);
        fails.values().removeIf(f -> f.lockedUntil != null && n.isAfter(f.lockedUntil) && f.count == 0);
    }

    // ------------------------------------------------------------------ who may do what
    //   ADMIN      everything
    //   MANAGER    programs, activities, volunteers, beneficiaries; submits expenses; approves hours
    //   FINANCE    donors and donations; approves or rejects expenses; submits expenses
    //   VOLUNTEER  sees open activities, signs up, logs their own hours (no money or beneficiary data)

    public static boolean isStaff(User u) { return u.role != Role.VOLUNTEER; }
    public static boolean canPrograms(User u) { return u.role == Role.ADMIN || u.role == Role.MANAGER; }
    public static boolean canFinance(User u) { return u.role == Role.ADMIN || u.role == Role.FINANCE; }

    public static void needStaff(User u, String what) {
        if (!isStaff(u)) throw ApiError.forbidden("Volunteers cannot " + what);
    }

    public static void needPrograms(User u, String what) {
        if (!canPrograms(u)) throw ApiError.forbidden("Only a program manager or the admin can " + what);
    }

    public static void needFinance(User u, String what) {
        if (!canFinance(u)) throw ApiError.forbidden("Only the finance officer or the admin can " + what);
    }

    public static void needAdmin(User u, String what) {
        if (u.role != Role.ADMIN) throw ApiError.forbidden("Only the admin can " + what);
    }
}
