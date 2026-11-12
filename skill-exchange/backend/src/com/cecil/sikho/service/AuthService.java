package com.cecil.sikho.service;

import com.cecil.sikho.ledger.TimeBank;
import com.cecil.sikho.model.Locality;
import com.cecil.sikho.model.Role;
import com.cecil.sikho.model.User;
import com.cecil.sikho.store.DataStore;
import com.cecil.sikho.util.Clock;
import com.cecil.sikho.util.Json;
import com.cecil.sikho.util.Passwords;
import com.cecil.sikho.util.Text;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Joining, signing in and out, sessions, and each member's own profile.
 *  - 5 wrong passwords in a row lock the account for 5 minutes.
 *  - The same message is shown for "no such user" and "wrong password" (so nobody can find out usernames).
 *  - A session ends after 12 hours without use. Only the SHA-256 hash of the session token is kept.
 */
public class AuthService {
    public static final int MAX_FAILS = 5;
    public static final long LOCK_MS = 5 * 60_000L;
    public static final long IDLE_MS = 12 * 3_600_000L;
    public static final String BAD_LOGIN = "Wrong username or password.";
    public static final Set<String> DAYS = Set.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
    public static final Set<String> PARTS = Set.of("MORN", "AFT", "EVE");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DataStore store;
    private final TimeBank bank;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();   // key = SHA-256 of the token

    public static final class Session {
        public final String userId;
        public volatile long lastSeen;
        Session(String userId, long lastSeen) { this.userId = userId; this.lastSeen = lastSeen; }
    }

    public AuthService(DataStore store, TimeBank bank) {
        this.store = store;
        this.bank = bank;
    }

    /** Returns a new session token, or throws Problem(401) with a friendly message. */
    public String login(String username, String password) {
        synchronized (store) {
            User u = store.userByName(username);
            long now = Clock.millis();
            if (u == null || !u.active) {
                store.log(null, "login.fail", Text.clean(username, 40), "unknown or closed account");
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
            if (u.suspended) {
                throw new Problem(403, "Your account is paused by a moderator"
                        + (u.suspendReason == null ? "." : ": " + u.suspendReason) + " Write to the moderators to talk about it.");
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

    /** The signed-in member for this token, or null (unknown, expired, closed or paused account). */
    public User userFor(String token) {
        if (token == null || token.isBlank()) return null;
        String key = Passwords.sha256(token);
        Session s = sessions.get(key);
        if (s == null) return null;
        long now = Clock.millis();
        if (now - s.lastSeen > IDLE_MS) { sessions.remove(key); return null; }
        User u;
        synchronized (store) { u = store.users.get(s.userId); }
        if (u == null || !u.active || u.suspended) { sessions.remove(key); return null; }
        s.lastSeen = now;
        return u;
    }

    public void logout(String token) {
        if (token != null) sessions.remove(Passwords.sha256(token));
    }

    /** Ends every session of one member (after a password change or a suspension). */
    public void endSessionsOf(String userId) {
        sessions.values().removeIf(s -> s.userId.equals(userId));
    }

    public int sessionCount() { return sessions.size(); }

    // ------------------------------------------------------------------ joining

    /** A new neighbour joins: checks the details, places them in their locality and gives 3 welcome credits. */
    public User register(Map<String, Object> body) {
        String username = Text.clean(Json.str(body, "username"), 30);
        String name = Text.clean(Json.str(body, "name"), 60);
        String password = Json.str(body, "password");
        String localityId = Json.str(body, "localityId");
        if (username == null || !username.matches("[a-zA-Z0-9._-]{3,30}"))
            throw Problem.bad("Username: 3-30 letters, digits, dot, dash or underscore.");
        if (name == null || name.length() < 2) throw Problem.bad("Please write your name.");
        String pw = Passwords.problem(password);
        if (pw != null) throw Problem.bad(pw);
        if (!Json.bool(body, "agree", false)) throw Problem.bad("Please agree to the community rules.");
        String phone = phone(Json.str(body, "phone"));
        synchronized (store) {
            Locality loc = localityId == null ? null : store.localities.get(localityId);
            if (loc == null) throw Problem.bad("Choose your locality.");
            if (store.userByName(username) != null) throw Problem.conflict("That username is already taken.");
            User u = new User();
            u.id = store.nextId("U", 101);
            u.username = username.toLowerCase();
            u.name = name;
            u.role = Role.MEMBER;
            u.passwordHash = Passwords.hash(password);
            u.localityId = loc.id;
            double[] p = near(loc);
            u.lat = p[0];
            u.lng = p[1];
            u.phone = phone;
            u.bio = Text.clean(Json.str(body, "bio"), 300);
            u.availability = new ArrayList<>(List.of("SAT-MORN", "SUN-MORN", "SAT-EVE"));
            u.createdAt = Clock.millis();
            store.users.put(u.id, u);
            bank.post(TimeBank.BANK, u.id, TimeBank.WELCOME, "WELCOME", null, "Welcome to Sikho-Sikhao");
            store.log(u.id, "member.join", u.id, u.username + " in " + loc.name);
            store.notify(u.id, "Welcome, " + u.firstName() + "! You have " + TimeBank.WELCOME
                    + " time credits to start learning. Add what you can teach so neighbours can find you.", "listings");
            store.save("users", "ledger", "notes", "audit");
            return u;
        }
    }

    /** A home position inside the locality: up to about 800 m from its centre (people do not live at one point). */
    public static double[] near(Locality loc) {
        double angle = RANDOM.nextDouble() * 2 * Math.PI;
        double km = 0.15 + RANDOM.nextDouble() * 0.65;
        return new double[] { loc.lat + km / 111.0 * Math.cos(angle),
                loc.lng + km / (111.0 * Math.cos(Math.toRadians(loc.lat))) * Math.sin(angle) };
    }

    /** An Indian mobile number (10 digits starting 6-9), or null if empty. */
    static String phone(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String d = raw.replaceAll("[\\s-]", "");
        if (d.startsWith("+91")) d = d.substring(3);
        if (!d.matches("[6-9]\\d{9}")) throw Problem.bad("Phone: a 10-digit mobile number, or leave it empty.");
        return d;
    }

    // ------------------------------------------------------------------ own profile

    public User updateProfile(User me, Map<String, Object> body) {
        synchronized (store) {
            List<String> changes = new ArrayList<>();
            if (body.containsKey("name")) {
                String n = Text.clean(Json.str(body, "name"), 60);
                if (n == null || n.length() < 2) throw Problem.bad("Please write your name.");
                me.name = n;
                changes.add("name");
            }
            if (body.containsKey("bio")) { me.bio = Text.clean(Json.str(body, "bio"), 300); changes.add("about"); }
            if (body.containsKey("languages")) { me.languages = Text.clean(Json.str(body, "languages"), 80); changes.add("languages"); }
            if (body.containsKey("phone")) { me.phone = phone(Json.str(body, "phone")); changes.add("phone"); }
            if (body.containsKey("maxKm")) {
                long km = Json.num(body, "maxKm", me.maxKm);
                if (km < 1 || km > 25) throw Problem.bad("Travel distance: 1 to 25 km.");
                me.maxKm = (int) km;
                changes.add("travel " + km + " km");
            }
            if (body.containsKey("localityId")) {
                Locality loc = store.localities.get(Json.str(body, "localityId"));
                if (loc == null) throw Problem.bad("Unknown locality.");
                if (!loc.id.equals(me.localityId)) {
                    me.localityId = loc.id;
                    double[] p = near(loc);
                    me.lat = p[0];
                    me.lng = p[1];
                    changes.add("moved to " + loc.name);
                }
            }
            if (body.containsKey("availability")) {
                List<String> slots = new ArrayList<>();
                for (String s : Json.strings(body, "availability")) {
                    String[] p = s.split("-");
                    if (p.length != 2 || !DAYS.contains(p[0]) || !PARTS.contains(p[1])) throw Problem.bad("Unknown time slot " + s + ".");
                    if (!slots.contains(s)) slots.add(s);
                }
                me.availability = slots;
                changes.add("free time (" + slots.size() + " slots)");
            }
            if (changes.isEmpty()) throw Problem.bad("Nothing to change.");
            store.log(me.id, "profile.update", me.id, String.join(", ", changes));
            store.save("users", "audit");
            return me;
        }
    }

    /** Block or unblock another member: blocked people do not see your cards and cannot send you requests. */
    public User block(User me, String otherId, boolean block) {
        synchronized (store) {
            User other = store.users.get(otherId);
            if (other == null) throw Problem.notFound("Member");
            if (other.id.equals(me.id)) throw Problem.bad("You cannot block yourself.");
            if (block && !me.blocked.contains(other.id)) me.blocked.add(other.id);
            if (!block) me.blocked.remove(other.id);
            store.log(me.id, block ? "member.block" : "member.unblock", other.id, null);
            store.save("users", "audit");
            return me;
        }
    }

    public void changeOwnPassword(User me, String current, String next) {
        synchronized (store) {
            if (!Passwords.verify(current, me.passwordHash)) throw Problem.bad("Your current password is not right.");
            String p = Passwords.problem(next);
            if (p != null) throw Problem.bad(p);
            me.passwordHash = Passwords.hash(next);
            store.log(me.id, "member.password", me.id, "changed own password");
            store.save("users", "audit");
        }
    }
}
