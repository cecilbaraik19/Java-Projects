package com.cecil.swikriti.service;

import com.cecil.swikriti.core.Condition;
import com.cecil.swikriti.model.Document;
import com.cecil.swikriti.model.Role;
import com.cecil.swikriti.model.Stage;
import com.cecil.swikriti.model.Template;
import com.cecil.swikriti.model.User;
import com.cecil.swikriti.store.DataStore;
import com.cecil.swikriti.util.Clock;
import com.cecil.swikriti.util.Csv;
import com.cecil.swikriti.util.Json;
import com.cecil.swikriti.util.Passwords;
import com.cecil.swikriti.util.Text;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** The Document Controller's desk: people, workflow templates, holidays, the document register (CSV). */
public class AdminService {
    public static final int MAX_STAGES = 8;
    public static final int MAX_SEATS = 6;

    private final DataStore store;
    private final WorkflowService flow;

    public AdminService(DataStore store, WorkflowService flow) {
        this.store = store;
        this.flow = flow;
    }

    // ================================================================== people

    public User saveUser(User admin, String id, Map<String, Object> b) {
        Access.admin(admin);
        synchronized (store) {
            User u = id == null ? new User() : store.users.get(id);
            if (u == null) throw Problem.notFound("Person");
            String username = Text.clean(Json.str(b, "username", u.username), 30);
            if (username == null || !username.toLowerCase().matches("[a-z][a-z0-9._-]{1,29}"))
                throw Problem.bad("Username: 2-30 letters, digits, dot, dash or underscore, starting with a letter.");
            username = username.toLowerCase();
            User other = store.userByName(username);
            if (other != null && other != u) throw Problem.conflict("The username '" + username + "' is taken.");
            String name = Text.clean(Json.str(b, "name", u.name), 60);
            if (name == null || name.length() < 2) throw Problem.bad("Give the person's name.");
            String managerId = b.containsKey("managerId") ? Json.str(b, "managerId") : u.managerId;
            if (Text.blank(managerId)) managerId = null;
            if (managerId != null) {
                User m = store.users.get(managerId);
                if (m == null) throw Problem.bad("Unknown manager.");
                if (id != null) {   // no loops: the new manager must not report (directly or not) to this person
                    for (User x = m; x != null; x = x.managerId == null ? null : store.users.get(x.managerId))
                        if (x.id.equals(u.id)) throw Problem.bad("That would make a loop in the reporting line.");
                }
            }
            String password = Json.str(b, "password");
            if (id == null && Text.blank(password)) throw Problem.bad("Give a first password.");
            if (!Text.blank(password)) {
                String p = Passwords.problem(password);
                if (p != null) throw Problem.bad(p);
            }
            Role role = b.containsKey("role") ? Role.of(Json.str(b, "role")) : u.role;
            boolean active = Json.bool(b, "active", u.id == null || u.active);
            if (u == admin && (!active || role != Role.ADMIN)) throw Problem.bad("You cannot remove your own admin rights or switch yourself off.");
            List<String> groups = new ArrayList<>();
            for (String g : b.containsKey("groups") ? Json.strings(b, "groups") : u.groups) {
                String c = Text.clean(g, 30);
                if (!Text.blank(c) && groups.stream().noneMatch(x -> x.equalsIgnoreCase(c))) groups.add(c);
            }
            u.username = username;
            u.name = name;
            u.title = Text.clean(Json.str(b, "title", u.title), 60);
            u.dept = Text.clean(Json.str(b, "dept", u.dept), 40);
            u.location = Text.clean(Json.str(b, "location", u.location), 40);
            u.role = role;
            u.managerId = managerId;
            u.groups = groups;
            u.active = active;
            if (!Text.blank(password)) u.passwordHash = Passwords.hash(password);
            if (u.id == null) {
                u.id = store.nextId("U", 1001);
                store.users.put(u.id, u);
            }
            store.log(admin.id, id == null ? "user.add" : "user.edit", u.username, u.role + (u.active ? "" : " (switched off)"));
            store.save("users", "audit");
            return u;
        }
    }

    // ================================================================== templates

    /** Saves a workflow template. Every rule is checked before anything is changed. */
    public Template saveTemplate(User admin, String code, Map<String, Object> b) {
        Access.admin(admin);
        synchronized (store) {
            Template old = code == null ? null : store.templates.get(code);
            if (code != null && old == null) throw Problem.notFound("Template");
            Template t = new Template();
            t.code = old != null ? old.code : Text.clean(Json.str(b, "code"), 6);
            if (old == null) {
                if (t.code == null || !t.code.matches("[A-Z]{2,5}")) throw Problem.bad("The code is 2-5 capital letters, e.g. PR.");
                if (store.templates.containsKey(t.code)) throw Problem.conflict("The code " + t.code + " is taken.");
            }
            t.name = Text.clean(Json.str(b, "name", old == null ? null : old.name), 60);
            if (t.name == null || t.name.length() < 3) throw Problem.bad("Give the document type a name.");
            t.description = Text.clean(Json.str(b, "description", old == null ? "" : old.description), 240);
            t.hasAmount = Json.bool(b, "hasAmount", old != null && old.hasAmount);
            t.publishOnApproval = Json.bool(b, "publishOnApproval", old != null && old.publishOnApproval);
            t.distinct = Json.bool(b, "distinct", old != null && old.distinct);
            t.active = Json.bool(b, "active", old == null || old.active);
            t.version = old == null ? 1 : old.version + 1;
            List<Object> raw = b.containsKey("stages") ? Json.list(b, "stages") : null;
            if (raw == null && old != null) for (Stage s : old.stages) t.stages.add(s.copy());
            else if (raw != null) for (Object o : raw) {
                if (!(o instanceof Map<?, ?> m)) throw Problem.bad("Bad stage.");
                t.stages.add(Stage.fromJson(cast(m)));
            }
            String problem = templateProblem(t);
            if (problem != null) throw Problem.bad(problem);
            store.templates.put(t.code, t);
            store.log(admin.id, old == null ? "template.add" : "template.edit", t.code, "version " + t.version + ", " + t.stages.size() + " stages");
            store.save("templates", "audit");
            return t;
        }
    }

    /** null if the template is fine, else the first thing that is wrong. */
    public String templateProblem(Template t) {
        if (t.stages.isEmpty()) return "A workflow needs at least one stage.";
        if (t.stages.size() > MAX_STAGES) return "At most " + MAX_STAGES + " stages.";
        boolean always = false;
        for (int i = 0; i < t.stages.size(); i++) {
            Stage s = t.stages.get(i);
            String at = "Stage " + (i + 1) + ": ";
            s.name = Text.clean(s.name, 40);
            if (s.name == null || s.name.length() < 2) return at + "give it a name.";
            if (s.seats.isEmpty()) return at + "add at least one seat (who signs).";
            if (s.seats.size() > MAX_SEATS) return at + "at most " + MAX_SEATS + " seats.";
            for (String r : s.seats) {
                String p = flow.ruleProblem(r);
                if (p != null) return at + p;
            }
            if (s.mode == null) return at + "choose ALL, ANY or QUORUM.";
            if (s.mode == Stage.Mode.QUORUM && (s.quorum < 1 || s.quorum > s.seats.size()))
                return at + "the quorum must be between 1 and the number of seats (" + s.seats.size() + ").";
            if (s.slaHours < 1 || s.slaHours > 200) return at + "the time limit must be 1-200 office hours.";
            s.condition = s.condition == null ? "" : s.condition.trim();
            String c = Condition.check(s.condition);
            if (c != null) return at + "condition: " + c;
            if (s.condition.isEmpty()) always = true;
        }
        if (!always) return "At least one stage must have no condition - otherwise a document could be approved by nobody.";
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) { return (Map<String, Object>) m; }

    // ================================================================== holidays

    public List<String> setHolidays(User admin, List<String> dates) {
        Access.admin(admin);
        TreeSet<String> clean = new TreeSet<>();
        for (String d : dates) {
            if (Text.blank(d)) continue;
            try { clean.add(LocalDate.parse(d.trim()).toString()); } catch (Exception e) { throw Problem.bad("'" + d + "' is not a date (use yyyy-mm-dd)."); }
        }
        if (clean.size() > 60) throw Problem.bad("At most 60 holidays.");
        synchronized (store) {
            store.meta.put("holidays", new ArrayList<>(clean));
            store.log(admin.id, "holidays.set", clean.size() + " days", null);
            store.save("audit");
        }
        return new ArrayList<>(clean);
    }

    // ================================================================== the register (CSV for Excel)

    public String exportCsv(User admin) {
        Access.admin(admin);
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Number", "Type", "Title", "Author", "Department", "Amount (Rs.)", "Status", "Revision", "Submitted", "Closed", "Certificate"));
        synchronized (store) {
            for (Document d : store.docs.values()) {
                if (d.number == null) continue;
                User a = store.users.get(d.authorId);
                rows.add(List.of(d.number, d.type, d.title, a == null ? "" : a.name, d.dept == null ? "" : d.dept,
                        String.valueOf(d.amount), d.status.name(), d.latest() == null ? "" : d.latest().letter(),
                        d.submittedAt == 0 ? "" : f.format(java.time.Instant.ofEpochMilli(d.submittedAt).atZone(Clock.ZONE)),
                        d.closedAt == 0 ? "" : f.format(java.time.Instant.ofEpochMilli(d.closedAt).atZone(Clock.ZONE)),
                        d.certificate == null ? "" : d.certificate));
            }
        }
        return Csv.write(rows);
    }
}
