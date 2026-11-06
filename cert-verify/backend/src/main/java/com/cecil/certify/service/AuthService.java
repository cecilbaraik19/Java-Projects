package com.cecil.certify.service;

import com.cecil.certify.model.Role;
import com.cecil.certify.model.User;
import com.cecil.certify.store.DataStore;
import com.cecil.certify.util.Clock;
import com.cecil.certify.util.Crypto;
import com.cecil.certify.util.Passwords;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Sign in, sign out, sessions and accounts. */
public final class AuthService {

    public static final int MAX_FAILS = 5;
    public static final long LOCK_MS = 5 * 60_000L;
    public static final long IDLE_MS = 12 * 3_600_000L;
    private static final Pattern USERNAME = Pattern.compile("[a-z][a-z0-9_.]{2,23}");

    private final DataStore store;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public AuthService(DataStore store) {
        this.store = store;
    }

    public static final class Session {
        public final String id;
        public final String username;
        public volatile long lastSeen;

        Session(String id, String username) {
            this.id = id;
            this.username = username;
            this.lastSeen = Clock.now();
        }
    }

    /** Returns a new session, or throws with a message that is the SAME for unknown user and wrong password. */
    public synchronized Session login(String username, String password) {
        String u = username == null ? "" : username.trim().toLowerCase();
        User user = store.users.get(u);
        if (user == null) {
            Passwords.check(password == null ? "" : password, Passwords.DUMMY); // same time as a real check
            throw new SecurityException("Wrong username or password");
        }
        if (user.lockedUntil > Clock.now()) {
            long mins = Math.max(1, (user.lockedUntil - Clock.now() + 59_999) / 60_000);
            throw new SecurityException("Too many wrong tries. This account is locked for " + mins + " more minute" + (mins == 1 ? "" : "s") + ".");
        }
        if (!Passwords.check(password == null ? "" : password, user.passwordHash)) {
            user.failedLogins++;
            if (user.failedLogins >= MAX_FAILS) {
                user.lockedUntil = Clock.now() + LOCK_MS;
                user.failedLogins = 0;
            }
            store.saveUsers();
            throw new SecurityException("Wrong username or password");
        }
        user.failedLogins = 0;
        user.lockedUntil = 0;
        store.saveUsers();
        Session s = new Session(Crypto.randomToken(32), user.username);
        sessions.put(s.id, s);
        return s;
    }

    public void logout(String sessionId) {
        if (sessionId != null) sessions.remove(sessionId);
    }

    /** The signed-in user for this session id, or null. Also ends sessions that were idle too long. */
    public User userFor(String sessionId) {
        if (sessionId == null) return null;
        Session s = sessions.get(sessionId);
        if (s == null) return null;
        if (Clock.now() - s.lastSeen > IDLE_MS) {
            sessions.remove(sessionId);
            return null;
        }
        s.lastSeen = Clock.now();
        return store.users.get(s.username);
    }

    public synchronized User createUser(String username, String displayName, Role role, String issuerId, String password) {
        String u = username == null ? "" : username.trim().toLowerCase();
        if (!USERNAME.matcher(u).matches()) throw new IllegalArgumentException("Username: 3-24 small letters, digits, _ or . , starting with a letter");
        if (store.users.containsKey(u)) throw new IllegalArgumentException("That username is taken");
        if (password == null || password.length() < 8) throw new IllegalArgumentException("Password must be at least 8 characters");
        if (role == Role.ISSUER && (issuerId == null || !store.issuers.containsKey(issuerId)))
            throw new IllegalArgumentException("Choose the institution this person works for");
        User user = new User();
        user.username = u;
        user.displayName = displayName == null || displayName.isBlank() ? u : displayName.trim();
        if (user.displayName.length() > 60) user.displayName = user.displayName.substring(0, 60);
        user.role = role;
        user.issuerId = role == Role.ISSUER ? issuerId : "";
        user.passwordHash = Passwords.hash(password);
        user.createdAt = Clock.now();
        store.users.put(u, user);
        store.saveUsers();
        return user;
    }

    public int activeSessions() {
        return sessions.size();
    }
}
