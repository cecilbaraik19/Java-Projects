package com.cecil.swikriti.service;

import com.cecil.swikriti.core.BusinessCalendar;
import com.cecil.swikriti.core.Diff;
import com.cecil.swikriti.core.Ledger;
import com.cecil.swikriti.core.Stats;
import com.cecil.swikriti.model.Comment;
import com.cecil.swikriti.model.Delegation;
import com.cecil.swikriti.model.DocStatus;
import com.cecil.swikriti.model.Document;
import com.cecil.swikriti.model.LedgerEntry;
import com.cecil.swikriti.model.Note;
import com.cecil.swikriti.model.Revision;
import com.cecil.swikriti.model.RouteStage;
import com.cecil.swikriti.model.Task;
import com.cecil.swikriti.model.Template;
import com.cecil.swikriti.model.User;
import com.cecil.swikriti.store.DataStore;
import com.cecil.swikriti.util.Clock;
import com.cecil.swikriti.util.Json;
import com.cecil.swikriti.util.Text;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Read-only answers for the pages of the website. Nothing here changes data (except marking notes read). */
public class ViewService {
    private final DataStore store;
    private final WorkflowService flow;

    public ViewService(DataStore store, WorkflowService flow) {
        this.store = store;
        this.flow = flow;
    }

    // ================================================================== basics

    public static Map<String, Object> person(User u) {
        return Json.obj("id", u.id, "name", u.name, "initials", u.initials(), "title", u.title, "dept", u.dept,
                "location", u.location, "role", u.role, "managerId", u.managerId, "groups", u.groups, "active", u.active);
    }

    public Map<String, Object> meta(User u) {
        synchronized (store) {
            List<Object> people = new ArrayList<>();
            for (User x : store.users.values()) people.add(person(x));
            List<Object> types = new ArrayList<>(store.templates.values());
            return Json.obj("today", Clock.today().toString(), "now", Clock.millis(), "people", people, "templates", types,
                    "unread", unread(u), "inbox", inboxCount(u), "holidays", store.holidays(),
                    "groups", groups());
        }
    }

    private List<String> groups() {
        java.util.TreeSet<String> g = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (User x : store.users.values()) g.addAll(x.groups);
        return new ArrayList<>(g);
    }

    private int unread(User u) {
        int n = 0;
        for (Note x : store.notes.values()) if (x.userId.equals(u.id) && !x.read) n++;
        return n;
    }

    private int inboxCount(User u) {
        int n = 0;
        for (Task k : store.tasks.values()) if (k.state.open() && k.assignees.contains(u.id)) {
            Document d = store.docs.get(k.docId);
            if (d != null && d.status == DocStatus.IN_REVIEW) n++;
        }
        return n;
    }

    Map<String, Object> summary(Document d) {
        Map<String, Object> m = Json.obj("id", d.id, "number", d.number, "type", d.type, "title", d.title, "authorId", d.authorId,
                "dept", d.dept, "amount", d.amount, "urgent", d.urgent, "status", d.status, "rev", d.latest() == null ? null : d.latest().letter(),
                "createdAt", d.createdAt, "updatedAt", d.updatedAt, "submittedAt", d.submittedAt, "closedAt", d.closedAt,
                "certificate", d.certificate);
        if (d.stageIndex >= 0 && d.stageIndex < d.route.size()) m.put("stage", d.route.get(d.stageIndex).stage.name);
        int done = 0, total = 0;
        for (RouteStage r : d.route) if (r.state != RouteStage.State.SKIPPED) { total++; if (r.state == RouteStage.State.DONE) done++; }
        m.put("progress", Json.obj("done", done, "total", total));
        return m;
    }

    // ================================================================== inbox

    public Map<String, Object> inbox(User u) {
        synchronized (store) {
            long now = Clock.millis();
            BusinessCalendar cal = store.calendar();
            List<Map<String, Object>> waiting = new ArrayList<>();
            for (Task k : store.tasks.values()) {
                if (!k.state.open() || !k.assignees.contains(u.id)) continue;
                Document d = store.docs.get(k.docId);
                if (d == null || d.status != DocStatus.IN_REVIEW) continue;
                Map<String, Object> m = summary(d);
                m.put("taskId", k.id);
                m.put("stageName", d.route.get(k.stage).stage.name);
                m.put("rule", k.rule);
                m.put("via", k.via.get(u.id));
                m.put("dueAt", k.dueAt);
                m.put("overdue", now > k.dueAt);
                m.put("hoursLeft", Stats.round1(now > k.dueAt ? -cal.hoursBetween(Instant.ofEpochMilli(k.dueAt), Instant.ofEpochMilli(now))
                        : cal.hoursBetween(Instant.ofEpochMilli(now), Instant.ofEpochMilli(k.dueAt))));
                m.put("createdAt", k.createdAt);
                waiting.add(m);
            }
            waiting.sort(Comparator.comparingLong(m -> (Long) m.get("dueAt")));
            List<Map<String, Object>> decided = new ArrayList<>();
            List<Task> mine = new ArrayList<>();
            for (Task k : store.tasks.values()) if (u.id.equals(k.decidedBy)) mine.add(k);
            mine.sort(Comparator.comparingLong((Task k) -> k.decidedAt).reversed());
            for (Task k : mine.subList(0, Math.min(8, mine.size()))) {
                Document d = store.docs.get(k.docId);
                if (d == null) continue;
                Map<String, Object> m = summary(d);
                m.put("decision", k.state);
                m.put("decidedAt", k.decidedAt);
                m.put("onBehalfOf", k.onBehalfOf);
                decided.add(m);
            }
            List<Map<String, Object>> myOpen = new ArrayList<>();
            for (Document d : store.docs.values())
                if (d.authorId.equals(u.id) && !d.status.closed()) myOpen.add(summary(d));
            myOpen.sort(Comparator.comparingLong((Map<String, Object> m) -> (Long) m.get("updatedAt")).reversed());
            List<Object> dels = new ArrayList<>();
            LocalDate today = Clock.today();
            for (Delegation g : store.delegations.values())
                if (!g.cancelled && (g.fromId.equals(u.id) || g.toId.equals(u.id)) && !LocalDate.parse(g.end).isBefore(today)) dels.add(g);
            return Json.obj("waiting", waiting, "decided", decided, "mine", myOpen, "delegations", dels);
        }
    }

    // ================================================================== lists

    public Map<String, Object> docs(User u, String scope, String status, String type, String q) {
        synchronized (store) {
            String key = Text.key(q);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Document d : store.docs.values()) {
                if (!Access.canSee(store, u, d)) continue;
                if ("mine".equals(scope) && !d.authorId.equals(u.id)) continue;
                if ("signed".equals(scope) && store.tasksOf(d.id).stream().noneMatch(k -> u.id.equals(k.decidedBy))) continue;
                if (!Text.blank(status) && !d.status.name().equals(status)) continue;
                if (!Text.blank(type) && !d.type.equals(type)) continue;
                if (!key.isEmpty()) {
                    User a = store.users.get(d.authorId);
                    String hay = Text.key(d.title + " " + d.number + " " + (d.vendor == null ? "" : d.vendor) + " " + (a == null ? "" : a.name));
                    if (!hay.contains(key)) continue;
                }
                out.add(summary(d));
            }
            out.sort(Comparator.comparingLong((Map<String, Object> m) -> (Long) m.get("updatedAt")).reversed());
            int total = out.size();
            return Json.obj("docs", out.subList(0, Math.min(300, total)), "total", total);
        }
    }

    public Map<String, Object> library(User u) {
        synchronized (store) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Document d : store.docs.values()) {
                Template t = store.templates.get(d.type);
                if (t != null && t.publishOnApproval && d.status == DocStatus.APPROVED) {
                    Map<String, Object> m = summary(d);
                    m.put("excerpt", d.latest().body.length() > 220 ? d.latest().body.substring(0, 220) + "…" : d.latest().body);
                    out.add(m);
                }
            }
            out.sort(Comparator.comparingLong((Map<String, Object> m) -> (Long) m.get("closedAt")).reversed());
            return Json.obj("docs", out);
        }
    }

    // ================================================================== one document

    public Map<String, Object> doc(User u, String id) {
        synchronized (store) {
            Document d = Access.see(store, u, id);
            Template t = store.templates.get(d.type);
            Map<String, Object> m = d.toJson();
            m.put("typeName", t == null ? d.type : t.name);
            m.put("hasAmount", t != null && t.hasAmount);
            m.put("distinct", t != null && t.distinct);
            List<Task> tasks = store.tasksOf(d.id);
            // the route of the latest revision, with its tasks per stage
            List<Object> route = new ArrayList<>();
            for (int i = 0; i < d.route.size(); i++) {
                Map<String, Object> rs = d.route.get(i).toJson();
                List<Object> seats = new ArrayList<>();
                for (Task k : tasks) if (k.rev == d.rev() && k.stage == i) seats.add(k);
                rs.put("tasks", seats);
                route.add(rs);
            }
            m.put("route", route);
            // earlier revisions: who decided what (for the revision table)
            List<Object> history = new ArrayList<>();
            for (Task k : tasks) if (k.decidedBy != null && k.state != Task.State.CANCELLED && k.state != Task.State.NOT_NEEDED)
                history.add(Json.obj("rev", k.rev, "letter", Revision.letter(k.rev), "stage", k.stage, "stageName", stageName(d, k),
                        "state", k.state, "by", k.decidedBy, "onBehalfOf", k.onBehalfOf, "at", k.decidedAt, "comment", k.comment));
            m.put("decisions", history);
            List<Object> cs = new ArrayList<>();
            for (Comment c : store.comments.values()) if (c.docId.equals(d.id)) cs.add(c);
            m.put("comments", cs);
            List<Object> chain = new ArrayList<>();
            for (LedgerEntry e : store.ledger) if (d.id.equals(e.docId())) chain.add(e);
            m.put("ledger", chain);
            Task myTask = null;
            for (Task k : tasks) if (k.state.open() && k.assignees.contains(u.id) && d.status == DocStatus.IN_REVIEW) { myTask = k; break; }
            boolean author = d.authorId.equals(u.id);
            boolean participant = author || u.admin();
            for (Task k : tasks) if (k.assignees.contains(u.id)) participant = true;
            m.put("myTask", myTask == null ? null : Json.obj("id", myTask.id, "stage", myTask.stage, "via", myTask.via.get(u.id),
                    "dueAt", myTask.dueAt, "rule", myTask.rule));
            m.put("can", Json.obj("edit", author && (d.status == DocStatus.DRAFT || d.status == DocStatus.RETURNED),
                    "withdraw", author && !d.status.closed(),
                    "comment", participant && d.rev() > 0 && !d.status.closed()));
            m.put("plan", store.templates.containsKey(d.type) && (d.status == DocStatus.DRAFT || d.status == DocStatus.RETURNED)
                    ? flow.plan(d, t) : null);
            return m;
        }
    }

    private static String stageName(Document d, Task k) {
        if (k.stage < d.route.size()) return d.route.get(k.stage).stage.name;   // older revisions used the same template
        return "Stage " + (k.stage + 1);
    }

    public Map<String, Object> diff(User u, String id, int a, int b) {
        synchronized (store) {
            Document d = Access.see(store, u, id);
            if (a < 1 || b < 1 || a > d.rev() || b > d.rev() || a == b) throw Problem.bad("Choose two different revisions.");
            Revision ra = d.revisions.get(a - 1), rb = d.revisions.get(b - 1);
            List<Diff.Line> lines = Diff.lines(ra.body, rb.body);
            List<Object> out = new ArrayList<>();
            for (Diff.Line l : lines) out.add(Json.obj("kind", l.kind(), "old", l.oldNo(), "new", l.newNo(), "text", l.text()));
            List<Object> fields = new ArrayList<>();
            field(fields, "Title", ra.title, rb.title);
            field(fields, "Amount (Rs.)", String.valueOf(ra.amount), String.valueOf(rb.amount));
            field(fields, "Vendor", ra.vendor, rb.vendor);
            field(fields, "Urgent", ra.urgent ? "yes" : "no", rb.urgent ? "yes" : "no");
            field(fields, "Needed by", ra.needBy, rb.needBy);
            field(fields, "Attachment", ra.attachment == null ? null : ra.attachment.name + " (" + ra.attachment.sha.substring(0, 8) + ")",
                    rb.attachment == null ? null : rb.attachment.name + " (" + rb.attachment.sha.substring(0, 8) + ")");
            return Json.obj("a", ra.letter(), "b", rb.letter(), "lines", out, "changes", Diff.changes(lines), "fields", fields, "note", rb.note);
        }
    }

    private static void field(List<Object> out, String name, String a, String b) {
        String x = a == null ? "" : a, y = b == null ? "" : b;
        if (!x.equals(y)) out.add(Json.obj("field", name, "from", x, "to", y));
    }

    // ================================================================== reports

    public Map<String, Object> reports(User u) {
        if (!u.manager()) throw Problem.forbidden("Reports are for managers and the Document Controller.");
        synchronized (store) {
            BusinessCalendar cal = store.calendar();
            long now = Clock.millis();
            Map<String, Map<String, Object>> byType = new LinkedHashMap<>();
            Map<String, List<Double>> cycles = new LinkedHashMap<>();
            for (Template t : store.templates.values()) {
                byType.put(t.code, Json.obj("code", t.code, "name", t.name, "submitted", 0, "approved", 0, "rejected", 0,
                        "withdrawn", 0, "open", 0, "returns", 0));
                cycles.put(t.code, new ArrayList<>());
            }
            List<Double> allCycles = new ArrayList<>();
            for (Document d : store.docs.values()) {
                Map<String, Object> r = byType.get(d.type);
                if (r == null || d.submittedAt == 0) continue;
                inc(r, "submitted");
                switch (d.status) {
                    case APPROVED -> inc(r, "approved");
                    case REJECTED -> inc(r, "rejected");
                    case WITHDRAWN -> inc(r, "withdrawn");
                    default -> inc(r, "open");
                }
                if (d.status == DocStatus.APPROVED) {
                    double h = cal.hoursBetween(Instant.ofEpochMilli(d.submittedAt), Instant.ofEpochMilli(d.closedAt));
                    cycles.get(d.type).add(h);
                    allCycles.add(h);
                }
            }
            // stages: how long tasks waited, and how many were on time
            Map<String, List<Double>> waits = new LinkedHashMap<>();
            Map<String, int[]> onTime = new LinkedHashMap<>();
            Map<String, Integer> pendingBy = new LinkedHashMap<>();
            int ok = 0, all = 0;
            for (Task k : store.tasks.values()) {
                Document d = store.docs.get(k.docId);
                if (d == null) continue;
                if (k.state == Task.State.RETURNED) inc(byType.get(d.type), "returns");
                String key = d.type + " · " + stageName(d, k);
                if (k.state == Task.State.APPROVED || k.state == Task.State.REJECTED || k.state == Task.State.RETURNED) {
                    waits.computeIfAbsent(key, x -> new ArrayList<>()).add(cal.hoursBetween(Instant.ofEpochMilli(k.createdAt), Instant.ofEpochMilli(k.decidedAt)));
                    int[] c = onTime.computeIfAbsent(key, x -> new int[2]);
                    c[1]++; all++;
                    if (k.decidedAt <= k.dueAt) { c[0]++; ok++; }
                }
                if (k.state.open() && d.status == DocStatus.IN_REVIEW) for (String a : k.candidates) pendingBy.merge(a, 1, Integer::sum);
            }
            List<Object> types = new ArrayList<>();
            for (Map.Entry<String, Map<String, Object>> e : byType.entrySet()) {
                Map<String, Object> r = e.getValue();
                List<Double> c = cycles.get(e.getKey());
                long closedYesNo = num(r, "approved") + num(r, "rejected");
                r.put("approvalRate", closedYesNo == 0 ? null : Math.round(100.0 * num(r, "approved") / closedYesNo));
                r.put("median", c.isEmpty() ? null : Stats.round1(Stats.median(c)));
                r.put("p85", c.isEmpty() ? null : Stats.round1(Stats.percentile(c, 0.85)));
                if (num(r, "submitted") > 0) types.add(r);
            }
            List<Map<String, Object>> stages = new ArrayList<>();
            for (Map.Entry<String, List<Double>> e : waits.entrySet()) {
                int[] c = onTime.get(e.getKey());
                stages.add(Json.obj("stage", e.getKey(), "count", e.getValue().size(), "median", Stats.round1(Stats.median(e.getValue())),
                        "p85", Stats.round1(Stats.percentile(e.getValue(), 0.85)), "onTime", Math.round(100.0 * c[0] / c[1])));
            }
            stages.sort(Comparator.comparingDouble((Map<String, Object> m) -> ((Number) m.get("median")).doubleValue()).reversed());
            List<Object> people = new ArrayList<>();
            pendingBy.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(8)
                    .forEach(e -> people.add(Json.obj("id", e.getKey(), "pending", e.getValue())));
            // 12 weeks of throughput (Monday to Sunday)
            LocalDate monday = Clock.today().with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
            List<Object> weeks = new ArrayList<>();
            for (int w = 11; w >= 0; w--) {
                LocalDate s = monday.minusWeeks(w), e = s.plusDays(7);
                long a = s.atStartOfDay(Clock.ZONE).toInstant().toEpochMilli(), b = e.atStartOfDay(Clock.ZONE).toInstant().toEpochMilli();
                int sub = 0, appr = 0, rej = 0;
                for (Document d : store.docs.values()) {
                    if (d.submittedAt >= a && d.submittedAt < b) sub++;
                    if (d.closedAt >= a && d.closedAt < b && d.status == DocStatus.APPROVED) appr++;
                    if (d.closedAt >= a && d.closedAt < b && d.status == DocStatus.REJECTED) rej++;
                }
                weeks.add(Json.obj("week", s.toString(), "submitted", sub, "approved", appr, "rejected", rej));
            }
            int overdue = 0, openTasks = 0;
            for (Task k : store.tasks.values()) {
                Document d = store.docs.get(k.docId);
                if (k.state.open() && d != null && d.status == DocStatus.IN_REVIEW) { openTasks++; if (now > k.dueAt) overdue++; }
            }
            return Json.obj("types", types, "stages", stages, "people", people, "weeks", weeks,
                    "onTime", all == 0 ? null : Math.round(100.0 * ok / all), "decisions", all,
                    "median", allCycles.isEmpty() ? null : Stats.round1(Stats.median(allCycles)),
                    "p85", allCycles.isEmpty() ? null : Stats.round1(Stats.percentile(allCycles, 0.85)),
                    "openTasks", openTasks, "overdue", overdue,
                    "bottleneck", stages.isEmpty() ? null : stages.get(0).get("stage"));
        }
    }

    private static void inc(Map<String, Object> m, String k) { if (m != null) m.put(k, num(m, k) + 1); }
    private static long num(Map<String, Object> m, String k) { return ((Number) m.get(k)).longValue(); }

    // ================================================================== admin

    public Map<String, Object> admin(User u) {
        Access.admin(u);
        synchronized (store) {
            List<Object> people = new ArrayList<>();
            for (User x : store.users.values()) {
                Map<String, Object> p = person(x);
                p.put("username", x.username);
                p.put("lastSeen", x.lastSeen);
                people.add(p);
            }
            List<Object> audit = new ArrayList<>();
            for (int i = store.audit.size() - 1; i >= 0 && audit.size() < 150; i--) audit.add(store.audit.get(i));
            List<Object> dels = new ArrayList<>(store.delegations.values());
            return Json.obj("people", people, "templates", new ArrayList<>(store.templates.values()), "holidays", store.holidays(),
                    "audit", audit, "delegations", dels, "ledgerSize", store.ledger.size());
        }
    }

    public Map<String, Object> ledger(User u) {
        Access.admin(u);
        synchronized (store) {
            List<Object> last = new ArrayList<>();
            for (int i = store.ledger.size() - 1; i >= 0 && last.size() < 60; i--) {
                LedgerEntry e = store.ledger.get(i);
                Map<String, Object> m = e.toJson();
                Document d = store.docs.get(e.docId());
                m.put("number", d == null ? null : d.number);
                last.add(m);
            }
            Ledger.Check c = Ledger.verify(store.ledger, store.secret());
            return Json.obj("entries", last, "size", store.ledger.size(), "ok", c.ok(), "brokenAt", c.brokenAt(), "reason", c.reason());
        }
    }

    // ================================================================== notifications

    public Map<String, Object> notes(User u) {
        synchronized (store) {
            List<Note> mine = new ArrayList<>();
            for (Note n : store.notes.values()) if (n.userId.equals(u.id)) mine.add(n);
            mine.sort(Comparator.comparingLong((Note n) -> n.at).reversed());
            return Json.obj("notes", mine.subList(0, Math.min(40, mine.size())), "unread", unread(u));
        }
    }

    public int read(User u, String id) {
        synchronized (store) {
            int n = 0;
            for (Note x : store.notes.values())
                if (x.userId.equals(u.id) && !x.read && (id == null || id.equals(x.id))) { x.read = true; n++; }
            if (n > 0) store.save("notes");
            return n;
        }
    }

    // ================================================================== public check of a certificate

    /** For anybody with the code printed on an approved document: is it real, and which revision was approved? */
    public Map<String, Object> verify(String code) {
        String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT).replaceAll("[^0-9A-F]", "");
        if (c.length() != 12) throw Problem.bad("A verification code has 12 letters and digits, like 7F3A-91C2-0B4D.");
        String pretty = c.substring(0, 4) + "-" + c.substring(4, 8) + "-" + c.substring(8);
        synchronized (store) {
            for (Document d : store.docs.values()) {
                if (!pretty.equals(d.certificate)) continue;
                Template t = store.templates.get(d.type);
                Revision r = d.latest();
                List<Object> signers = new ArrayList<>();
                for (Task k : store.tasksOf(d.id)) if (k.rev == d.rev() && k.state == Task.State.APPROVED) {
                    User s = store.users.get(k.decidedBy);
                    User b = k.onBehalfOf == null ? null : store.users.get(k.onBehalfOf);
                    signers.add(Json.obj("stage", stageName(d, k), "name", s == null ? "?" : s.name, "title", s == null ? null : s.title,
                            "onBehalfOf", b == null ? null : b.name, "at", k.decidedAt));
                }
                Ledger.Check chk = Ledger.verify(store.ledger, store.secret());
                boolean ownOk = true;
                for (LedgerEntry e : store.ledger) if (d.id.equals(e.docId()) && e.rev() == d.rev() && !r.hash.equals(e.docHash())) ownOk = false;
                return Json.obj("found", true, "number", d.number, "type", t == null ? d.type : t.name, "title", d.title,
                        "rev", r.letter(), "approvedAt", d.closedAt, "hash", r.hash, "attachment", r.attachment,
                        "signers", signers, "ledgerOk", chk.ok() && ownOk, "code", pretty);
            }
        }
        return Json.obj("found", false, "code", pretty);
    }

    // ================================================================== delegations

    public Map<String, Object> delegations(User u) {
        synchronized (store) {
            List<Delegation> mine = new ArrayList<>(), forMe = new ArrayList<>();
            for (Delegation g : store.delegations.values()) {
                if (g.fromId.equals(u.id)) mine.add(g);
                else if (g.toId.equals(u.id)) forMe.add(g);
            }
            Comparator<Delegation> c = Comparator.comparing((Delegation g) -> g.start).reversed();
            mine.sort(c);
            forMe.sort(c);
            return Json.obj("mine", mine, "forMe", forMe, "today", Clock.today().toString());
        }
    }
}
