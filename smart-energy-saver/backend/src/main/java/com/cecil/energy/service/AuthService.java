package com.cecil.energy.service;

import com.cecil.energy.model.Enums;
import com.cecil.energy.model.Enums.Role;
import com.cecil.energy.model.User;
import com.cecil.energy.store.DataStore;
import com.cecil.energy.util.Json;
import com.cecil.energy.util.Passwords;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Sign in / sign out, sessions, and managing people (admin only).
 *
 * Sessions are random 256-bit tokens kept in memory. They expire after 12 hours of no use.
 * 5 wrong passwords lock the username for 5 minutes (stops password guessing).
 */
public class AuthService {

    static final Duration SESSION_IDLE = Duration.ofHours(12);
    static final int MAX_FAILS = 5;
    static final Duration LOCK_TIME = Duration.ofMinutes(5);
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9._-]{3,30}");

    private final DataStore store;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, int[]> fails = new ConcurrentHashMap<>();
    private final Map<String, Instant> lockedUntil = new ConcurrentHashMap<>();

    /** A signed-in session. */
    public static class Session {
        public final String token;
        public final String username;
        volatile Instant lastUsed;

        Session(String token, String username) {
            this.token = token;
            this.username = username;
            this.lastUsed = Instant.now();
        }
    }

    public AuthService(DataStore store) {
        this.store = store;
    }

    /** Checks the password and returns {token, user}. */
    public Map<String, Object> login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            throw ServiceException.bad("Enter your username and password.");
        }
        String key = username.trim().toLowerCase();
        Instant until = lockedUntil.get(key);
        if (until != null && until.isAfter(Instant.now())) {
            long mins = Math.max(1, Duration.between(Instant.now(), until).toMinutes() + 1);
            throw ServiceException.tooMany("Too many wrong tries. Try again in " + mins + " minute(s).");
        }
        User u;
        synchronized (store.lock) {
            u = store.user(key);
        }
        // verify even for unknown users, so the response time does not reveal which usernames exist
        boolean ok = Passwords.verify(password, u != null ? u.passwordHash : "pbkdf2$1000$AAAAAAAAAAAAAAAAAAAAAA==$AAAA");
        if (u == null || !ok || !u.active) {
            int[] f = fails.computeIfAbsent(key, k -> new int[1]);
            synchronized (f) {
                f[0]++;
                if (f[0] >= MAX_FAILS) {
                    lockedUntil.put(key, Instant.now().plus(LOCK_TIME));
                    f[0] = 0;
                }
            }
            if (u != null && ok && !u.active) throw ServiceException.unauthorized("This account is turned off. Ask the admin.");
            throw ServiceException.unauthorized("Wrong username or password.");
        }
        fails.remove(key);
        lockedUntil.remove(key);
        Session s = new Session(Passwords.randomToken(), u.username);
        sessions.put(s.token, s);
        return Map.of("token", s.token, "user", u.toPublic());
    }

    public void logout(String token) {
        if (token != null) sessions.remove(token);
    }

    /** Finds the signed-in user for a token, or throws 401. */
    public User require(String token) {
        if (token == null || token.isEmpty()) throw ServiceException.unauthorized("Please sign in.");
        Session s = sessions.get(token);
        if (s == null) throw ServiceException.unauthorized("Please sign in.");
        if (s.lastUsed.plus(SESSION_IDLE).isBefore(Instant.now())) {
            sessions.remove(token);
            throw ServiceException.unauthorized("Your session ended. Please sign in again.");
        }
        User u;
        synchronized (store.lock) {
            u = store.user(s.username);
        }
        if (u == null || !u.active) {
            sessions.remove(token);
            throw ServiceException.unauthorized("Please sign in.");
        }
        s.lastUsed = Instant.now();
        return u;
    }

    /** Removes expired sessions (called now and then by the server). */
    public void cleanup() {
        Instant cut = Instant.now().minus(SESSION_IDLE);
        for (Iterator<Session> it = sessions.values().iterator(); it.hasNext(); ) {
            if (it.next().lastUsed.isBefore(cut)) it.remove();
        }
    }

    public void changePassword(User me, String current, String next, String keepToken) {
        if (!Passwords.verify(current, me.passwordHash)) throw ServiceException.bad("Your current password is wrong.");
        checkPassword(next);
        synchronized (store.lock) {
            me.passwordHash = Passwords.hash(next);
            store.saveUsers();
        }
        // sign out every other device
        sessions.values().removeIf(s -> s.username.equals(me.username) && !s.token.equals(keepToken));
    }

    static void checkPassword(String p) {
        if (p == null || p.length() < 8) throw ServiceException.bad("Password must be at least 8 characters.");
        if (p.length() > 100) throw ServiceException.bad("Password is too long.");
    }

    // ------------------------------------------------------------------ team (admin)

    public List<Map<String, Object>> listUsers() {
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (store.lock) {
            for (User u : store.users) out.add(u.toPublic());
        }
        return out;
    }

    public Map<String, Object> createUser(User admin, Map<String, Object> body) {
        requireAdmin(admin);
        String username = Json.str(body, "username", "").trim().toLowerCase();
        if (!USERNAME.matcher(username).matches()) {
            throw ServiceException.bad("Username: 3-30 small letters, numbers, dot, dash or underscore.");
        }
        String name = clean(Json.str(body, "displayName", ""), 60);
        if (name.isEmpty()) throw ServiceException.bad("Enter the person's name.");
        String password = Json.str(body, "password", "");
        checkPassword(password);
        synchronized (store.lock) {
            if (store.user(username) != null) throw ServiceException.conflict("That username is already taken.");
            User u = new User();
            u.username = username;
            u.displayName = name;
            u.role = Enums.parse(Role.class, Json.str(body, "role"), Role.MEMBER);
            u.passwordHash = Passwords.hash(password);
            u.active = true;
            u.createdAt = store.nowText();
            store.users.add(u);
            store.saveUsers();
            return u.toPublic();
        }
    }

    public Map<String, Object> updateUser(User admin, String username, Map<String, Object> body) {
        requireAdmin(admin);
        synchronized (store.lock) {
            User u = store.user(username);
            if (u == null) throw ServiceException.notFound("No such person.");
            if (body.containsKey("displayName")) {
                String n = clean(Json.str(body, "displayName", ""), 60);
                if (n.isEmpty()) throw ServiceException.bad("Name cannot be empty.");
                u.displayName = n;
            }
            if (body.containsKey("role")) {
                Role r = Enums.parse(Role.class, Json.str(body, "role"), u.role);
                if (u.role == Role.ADMIN && r != Role.ADMIN && countActiveAdmins() <= 1) {
                    throw ServiceException.bad("There must be at least one admin.");
                }
                u.role = r;
            }
            if (body.containsKey("active")) {
                boolean a = Json.bool(body, "active", true);
                if (!a && u.username.equals(admin.username)) throw ServiceException.bad("You cannot turn off your own account.");
                if (!a && u.role == Role.ADMIN && countActiveAdmins() <= 1) throw ServiceException.bad("There must be at least one admin.");
                u.active = a;
            }
            String pw = Json.str(body, "password", "");
            if (!pw.isEmpty()) {
                checkPassword(pw);
                u.passwordHash = Passwords.hash(pw);
                sessions.values().removeIf(s -> s.username.equals(u.username));
            }
            if (!u.active) sessions.values().removeIf(s -> s.username.equals(u.username));
            store.saveUsers();
            return u.toPublic();
        }
    }

    private int countActiveAdmins() {
        int n = 0;
        for (User u : store.users) if (u.role == Role.ADMIN && u.active) n++;
        return n;
    }

    static void requireAdmin(User u) {
        if (u.role != Role.ADMIN) throw ServiceException.forbidden("Only the admin can do this.");
    }

    static void requireManager(User u) {
        if (!u.role.canManage()) throw ServiceException.forbidden("Only an energy manager or admin can do this.");
    }

    /** Trims text, removes control characters and limits the length. */
    public static String clean(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("[\\p{Cntrl}&&[^\n]]", "").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
