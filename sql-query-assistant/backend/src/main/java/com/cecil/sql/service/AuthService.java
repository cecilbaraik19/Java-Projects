package com.cecil.sql.service;

import com.cecil.sql.model.Role;
import com.cecil.sql.model.User;
import com.cecil.sql.store.DataStore;
import com.cecil.sql.util.Clock;
import com.cecil.sql.util.Passwords;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sign in, sessions and accounts.
 *  - passwords checked with PBKDF2 (see Passwords), same message for "no such user" and "wrong password"
 *  - 5 wrong passwords lock the account for 5 minutes
 *  - a session ends after 12 hours without use
 */
public final class AuthService {
    public static final int MAX_FAILS = 5;
    public static final long LOCK_MILLIS = 5 * 60_000L;
    public static final long IDLE_MILLIS = 12 * 3600_000L;
    public static final String BAD_LOGIN = "Wrong username or password.";

    public static final class Session {
        public final String token, username;
        public volatile long lastSeen;
        Session(String token, String username) { this.token = token; this.username = username; this.lastSeen = Clock.millis(); }
    }

    public static final class AuthException extends RuntimeException {
        public AuthException(String m) { super(m); }
    }

    private final DataStore store;
    private final Map<String, User> users = new LinkedHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public AuthService(DataStore store, List<User> initial) {
        this.store = store;
        for (User u : initial) users.put(u.username, u);
    }

    public synchronized Session login(String username, String password) {
        String name = username == null ? "" : username.trim().toLowerCase();
        User u = users.get(name);
        if (u == null) {
            Passwords.dummyVerify(password);   // spend the same time, so timing does not reveal real usernames
            throw new AuthException(BAD_LOGIN);
        }
        long now = Clock.millis();
        if (u.lockedUntil > now) {
            long mins = Math.max(1, (u.lockedUntil - now + 59_999) / 60_000);
            throw new AuthException("Too many wrong passwords. Try again in " + mins + " minute" + (mins == 1 ? "" : "s") + ".");
        }
        if (!Passwords.verify(password, u.passwordHash)) {
            u.failedLogins++;
            if (u.failedLogins >= MAX_FAILS) { u.lockedUntil = now + LOCK_MILLIS; u.failedLogins = 0; }
            save();
            throw new AuthException(BAD_LOGIN);
        }
        u.failedLogins = 0;
        u.lockedUntil = 0;
        save();
        Session s = new Session(Passwords.token(), u.username);
        sessions.put(s.token, s);
        return s;
    }

    public void logout(String token) { if (token != null) sessions.remove(token); }

    /** The signed-in user for this session token, or null. */
    public synchronized User user(String token) {
        if (token == null) return null;
        Session s = sessions.get(token);
        if (s == null) return null;
        long now = Clock.millis();
        if (now - s.lastSeen > IDLE_MILLIS) { sessions.remove(token); return null; }
        s.lastSeen = now;
        return users.get(s.username);
    }

    public synchronized User find(String username) { return users.get(username); }
    public synchronized List<User> all() { return new ArrayList<>(users.values()); }

    public synchronized User create(String username, String displayName, Role role, String password) {
        String name = username == null ? "" : username.trim().toLowerCase();
        if (!name.matches("[a-z][a-z0-9_.]{2,23}")) throw new IllegalArgumentException("Username: 3–24 letters or digits, starting with a letter.");
        if (users.containsKey(name)) throw new IllegalArgumentException("That username is taken.");
        checkPassword(password);
        User u = new User(name, displayName == null || displayName.isBlank() ? name : displayName.trim(), role, Passwords.hash(password), Clock.now().toString());
        users.put(name, u);
        save();
        return u;
    }

    public synchronized void update(String username, String displayName, Role role, String newPassword, User actor) {
        User u = users.get(username);
        if (u == null) throw new IllegalArgumentException("No such user.");
        if (role != null && u == actor && role != Role.ADMIN) throw new IllegalArgumentException("You cannot remove your own admin rights.");
        if (displayName != null && !displayName.isBlank()) u.displayName = displayName.trim();
        if (role != null) u.role = role;
        if (newPassword != null && !newPassword.isEmpty()) {
            checkPassword(newPassword);
            u.passwordHash = Passwords.hash(newPassword);
            sessions.values().removeIf(s -> s.username.equals(username));   // sign out everywhere
        }
        u.lockedUntil = 0;
        u.failedLogins = 0;
        save();
    }

    public synchronized void delete(String username, User actor) {
        if (actor.username.equals(username)) throw new IllegalArgumentException("You cannot delete yourself.");
        if (users.remove(username) == null) throw new IllegalArgumentException("No such user.");
        sessions.values().removeIf(s -> s.username.equals(username));
        save();
    }

    static void checkPassword(String p) {
        if (p == null || p.length() < 8) throw new IllegalArgumentException("Password must be at least 8 characters.");
    }

    private void save() { store.saveUsers(users.values()); }
}
