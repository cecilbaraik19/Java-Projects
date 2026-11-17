package com.cecil.kaushal.service;

import com.cecil.kaushal.core.Levels;
import com.cecil.kaushal.model.Claim;
import com.cecil.kaushal.model.ClaimStatus;
import com.cecil.kaushal.model.Role;
import com.cecil.kaushal.model.Skill;
import com.cecil.kaushal.model.Snapshot;
import com.cecil.kaushal.model.User;
import com.cecil.kaushal.store.DataStore;
import com.cecil.kaushal.util.Clock;
import com.cecil.kaushal.util.Csv;
import com.cecil.kaushal.util.Json;
import com.cecil.kaushal.util.Passwords;
import com.cecil.kaushal.util.Text;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * HR tools: people (add, change role or manager - no loops in the reporting line), CSV import of skills from an old
 * spreadsheet (with a dry run first), CSV export of the skill matrix, and the monthly snapshot for the trend chart.
 */
public class AdminService {
    public static final int MAX_IMPORT_LINES = 2000;
    private final DataStore store;
    private final ProfileService profiles;

    public AdminService(DataStore store, ProfileService profiles) {
        this.store = store;
        this.profiles = profiles;
    }

    public User saveUser(User by, String id, Map<String, Object> b) {
        Access.admin(by);
        synchronized (store) {
            User u = id == null ? new User() : store.users.get(id);
            if (u == null) throw Problem.notFound("Person");
            String username = id == null ? Text.clean(Json.str(b, "username"), 30) : u.username;
            if (id == null) {
                if (username == null || !username.matches("[a-z][a-z0-9._-]{2,29}")) throw Problem.bad("Username: 3-30 small letters or digits, starting with a letter.");
                if (store.userByName(username) != null) throw Problem.conflict("That username is taken.");
            }
            String name = Text.clean(Json.str(b, "name", u.name), 60);
            if (Text.blank(name) || name.length() < 3) throw Problem.bad("Write the person's full name.");
            String managerId = b.containsKey("managerId") ? Json.str(b, "managerId") : u.managerId;
            if (Text.blank(managerId)) managerId = null;
            if (managerId != null) {
                User m = store.users.get(managerId);
                if (m == null) throw Problem.bad("Unknown manager.");
                if (id != null && (m.id.equals(u.id) || store.manages(u, m)))
                    throw Problem.conflict(m.name + " reports to " + name + " - that would be a loop in the reporting line.");
            }
            String jobRoleId = b.containsKey("jobRoleId") ? Json.str(b, "jobRoleId") : u.jobRoleId;
            if (Text.blank(jobRoleId)) jobRoleId = null;
            if (jobRoleId != null && !store.roles.containsKey(jobRoleId)) throw Problem.bad("Unknown job role.");
            Role role = b.containsKey("role") ? Role.of(Json.str(b, "role")) : u.role;
            if (u.id != null && u.id.equals(by.id) && role != Role.ADMIN) throw Problem.bad("You cannot take away your own HR rights.");
            String pw = Json.str(b, "password");
            if (id == null || !Text.blank(pw)) {
                String pr = Passwords.problem(pw);
                if (pr != null) throw Problem.bad(pr);
                u.passwordHash = Passwords.hash(pw);
            }
            boolean active = Json.bool(b, "active", u.active || id == null);
            if (id != null && !active && u.id.equals(by.id)) throw Problem.bad("You cannot switch off your own account.");
            if (id == null) {
                u.id = store.nextId("U", 1);
                u.username = username;
                u.joined = Clock.today().toString();
                store.users.put(u.id, u);
            }
            u.name = name;
            u.title = Text.clean(Json.str(b, "title", u.title), 60);
            u.dept = Text.clean(Json.str(b, "dept", u.dept), 40);
            u.location = Text.clean(Json.str(b, "location", u.location), 40);
            u.role = role;
            u.managerId = managerId;
            u.jobRoleId = jobRoleId;
            u.active = active;
            store.log(by.id, id == null ? "user.add" : "user.edit", u.id, name + " " + role + (active ? "" : " (off)"));
            store.save("users", "audit");
            return u;
        }
    }

    /**
     * Imports skills from CSV:  username, skill, level [, last used yyyy-mm] [, verified yes/no]
     * The skill can be its name, symbol or another name ("JS"). Dry run = check only, change nothing.
     * Lines marked verified count as checked by HR ("from the old HR sheet"); the others wait for a check.
     */
    public Map<String, Object> importCsv(User by, String csv, boolean dryRun) {
        Access.admin(by);
        synchronized (store) {
            List<List<String>> rows = Csv.parse(csv == null ? "" : csv);
            if (rows.isEmpty()) throw Problem.bad("The file is empty.");
            if (rows.size() > MAX_IMPORT_LINES + 1) throw Problem.bad("At most " + MAX_IMPORT_LINES + " lines at a time.");
            int start = 0;
            if (!rows.get(0).isEmpty() && Text.key(rows.get(0).get(0)).equals("username")) start = 1;
            List<Map<String, Object>> lines = new ArrayList<>();
            int ok = 0, bad = 0, verified = 0;
            boolean before = store.batch;
            for (int i = start; i < rows.size(); i++) {
                List<String> r = rows.get(i);
                String err = null;
                User u = store.userByName(cell(r, 0));
                Skill s = store.skillByName(cell(r, 1));
                int level = Levels.parse(cell(r, 2));
                String used = cell(r, 3);
                boolean ver = List.of("yes", "y", "true", "1", "verified").contains(Text.key(cell(r, 4)));
                if (u == null) err = "Unknown username '" + Text.clean(cell(r, 0), 30) + "'";
                else if (s == null) err = "Unknown skill '" + Text.clean(cell(r, 1), 40) + "'";
                else if (level < 1) err = "Level must be 1-5 or Aware ... Expert";
                else {
                    try { used = ProfileService.month(used); } catch (Problem p) { err = p.getMessage(); }
                }
                if (err == null && ver && u.id.equals(by.id)) err = "You cannot verify your own skills";
                if (err != null) {
                    bad++;
                    lines.add(Json.obj("line", i + 1, "ok", false, "message", err));
                    continue;
                }
                ok++;
                if (ver) verified++;
                Claim existing = store.claim(u.id, s.id);
                lines.add(Json.obj("line", i + 1, "ok", true, "message", u.name + " · " + s.name + " " + level
                        + (ver ? " (verified)" : "") + (existing != null ? " - replaces level " + existing.claimed : "")));
                if (dryRun) continue;
                store.batch = true;     // one save at the end
                Claim c = profiles.claimAs(u, s, level, used, "Imported from HR records");
                if (ver) {
                    c.verified = level;
                    c.status = ClaimStatus.VERIFIED;
                    c.verifiedAt = Clock.millis();
                    c.verifiedBy = by.id;
                    c.note = "Imported from HR records";
                    c.event(c.verifiedAt, by.id, "imported", level, null);
                }
            }
            store.batch = before;
            if (!dryRun && ok > 0) {
                store.log(by.id, "import", null, ok + " lines, " + verified + " verified, " + bad + " refused");
                store.save("claims", "notes", "audit");
            }
            return Json.obj("dryRun", dryRun, "ok", ok, "bad", bad, "verified", verified, "lines", lines);
        }
    }

    private static String cell(List<String> r, int i) { return i < r.size() ? r.get(i).trim() : ""; }

    /** The skill matrix as CSV (people the viewer may see in full × skills, effective levels). */
    public String exportCsv(User by) {
        Access.manager(by);
        synchronized (store) {
            LocalDate today = Clock.today();
            List<User> people = new ArrayList<>();
            for (User u : store.users.values()) if (u.active && (by.admin() || store.manages(by, u) || u.id.equals(by.id))) people.add(u);
            people.sort(Comparator.comparing(u -> u.name));
            List<Skill> skills = new ArrayList<>();
            for (Skill s : store.skills.values()) if (s.active) skills.add(s);
            skills.sort(Comparator.comparingInt(s -> s.number));
            List<List<String>> rows = new ArrayList<>();
            List<String> head = new ArrayList<>(List.of("Name", "Username", "Title", "Department"));
            for (Skill s : skills) head.add(s.name);
            rows.add(head);
            for (User u : people) {
                Map<String, Integer> lv = store.levels(u.id, false, today);
                List<String> r = new ArrayList<>(List.of(u.name, u.username, Text.blank(u.title) ? "" : u.title, Text.blank(u.dept) ? "" : u.dept));
                for (Skill s : skills) r.add(lv.containsKey(s.id) ? String.valueOf(lv.get(s.id)) : "");
                rows.add(r);
            }
            store.log(by.id, "export", null, people.size() + " people");
            store.save("audit");
            return Csv.write(rows);
        }
    }

    /** Counts every active person's effective level per skill on the given day (first day of a month). */
    public Snapshot snapshot(LocalDate day) {
        synchronized (store) {
            Snapshot s = new Snapshot();
            s.month = YearMonth.from(day).toString();
            s.takenAt = Clock.millis();
            for (User u : store.users.values()) {
                if (!u.active) continue;
                for (Map.Entry<String, Integer> e : store.levels(u.id, false, day).entrySet())
                    s.counts.computeIfAbsent(e.getKey(), k -> new int[5])[e.getValue() - 1]++;
            }
            store.snapshots.put(s.month, s);
            store.save("snapshots");
            return s;
        }
    }

    /** Background job: makes this month's snapshot if it is missing. */
    public boolean monthly() {
        synchronized (store) {
            String m = YearMonth.from(Clock.today()).toString();
            if (store.snapshots.containsKey(m)) return false;
            snapshot(Clock.today());
            return true;
        }
    }
}
