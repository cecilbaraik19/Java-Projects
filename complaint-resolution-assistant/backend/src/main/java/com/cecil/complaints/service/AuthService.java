package com.cecil.complaints.service;

import com.cecil.complaints.model.Enums;
import com.cecil.complaints.model.Enums.Role;
import com.cecil.complaints.model.User;
import com.cecil.complaints.store.DataStore;
import com.cecil.complaints.util.Passwords;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Login, logout and session handling for the agent console.
 * Sessions live in memory and expire after 8 hours.
 * After 5 wrong passwords a username is locked for 5 minutes (brute-force protection).
 */
public class AuthService {

    public static final Duration SESSION_LENGTH = Duration.ofHours(8);
    private static final int MAX_FAILS = 5;
    private static final Duration LOCK_TIME = Duration.ofMinutes(5);
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9._-]{3,30}");

    private record Session(String username, Instant expires) {
    }

    private record Fails(int count, Instant lockedUntil) {
    }

    private final DataStore store;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Fails> fails = new ConcurrentHashMap<>();

    public AuthService(DataStore store) {
        this.store = store;
    }

    /** Creates the demo accounts the first time the app runs. */
    public void seedUsers() {
        synchronized (store) {
            if (!store.users().isEmpty()) {
                return;
            }
            createUserInternal("admin", "Admin", Role.ADMIN, "admin123");
            createUserInternal("priya", "Priya Kapoor", Role.AGENT, "agent123");
            createUserInternal("rahul", "Rahul Joshi", Role.AGENT, "agent123");
            store.save();
        }
    }

    /** @return session token */
    public String login(String username, String password) {
        String u = username == null ? "" : username.trim().toLowerCase();
        Fails f = fails.get(u);
        if (f != null && f.lockedUntil() != null && Instant.now().isBefore(f.lockedUntil())) {
            throw new ServiceException(429, "Too many failed attempts. Try again in a few minutes.");
        }
        User user;
        synchronized (store) {
            user = store.users().get(u);
        }
        if (user == null || !user.active || !Passwords.verify(password, user.salt, user.passwordHash)) {
            int count = f == null ? 1 : f.count() + 1;
            fails.put(u, new Fails(count, count >= MAX_FAILS ? Instant.now().plus(LOCK_TIME) : null));
            throw ServiceException.unauthorized("Wrong username or password");
        }
        fails.remove(u);
        String token = Passwords.newToken();
        sessions.put(token, new Session(user.username, Instant.now().plus(SESSION_LENGTH)));
        return token;
    }

    public void logout(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    /** @return the logged-in user, or null if the token is missing/expired */
    public User userForToken(String token) {
        if (token == null) {
            return null;
        }
        Session s = sessions.get(token);
        if (s == null) {
            return null;
        }
        if (Instant.now().isAfter(s.expires())) {
            sessions.remove(token);
            return null;
        }
        synchronized (store) {
            User u = store.users().get(s.username());
            return u != null && u.active ? u : null;
        }
    }

    public List<User> users() {
        synchronized (store) {
            return new ArrayList<>(store.users().values());
        }
    }

    public User createUser(String username, String displayName, String role, String password) {
        String u = username == null ? "" : username.trim().toLowerCase();
        if (!USERNAME.matcher(u).matches()) {
            throw ServiceException.badRequest("Username must be 3-30 characters: a-z, 0-9, dot, dash or underscore");
        }
        if (displayName == null || displayName.isBlank() || displayName.length() > 60) {
            throw ServiceException.badRequest("Display name is required (max 60 characters)");
        }
        if (password == null || password.length() < 6) {
            throw ServiceException.badRequest("Password must be at least 6 characters");
        }
        synchronized (store) {
            if (store.users().containsKey(u)) {
                throw new ServiceException(409, "Username already exists");
            }
            User user = createUserInternal(u, displayName.trim(), Enums.parse(Role.class, role, Role.AGENT), password);
            store.save();
            return user;
        }
    }

    private User createUserInternal(String username, String displayName, Role role, String password) {
        User u = new User();
        u.username = username;
        u.displayName = displayName;
        u.role = role;
        u.salt = Passwords.newSalt();
        u.passwordHash = Passwords.hash(password, u.salt);
        store.users().put(username, u);
        return u;
    }

    public String displayName(String username) {
        if (username == null) {
            return null;
        }
        synchronized (store) {
            User u = store.users().get(username);
            return u == null ? username : u.displayName;
        }
    }
}
