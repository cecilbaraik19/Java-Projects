package com.cecil.dainandini.service;

import com.cecil.dainandini.ai.ClaudeClient;
import com.cecil.dainandini.model.AuditEntry;
import com.cecil.dainandini.model.Role;
import com.cecil.dainandini.model.User;
import com.cecil.dainandini.store.DataStore;
import com.cecil.dainandini.util.Clock;
import com.cecil.dainandini.util.Json;
import com.cecil.dainandini.util.Passwords;
import com.cecil.dainandini.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** People, my own settings, app settings and the activity log. */
public class AdminService {
    private final DataStore store;
    private final ClaudeClient claude;

    public AdminService(DataStore store, ClaudeClient claude) { this.store = store; this.claude = claude; }

    static void needAdmin(User u) { if (!u.admin()) throw Problem.forbidden("Only the admin can do this."); }

    /** Everybody (name, team, role) – used for invites and handing over tasks. */
    public List<Object> people() {
        synchronized (store) {
            List<Object> out = new ArrayList<>();
            for (User u : store.users.values()) if (u.active) out.add(u.card());
            return out;
        }
    }

    /** My working hours, lunch and focus goal. */
    public User updateMe(User u, Map<String, Object> b) {
        synchronized (store) {
            int ws = b.containsKey("workStart") ? TaskService.minutes(Json.str(b, "workStart")) : u.workStart;
            int we = b.containsKey("workEnd") ? TaskService.minutes(Json.str(b, "workEnd")) : u.workEnd;
            if (we - ws < 120) throw Problem.bad("The working day must be at least 2 hours long.");
            int ls = b.containsKey("lunchStart") ? TaskService.minutes(Json.str(b, "lunchStart")) : u.lunchStart;
            int lm = (int) Json.num(b, "lunchMin", u.lunchMin);
            if (lm < 0 || lm > 120) throw Problem.bad("Lunch can be 0 to 120 minutes.");
            int goal = (int) Json.num(b, "focusGoalMin", u.focusGoalMin);
            if (goal < 30 || goal > 480) throw Problem.bad("The focus goal can be 30 to 480 minutes a day.");
            List<Integer> days = new ArrayList<>();
            if (b.containsKey("workDays")) {
                for (Object o : Json.list(b, "workDays")) if (o instanceof Number n && n.intValue() >= 1 && n.intValue() <= 7 && !days.contains(n.intValue())) days.add(n.intValue());
                if (days.isEmpty()) throw Problem.bad("Pick at least one working day.");
                days.sort(Integer::compare);
                u.workDays = days;
            }
            u.workStart = ws; u.workEnd = we; u.lunchStart = ls; u.lunchMin = lm; u.focusGoalMin = goal;
            store.log(u.id, "settings.me", u.username, null);
            store.save("users", "audit");
            return u;
        }
    }

    public User addPerson(User admin, Map<String, Object> b) {
        needAdmin(admin);
        synchronized (store) {
            String username = Json.str(b, "username", "").trim().toLowerCase();
            if (!username.matches("[a-z][a-z0-9._]{2,23}")) throw Problem.bad("Username: 3–24 small letters, digits, dot or underscore; starts with a letter.");
            if (store.userByName(username) != null) throw Problem.conflict("That username is taken.");
            String name = Text.clean(Json.str(b, "name"), 60);
            if (Text.blank(name)) throw Problem.bad("Please type the person's name.");
            String pw = Json.str(b, "password");
            String problem = Passwords.problem(pw);
            if (problem != null) throw Problem.bad(problem);
            User u = new User();
            u.id = store.nextId("U", 1);
            u.username = username;
            u.name = name;
            u.title = Text.clean(Json.str(b, "title", ""), 60);
            u.team = Text.clean(Json.str(b, "team", ""), 40);
            u.role = TaskService.enumOf(Role.class, Json.str(b, "role", "MEMBER"), "role");
            u.passwordHash = Passwords.hash(pw);
            u.createdAt = Clock.millis();
            store.users.put(u.id, u);
            store.log(admin.id, "person.add", u.username, u.role.name());
            store.save("users", "audit");
            return u;
        }
    }

    public User updatePerson(User admin, String id, Map<String, Object> b) {
        needAdmin(admin);
        synchronized (store) {
            User u = store.users.get(id);
            if (u == null) throw Problem.notFound("Person");
            if (b.containsKey("role")) {
                Role r = TaskService.enumOf(Role.class, Json.str(b, "role"), "role");
                if (u.id.equals(admin.id) && r != Role.ADMIN) throw Problem.bad("You cannot take away your own admin role.");
                u.role = r;
            }
            if (b.containsKey("team")) u.team = Text.clean(Json.str(b, "team", ""), 40);
            if (b.containsKey("title")) u.title = Text.clean(Json.str(b, "title", ""), 60);
            if (b.containsKey("active")) {
                boolean a = Json.bool(b, "active", true);
                if (u.id.equals(admin.id) && !a) throw Problem.bad("You cannot switch off your own account.");
                u.active = a;
            }
            store.log(admin.id, "person.edit", u.username, u.role + (u.active ? "" : ", switched off"));
            store.save("users", "audit");
            return u;
        }
    }

    public Map<String, Object> settings(User admin) {
        needAdmin(admin);
        return Json.obj("claude", store.setting("claude", "OFF"), "claudeKey", claude != null, "claudeModel", claude == null ? null : claude.model());
    }

    public Map<String, Object> saveSettings(User admin, Map<String, Object> b) {
        needAdmin(admin);
        String mode = Json.str(b, "claude", "OFF");
        if (!mode.equals("OFF") && !mode.equals("WHEN_UNSURE")) throw Problem.bad("Unknown Claude setting.");
        if (mode.equals("WHEN_UNSURE") && claude == null) throw Problem.bad("Set the ANTHROPIC_API_KEY environment variable and restart the app first.");
        synchronized (store) {
            store.setSetting("claude", mode);
            store.log(admin.id, "settings.app", "claude", mode);
            store.save("audit");
        }
        return settings(admin);
    }

    public List<Object> audit(User admin, int max) {
        needAdmin(admin);
        synchronized (store) {
            List<Object> out = new ArrayList<>();
            for (int i = store.audit.size() - 1; i >= 0 && out.size() < max; i--) {
                AuditEntry a = store.audit.get(i);
                Map<String, Object> j = a.toJson();
                j.put("who", a.userId() == null ? "—" : store.userName(a.userId()));
                out.add(j);
            }
            return out;
        }
    }
}
