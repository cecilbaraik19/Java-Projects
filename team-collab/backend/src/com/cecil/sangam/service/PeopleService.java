package com.cecil.sangam.service;

import com.cecil.sangam.core.EventBus;
import com.cecil.sangam.core.WorkHours;
import com.cecil.sangam.model.Role;
import com.cecil.sangam.model.User;
import com.cecil.sangam.store.DataStore;
import com.cecil.sangam.util.Clock;
import com.cecil.sangam.util.Json;
import com.cecil.sangam.util.Passwords;
import com.cecil.sangam.util.Text;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * People: their time zone and hours, presence (online / away / offline, focus time) and – for the admin –
 * adding a new teammate.
 */
public class PeopleService {
    public static final long ONLINE_MS = 90_000;
    public static final long AWAY_MS = 15 * 60_000;
    /** Time zones offered in the settings page (any valid IANA zone is accepted). */
    public static final List<String> ZONES = List.of("Asia/Kolkata", "Asia/Singapore", "Asia/Tokyo", "Australia/Sydney",
            "Asia/Dubai", "Africa/Nairobi", "Africa/Accra", "Africa/Lagos", "Europe/Berlin", "Europe/Lisbon", "Europe/London",
            "America/Sao_Paulo", "America/New_York", "America/Toronto", "America/Chicago", "America/Los_Angeles", "UTC");

    private final DataStore store;
    private final EventBus events;

    public PeopleService(DataStore store, EventBus events) {
        this.store = store;
        this.events = events;
    }

    /** What anyone may see about a person, plus their clock right now. */
    public static Map<String, Object> card(User u, long now) {
        return Json.obj("id", u.id, "username", u.username, "name", u.name, "title", u.title, "city", u.city,
                "zone", u.zone, "role", u.role, "statusText", u.statusText,
                "hours", hm(u.workStart) + "–" + hm(u.workEnd), "workStart", u.workStart, "workEnd", u.workEnd,
                "workDays", u.workDays, "presence", presence(u, now), "focusUntil", u.focusUntil > now ? u.focusUntil : 0,
                "clock", WorkHours.clock(u, now), "initials", initials(u.name));
    }

    /** online (seen in the last 90 s) / away (15 min) / offline – and "focus" wins while focus time is on. */
    public static String presence(User u, long now) {
        if (u.focusUntil > now && now - u.lastSeen < AWAY_MS) return "focus";
        if (now - u.lastSeen < ONLINE_MS) return "online";
        if (now - u.lastSeen < AWAY_MS) return "away";
        return "offline";
    }

    public static String hm(int minutes) { return String.format("%02d:%02d", minutes / 60, minutes % 60); }

    static String initials(String name) {
        if (name == null || name.isBlank()) return "?";
        String[] p = name.trim().split("\\s+");
        return (p[0].substring(0, 1) + (p.length > 1 ? p[p.length - 1].substring(0, 1) : "")).toUpperCase();
    }

    /** Change my own zone, hours, days, title, city or status. */
    public User updateMe(User u, Map<String, Object> b) {
        synchronized (store) {
            String zone = b.containsKey("zone") ? Json.str(b, "zone") : u.zone;
            try { ZoneId.of(zone); } catch (Exception e) { throw Problem.bad("Unknown time zone. Use a name like Europe/Berlin."); }
            int start = b.containsKey("workStart") ? WorkHours.parseHm(Json.str(b, "workStart")) : u.workStart;
            int end = b.containsKey("workEnd") ? WorkHours.parseHm(Json.str(b, "workEnd")) : u.workEnd;
            if (start < 0 || end < 0) throw Problem.bad("Times look like 09:00.");
            if (end <= start) throw Problem.bad("Your working day must end after it starts (on the same day).");
            if (end - start < 2 * 60 || end - start > 12 * 60) throw Problem.bad("Working hours: between 2 and 12 hours a day.");
            if (start % 15 != 0 || end % 15 != 0) throw Problem.bad("Use quarter hours (09:00, 09:15 ...).");
            List<Integer> days = u.workDays;
            if (b.containsKey("workDays")) {
                days = new ArrayList<>();
                for (Object o : Json.list(b, "workDays")) {
                    long d = o instanceof Number n ? n.longValue() : -1;
                    if (d < 1 || d > 7) throw Problem.bad("Days are 1 (Monday) to 7 (Sunday).");
                    if (!days.contains((int) d)) days.add((int) d);
                }
                if (days.isEmpty()) throw Problem.bad("Pick at least one working day.");
                days.sort(Integer::compare);
            }
            u.zone = zone;
            u.workStart = start;
            u.workEnd = end;
            u.workDays = days;
            if (b.containsKey("title")) u.title = Text.clean(Json.str(b, "title", ""), 60);
            if (b.containsKey("city")) u.city = Text.clean(Json.str(b, "city", ""), 40);
            if (b.containsKey("statusText")) u.statusText = Text.clean(Json.str(b, "statusText", ""), 80);
            store.log(u.id, "me.update", u.username, u.zone + " " + hm(start) + "–" + hm(end));
            store.save("users", "audit");
            events.publish("presence", null, null, null, Json.obj("userId", u.id));
            return u;
        }
    }

    /** Focus time: notifications wait (minutes = 0 ends it). */
    public User focus(User u, int minutes) {
        if (minutes < 0 || minutes > 240) throw Problem.bad("Focus time: up to 4 hours.");
        synchronized (store) {
            long now = Clock.millis();
            u.focusUntil = minutes == 0 ? 0 : now + minutes * WorkHours.MIN;
            store.save("users");
            if (minutes == 0) {
                // the waiting notifications arrive now (if it is working time)
                long at = DataStore.deliverAt(u, now);
                for (var n : store.notes.values()) if (n.userId.equals(u.id) && n.deliverAt > now && !n.urgent) n.deliverAt = Math.max(at, now);
                store.save("notes");
            }
        }
        events.publish("presence", null, null, null, Json.obj("userId", u.id));
        return u;
    }

    /** Admin: add a teammate (they change the password after signing in). */
    public User add(User admin, Map<String, Object> b) {
        Access.admin(admin);
        synchronized (store) {
            String username = Text.clean(Json.str(b, "username"), 30);
            if (username == null || !username.matches("[a-z][a-z0-9._-]{2,29}")) throw Problem.bad("Usernames: 3 to 30 small letters, digits, dot, dash.");
            if (store.userByName(username) != null || username.equals("here") || username.equals("team")) throw Problem.conflict("That username is taken.");
            String name = Text.clean(Json.str(b, "name"), 60);
            if (Text.blank(name) || name.length() < 2) throw Problem.bad("Write the person's name.");
            String pw = Json.str(b, "password");
            String bad = Passwords.problem(pw);
            if (bad != null) throw Problem.bad(bad);
            Role role;
            try { role = Role.valueOf(Json.str(b, "role", "MEMBER")); } catch (IllegalArgumentException e) { throw Problem.bad("Unknown role."); }
            User u = new User();
            u.id = store.nextId("U", 1);
            u.username = username;
            u.name = name;
            u.role = role;
            u.passwordHash = Passwords.hash(pw);
            u.createdAt = Clock.millis();
            u.title = "";
            u.city = "";
            store.users.put(u.id, u);
            try {
                updateMe(u, b);   // same checks for zone and hours
            } catch (Problem e) {
                store.users.remove(u.id);
                throw e;
            }
            store.log(admin.id, "person.add", username, role.name());
            store.save("users", "audit");
            return u;
        }
    }
}
