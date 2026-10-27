package com.cecil.pipeline.service;

import com.cecil.pipeline.model.Enums;
import com.cecil.pipeline.model.Enums.Role;
import com.cecil.pipeline.model.User;
import com.cecil.pipeline.store.DataStore;
import com.cecil.pipeline.util.Json;
import com.cecil.pipeline.util.Passwords;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Sign-in, sessions and team (user) management. */
public class AuthService {

    private static final Duration SESSION_TTL = Duration.ofHours(12);
    private static final int MAX_FAILS = 5;
    private static final Duration LOCK_TIME = Duration.ofMinutes(5);
    private static final Pattern USERNAME = Pattern.compile("[a-z][a-z0-9._-]{2,23}");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final DataStore store;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Fails> fails = new ConcurrentHashMap<>();

    private record Session(String username, Instant expires) { }
    private static final class Fails { int count; Instant lockedUntil; }

    public AuthService(DataStore store) {
        this.store = store;
    }

    // ------------------------------------------------------------------ sign in / out

    /** Returns a new session token. Too many wrong passwords locks the username for a few minutes. */
    public String login(String username, String password) {
        if (username == null || password == null || username.isBlank()) throw ServiceException.bad("Enter your username and password");
        String key = username.trim().toLowerCase();
        Fails f = fails.get(key);
        if (f != null && f.lockedUntil != null && Instant.now().isBefore(f.lockedUntil)) {
            throw new ServiceException(429, "Too many wrong attempts. Try again in a few minutes.");
        }
        User u = store.user(key);
        boolean ok = u != null && u.active && Passwords.verify(password, u.passwordHash);
        if (!ok) {
            Fails nf = fails.computeIfAbsent(key, k -> new Fails());
            synchronized (nf) {
                nf.count++;
                if (nf.count >= MAX_FAILS) { nf.lockedUntil = Instant.now().plus(LOCK_TIME); nf.count = 0; }
            }
            throw new ServiceException(401, "Wrong username or password");
        }
        fails.remove(key);
        String token = Passwords.newToken();
        sessions.put(token, new Session(u.username, Instant.now().plus(SESSION_TTL)));
        return token;
    }

    public void logout(String token) {
        if (token != null) sessions.remove(token);
    }

    /** The signed-in user for a token, or null if the token is unknown, expired, or the user was turned off. */
    public User userFor(String token) {
        if (token == null) return null;
        Session s = sessions.get(token);
        if (s == null) return null;
        if (Instant.now().isAfter(s.expires())) { sessions.remove(token); return null; }
        User u = store.user(s.username());
        if (u == null || !u.active) { sessions.remove(token); return null; }
        sessions.put(token, new Session(s.username(), Instant.now().plus(SESSION_TTL))); // sliding expiry
        return u;
    }

    public void changePassword(User me, String current, String next) {
        if (!Passwords.verify(current, me.passwordHash)) throw ServiceException.bad("Your current password is wrong");
        checkPassword(next);
        synchronized (store) {
            me.passwordHash = Passwords.hash(next);
            store.save();
        }
        // sign out other sessions of this user
        sessions.entrySet().removeIf(e -> e.getValue().username().equals(me.username));
    }

    // ------------------------------------------------------------------ team management

    public List<Map<String, Object>> listUsers(boolean includeInactive) {
        synchronized (store) {
            List<Map<String, Object>> out = new ArrayList<>();
            store.users.stream()
                .filter(u -> includeInactive || u.active)
                .sorted(Comparator.comparing(u -> u.displayName.toLowerCase()))
                .forEach(u -> out.add(u.toPublic()));
            return out;
        }
    }

    public User createUser(User actor, Map<String, Object> body) {
        requireAdmin(actor);
        String username = Json.str(body, "username", "").trim().toLowerCase();
        if (!USERNAME.matcher(username).matches()) {
            throw ServiceException.bad("Username must be 3-24 characters: small letters, numbers, dot, dash or underscore, starting with a letter");
        }
        String password = Json.str(body, "password", "");
        checkPassword(password);
        synchronized (store) {
            if (store.user(username) != null) throw ServiceException.conflict("That username is already taken");
            User u = new User();
            u.username = username;
            u.passwordHash = Passwords.hash(password);
            u.createdAt = Instant.now().toString();
            applyUserFields(u, body);
            if (u.displayName == null || u.displayName.isBlank()) u.displayName = username;
            store.users.add(u);
            store.save();
            return u;
        }
    }

    public User updateUser(User actor, String username, Map<String, Object> body) {
        requireAdmin(actor);
        synchronized (store) {
            User u = store.user(username);
            if (u == null) throw ServiceException.notFound("User");
            boolean wasAdmin = u.isAdmin() && u.active;
            applyUserFields(u, body);
            if (body.containsKey("active")) u.active = Json.bool(body, "active", true);
            if (wasAdmin && (!u.isAdmin() || !u.active) && activeAdmins() == 0) {
                u.role = Role.ADMIN; u.active = true;
                throw ServiceException.bad("There must always be at least one active admin");
            }
            String pw = Json.str(body, "password");
            if (pw != null && !pw.isBlank()) {
                checkPassword(pw);
                u.passwordHash = Passwords.hash(pw);
            }
            store.save();
            if (!u.active || (pw != null && !pw.isBlank())) {
                sessions.entrySet().removeIf(e -> e.getValue().username().equals(u.username));
            }
            return u;
        }
    }

    private long activeAdmins() {
        return store.users.stream().filter(x -> x.active && x.isAdmin()).count();
    }

    private void applyUserFields(User u, Map<String, Object> body) {
        if (body.containsKey("displayName")) {
            String dn = Json.str(body, "displayName", "").trim();
            if (dn.length() > 60) throw ServiceException.bad("Name is too long");
            if (!dn.isEmpty()) u.displayName = dn;
        }
        if (body.containsKey("email")) {
            String em = Json.str(body, "email", "").trim();
            if (!em.isEmpty() && !EMAIL.matcher(em).matches()) throw ServiceException.bad("That email address does not look right");
            u.email = em;
        }
        if (body.containsKey("role")) u.role = Enums.parse(Role.class, Json.str(body, "role"), u.role);
        if (body.containsKey("monthlyTarget")) {
            long t = Json.lng(body, "monthlyTarget", 0);
            if (t < 0) throw ServiceException.bad("Target cannot be negative");
            u.monthlyTarget = t;
        }
    }

    private static void checkPassword(String pw) {
        if (pw == null || pw.length() < 8) throw ServiceException.bad("Password must be at least 8 characters");
        if (pw.length() > 128) throw ServiceException.bad("Password is too long");
    }

    public static void requireAdmin(User u) {
        if (u == null || !u.isAdmin()) throw ServiceException.forbidden("Only an admin can do this");
    }
}
