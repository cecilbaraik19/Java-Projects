package com.cecil.swikriti.service;

import com.cecil.swikriti.core.BusinessCalendar;
import com.cecil.swikriti.core.Condition;
import com.cecil.swikriti.core.Delegations;
import com.cecil.swikriti.core.Ledger;
import com.cecil.swikriti.model.Delegation;
import com.cecil.swikriti.model.DocStatus;
import com.cecil.swikriti.model.Document;
import com.cecil.swikriti.model.LedgerEntry;
import com.cecil.swikriti.model.Revision;
import com.cecil.swikriti.model.RouteStage;
import com.cecil.swikriti.model.Stage;
import com.cecil.swikriti.model.Task;
import com.cecil.swikriti.model.Template;
import com.cecil.swikriti.model.User;
import com.cecil.swikriti.store.DataStore;
import com.cecil.swikriti.util.Clock;
import com.cecil.swikriti.util.Json;
import com.cecil.swikriti.util.Text;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The WORKFLOW ENGINE. It moves a document through the stages of its route.
 *
 *   start      copy the template's stages into the document, decide which stages are needed (conditions),
 *              then open the first needed stage
 *   open       make one Task per seat; find the people for each seat (rules, minus the author, plus delegates);
 *              set the deadline in office hours
 *   decide     approve / reject / send back. After every decision the stage is checked:
 *                 enough "yes"           -> stage DONE, open the next stage (or the document is APPROVED)
 *                 can no longer get enough "yes" -> stage FAILED, the document is REJECTED
 *                 sent back              -> the document goes back to the author (RETURNED)
 *   tick       every few minutes: reminders at 75 % of the deadline, escalation to the manager when overdue
 *
 * Every decision is written into the hash-chained ledger.
 */
public class WorkflowService {
    public static final int MAX_COMMENT = 1000;
    public static final double REMIND_AT = 0.75;

    private final DataStore store;

    public WorkflowService(DataStore store) { this.store = store; }

    // ================================================================== who sits on a seat

    /** The people a seat rule points to for a document by this author (before removing the author). */
    public List<String> resolve(String rule, User author) {
        List<String> out = new ArrayList<>();
        if (rule == null) return out;
        String r = rule.trim();
        if (r.equalsIgnoreCase("MANAGER") || r.equalsIgnoreCase("MANAGER2")) {
            User m = author == null || author.managerId == null ? null : store.users.get(author.managerId);
            if (m != null && r.equalsIgnoreCase("MANAGER2")) m = m.managerId == null ? null : store.users.get(m.managerId);
            if (m != null && m.active) out.add(m.id);
        } else if (r.toUpperCase(Locale.ROOT).startsWith("GROUP:")) {
            String g = r.substring(6).trim();
            for (User u : store.users.values()) if (u.active && u.inGroup(g)) out.add(u.id);
        } else if (r.toUpperCase(Locale.ROOT).startsWith("USER:")) {
            User u = store.users.get(r.substring(5).trim());
            if (u != null && u.active) out.add(u.id);
        }
        return out;
    }

    /** null when the rule is fine, else what is wrong with it. */
    public String ruleProblem(String rule) {
        if (rule == null || rule.isBlank()) return "A seat needs a rule.";
        String r = rule.trim(), up = r.toUpperCase(Locale.ROOT);
        if (up.equals("MANAGER") || up.equals("MANAGER2")) return null;
        if (up.startsWith("GROUP:")) {
            String g = r.substring(6).trim();
            for (User u : store.users.values()) if (u.active && u.inGroup(g)) return null;
            return "Nobody is in the group '" + g + "'.";
        }
        if (up.startsWith("USER:")) {
            User u = store.users.get(r.substring(5).trim());
            return u != null && u.active ? null : "No active person with id " + r.substring(5).trim() + ".";
        }
        return "Unknown rule '" + r + "'. Use MANAGER, MANAGER2, GROUP:<name> or USER:<id>.";
    }

    /** The document's facts for conditions: amount, dept, type, vendor, title, urgent. */
    public static Map<String, Object> facts(Document d) {
        return Json.obj("amount", d.amount, "dept", d.dept, "type", d.type, "vendor", d.vendor == null ? "" : d.vendor,
                "title", d.title, "urgent", d.urgent);
    }

    /**
     * What the route WOULD be for this document right now (used for the "who will approve this?" preview and by start).
     * Each item: stage name, needed or skipped (and why), and the people per seat.
     */
    public List<Map<String, Object>> plan(Document d, Template t) {
        List<Map<String, Object>> out = new ArrayList<>();
        User author = store.users.get(d.authorId);
        Map<String, Object> f = facts(d);
        for (Stage s : t.stages) {
            boolean needed = Condition.test(s.condition, f);
            List<Object> seats = new ArrayList<>();
            for (String rule : s.seats) {
                List<String> people = resolve(rule, author);
                people.remove(d.authorId);
                boolean fallback = people.isEmpty();
                if (fallback) people = controllers(d.authorId);
                seats.add(Json.obj("rule", rule, "people", people, "fallback", fallback));
            }
            out.add(Json.obj("name", s.name, "needed", needed, "condition", s.condition, "mode", s.mode,
                    "needed_count", s.needed(), "slaHours", s.slaHours, "seats", seats));
        }
        return out;
    }

    /** The Document Controllers (admins) - the last resort when a seat finds nobody. */
    List<String> controllers(String exceptId) {
        List<String> out = new ArrayList<>();
        for (User u : store.users.values()) if (u.active && u.admin() && !u.id.equals(exceptId)) out.add(u.id);
        return out;
    }

    // ================================================================== starting and opening stages

    /** Called by DocumentService.submit (holding the store lock): the revision is already added. */
    void start(Document d, Template t) {
        d.route.clear();
        d.templateVersion = t.version;
        Map<String, Object> f = facts(d);
        for (Stage s : t.stages) {
            RouteStage rs = new RouteStage();
            rs.stage = s.copy();
            if (!Condition.test(s.condition, f)) {
                rs.state = RouteStage.State.SKIPPED;
                rs.why = "not needed: " + s.condition.trim();
            }
            d.route.add(rs);
        }
        d.status = DocStatus.IN_REVIEW;
        open(d, 0);
    }

    /** Opens the first needed stage at or after `from`; if there is none left, the document is approved. */
    private void open(Document d, int from) {
        int i = from;
        while (i < d.route.size() && d.route.get(i).state == RouteStage.State.SKIPPED) i++;
        if (i >= d.route.size()) { approved(d); return; }
        RouteStage rs = d.route.get(i);
        rs.state = RouteStage.State.ACTIVE;
        rs.startedAt = Clock.millis();
        d.stageIndex = i;
        User author = store.users.get(d.authorId);
        Template t = store.templates.get(d.type);
        boolean distinct = t != null && t.distinct;
        Set<String> signedBefore = new LinkedHashSet<>();
        for (Task k : store.tasksOf(d.id)) if (k.rev == d.rev() && k.state == Task.State.APPROVED) signedBefore.add(k.decidedBy);
        double sla = rs.stage.slaHours * (d.urgent ? 0.5 : 1.0);     // urgent documents get half the time
        long due = store.calendar().addHours(Clock.now(), sla).toEpochMilli();
        LocalDate today = Clock.today();
        for (int seat = 0; seat < rs.stage.seats.size(); seat++) {
            Task k = new Task();
            k.id = store.nextId("T", 1);
            k.docId = d.id;
            k.rev = d.rev();
            k.stage = i;
            k.seat = seat;
            k.rule = rs.stage.seats.get(seat);
            List<String> people = resolve(k.rule, author);
            people.remove(d.authorId);                                    // four eyes: never your own document
            if (distinct) {
                List<String> fresh = new ArrayList<>(people);
                fresh.removeAll(signedBefore);
                if (!fresh.isEmpty()) people = fresh;                     // keep them only if nobody else is left
            }
            if (people.isEmpty()) {
                people = controllers(d.authorId);
                k.via.put("fallback", "nobody found for " + k.rule + " - sent to the Document Controller");
            }
            k.candidates = new ArrayList<>(people);
            k.assignees = new ArrayList<>(people);
            for (String c : people) addDelegates(k, c, d, today, distinct ? signedBefore : Set.of());
            k.createdAt = Clock.millis();
            k.dueAt = due;
            store.tasks.put(k.id, k);
            for (String a : k.assignees)
                store.notify(a, d.number + " “" + d.title + "” is waiting for you (" + rs.stage.name + ")", "#/doc/" + d.id);
        }
    }

    /** Adds the people a candidate has delegated to (today, for this type) as extra assignees. */
    private void addDelegates(Task k, String candidate, Document d, LocalDate day, Set<String> skip) {
        for (String del : Delegations.who(candidate, day, d.type, store.delegations.values())) {
            if (del.equals(d.authorId) || k.assignees.contains(del) || skip.contains(del)) continue;
            User du = store.users.get(del);
            if (du == null || !du.active) continue;
            k.assignees.add(del);
            k.via.put(del, "delegate of " + candidate);
        }
    }

    private void approved(Document d) {
        d.status = DocStatus.APPROVED;
        d.stageIndex = -1;
        d.closedAt = Clock.millis();
        Revision r = d.latest();
        LedgerEntry e = store.chain(d.id, d.rev(), "CERTIFIED", "system", null, r.hash);
        String h = e.hash().toUpperCase(Locale.ROOT);
        d.certificate = h.substring(0, 4) + "-" + h.substring(4, 8) + "-" + h.substring(8, 12);
        tellResult(d, "approved ✓");
    }

    private void tellResult(Document d, String what) {
        String text = d.number + " “" + d.title + "” was " + what;
        store.notify(d.authorId, text, "#/doc/" + d.id);
        for (String c : d.cc) store.notify(c, text, "#/doc/" + d.id);
    }

    // ================================================================== decisions

    public enum Action { APPROVE, REJECT, RETURN;
        static Action of(String s) {
            try { return valueOf(s == null ? "" : s.trim().toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { throw Problem.bad("Choose approve, reject or send back."); }
        }
    }

    public Map<String, Object> decide(User u, String taskId, String action, String comment) {
        Action a = Action.of(action);
        synchronized (store) {
            Task k = store.tasks.get(taskId);
            if (k == null) throw Problem.notFound("Task");
            Document d = Access.see(store, u, k.docId);
            if (!k.state.open()) throw Problem.conflict("This task is already closed (" + k.state.name().toLowerCase().replace('_', ' ') + ").");
            if (d.status != DocStatus.IN_REVIEW || k.rev != d.rev() || k.stage != d.stageIndex)
                throw Problem.conflict("The document has moved on - reload the page.");
            if (!k.assignees.contains(u.id)) throw Problem.forbidden("This task is not yours.");
            if (u.id.equals(d.authorId)) throw Problem.forbidden("You cannot approve your own document (four-eyes rule).");
            Template t = store.templates.get(d.type);
            for (Task o : store.tasksOf(d.id)) {
                if (o.rev != d.rev() || o == k || !u.id.equals(o.decidedBy)) continue;
                if (o.stage == k.stage) throw Problem.forbidden("You already signed another seat of this stage.");
                if (t != null && t.distinct && o.state == Task.State.APPROVED)
                    throw Problem.forbidden("For " + t.name + " one person may sign only one stage. You signed “"
                            + d.route.get(o.stage).stage.name + "”.");
            }
            String c = Text.block(comment, MAX_COMMENT);
            if (a != Action.APPROVE && (c == null || c.length() < 5))
                throw Problem.bad(a == Action.REJECT ? "Please give the reason for rejecting (at least 5 characters)."
                        : "Please say what must be changed (at least 5 characters).");
            String behalf = null;
            Object via = k.via.get(u.id);
            if (!k.candidates.contains(u.id) && via instanceof String v && v.startsWith("delegate of ")) behalf = v.substring(12);

            k.state = switch (a) { case APPROVE -> Task.State.APPROVED; case REJECT -> Task.State.REJECTED; case RETURN -> Task.State.RETURNED; };
            k.decidedBy = u.id;
            k.onBehalfOf = behalf;
            k.decidedAt = Clock.millis();
            k.comment = c == null || c.isEmpty() ? null : c;
            store.chain(d.id, d.rev(), a.name(), u.id, behalf, d.latest().hash);
            d.updatedAt = Clock.millis();

            RouteStage rs = d.route.get(k.stage);
            String outcome;
            if (a == Action.RETURN) {
                closeOpen(d, Task.State.CANCELLED);
                rs.state = RouteStage.State.STOPPED;
                rs.doneAt = Clock.millis();
                d.status = DocStatus.RETURNED;
                d.stageIndex = -1;
                store.notify(d.authorId, d.number + " was sent back to you by " + store.users.get(u.id).name + ": " + c, "#/doc/" + d.id);
                outcome = "returned";
            } else {
                int yes = 0, open = 0;
                for (Task o : store.tasksOf(d.id)) {
                    if (o.rev != d.rev() || o.stage != k.stage) continue;
                    if (o.state == Task.State.APPROVED) yes++;
                    if (o.state == Task.State.PENDING) open++;
                }
                int need = rs.stage.needed();
                if (yes >= need) {
                    closeOpen(d, Task.State.NOT_NEEDED);
                    rs.state = RouteStage.State.DONE;
                    rs.doneAt = Clock.millis();
                    open(d, k.stage + 1);
                    outcome = d.status == DocStatus.APPROVED ? "approved" : "next stage";
                } else if (yes + open < need) {
                    closeOpen(d, Task.State.NOT_NEEDED);
                    rs.state = RouteStage.State.FAILED;
                    rs.doneAt = Clock.millis();
                    d.status = DocStatus.REJECTED;
                    d.stageIndex = -1;
                    d.closedAt = Clock.millis();
                    store.chain(d.id, d.rev(), "CLOSED-REJECTED", "system", null, d.latest().hash);
                    tellResult(d, "rejected at “" + rs.stage.name + "”" + (c != null ? ": " + c : ""));
                    outcome = "rejected";
                } else {
                    outcome = a == Action.APPROVE ? "waiting for more" : "vote recorded";
                }
            }
            store.save("docs", "tasks", "notes", "ledger");
            return Json.obj("outcome", outcome, "status", d.status);
        }
    }

    /** Closes every still-open task of the document's current revision. */
    void closeOpen(Document d, Task.State as) {
        for (Task o : store.tasksOf(d.id)) if (o.state == Task.State.PENDING) {
            o.state = as;
            o.decidedAt = Clock.millis();
        }
    }

    // ================================================================== reminders and escalation

    /** Runs every few minutes. Returns {reminded, escalated}. */
    public Map<String, Object> tick() {
        int reminded = 0, escalated = 0;
        synchronized (store) {
            long now = Clock.millis();
            BusinessCalendar cal = store.calendar();
            for (Task k : new ArrayList<>(store.tasks.values())) {
                if (!k.state.open()) continue;
                Document d = store.docs.get(k.docId);
                if (d == null || d.status != DocStatus.IN_REVIEW) continue;
                if (!k.reminded && now < k.dueAt && usedShare(cal, k, now) >= REMIND_AT) {
                    k.reminded = true;
                    reminded++;
                    for (String a : k.assignees) store.notify(a, "Reminder: " + d.number + " is due " + dueText(k.dueAt), "#/doc/" + d.id);
                }
                if (!k.escalated && now > k.dueAt) {
                    k.escalated = true;
                    k.reminded = true;
                    escalated++;
                    String boss = escalationTarget(k, d);
                    if (boss != null && !k.assignees.contains(boss)) {
                        k.assignees.add(boss);
                        k.via.put(boss, "escalated (overdue)");
                        store.notify(boss, "Overdue: " + d.number + " “" + d.title + "” waits for your team - you may decide it", "#/doc/" + d.id);
                    }
                    for (String a : k.candidates) store.notify(a, "Overdue: " + d.number + " - it was escalated", "#/doc/" + d.id);
                    store.log(null, "task.escalate", d.number, k.rule + " -> " + (boss == null ? "nobody" : boss));
                }
            }
            if (reminded + escalated > 0) store.save("tasks", "notes", "audit");
        }
        return Json.obj("reminded", reminded, "escalated", escalated);
    }

    /** How much of the office-hour time limit has been used (0.0 - 1.0). */
    static double usedShare(BusinessCalendar cal, Task k, long now) {
        double total = cal.hoursBetween(Instant.ofEpochMilli(k.createdAt), Instant.ofEpochMilli(k.dueAt));
        if (total <= 0) return 1;
        return cal.hoursBetween(Instant.ofEpochMilli(k.createdAt), Instant.ofEpochMilli(now)) / total;
    }

    /** The manager of the first candidate; if that is the author or nobody, a Document Controller. */
    private String escalationTarget(Task k, Document d) {
        Set<String> busy = new LinkedHashSet<>();     // people who already sit on a seat of this stage
        for (Task o : store.tasksOf(d.id)) if (o.rev == k.rev && o.stage == k.stage && o != k) busy.addAll(o.assignees);
        for (String c : k.candidates) {
            User cu = store.users.get(c);
            if (cu != null && cu.managerId != null && !cu.managerId.equals(d.authorId) && !busy.contains(cu.managerId)) {
                User m = store.users.get(cu.managerId);
                if (m != null && m.active) return m.id;
            }
        }
        for (String c : controllers(d.authorId)) if (!busy.contains(c)) return c;
        return null;
    }

    static String dueText(long ms) {
        return java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm").format(Instant.ofEpochMilli(ms).atZone(Clock.ZONE));
    }

    // ================================================================== delegation (out of office)

    public Delegation delegate(User u, Map<String, Object> b) {
        synchronized (store) {
            String fromId = u.admin() && !Text.blank(Json.str(b, "fromId")) ? Json.str(b, "fromId") : u.id;
            User from = store.users.get(fromId), to = store.users.get(Json.str(b, "toId"));
            if (from == null) throw Problem.notFound("Person");
            if (to == null || !to.active) throw Problem.bad("Pick the person who will approve for you.");
            if (to.id.equals(from.id)) throw Problem.bad("You cannot delegate to yourself.");
            Delegation d = new Delegation();
            d.fromId = from.id;
            d.toId = to.id;
            d.start = date(Json.str(b, "start"), "start");
            d.end = date(Json.str(b, "end"), "end");
            LocalDate s = LocalDate.parse(d.start), e = LocalDate.parse(d.end);
            if (e.isBefore(s)) throw Problem.bad("The end date is before the start date.");
            if (e.isBefore(Clock.today())) throw Problem.bad("The end date is in the past.");
            if (s.plusDays(90).isBefore(e)) throw Problem.bad("A delegation can be at most 90 days long.");
            for (String ty : Json.strings(b, "types")) if (store.templates.containsKey(ty)) d.types.add(ty);
            d.note = Text.clean(Json.str(b, "note"), 200);
            for (Delegation o : store.delegations.values())
                if (!o.cancelled && o.fromId.equals(from.id) && o.overlaps(d.start, d.end))
                    throw Problem.conflict(from.name + " already has a delegation in these dates (to " + store.users.get(o.toId).name + "). Cancel it first.");
            if (Delegations.wouldLoop(d, store.delegations.values()))
                throw Problem.conflict("That would make a circle: " + to.name + " already delegates (directly or through others) back to " + from.name + " in these dates.");
            d.id = store.nextId("G", 1);
            d.createdAt = Clock.millis();
            store.delegations.put(d.id, d);
            // work that is already waiting moves too
            LocalDate today = Clock.today();
            if (d.covers(today, null)) for (Task k : store.tasks.values()) {
                if (!k.state.open() || !k.assignees.contains(from.id)) continue;
                Document doc = store.docs.get(k.docId);
                if (doc == null || !d.covers(today, doc.type) || doc.authorId.equals(to.id) || k.assignees.contains(to.id)) continue;
                k.assignees.add(to.id);
                k.via.put(to.id, "delegate of " + from.id);
                store.notify(to.id, doc.number + " is waiting for you (for " + from.name + ")", "#/doc/" + doc.id);
            }
            store.notify(to.id, from.name + " asked you to approve for them from " + d.start + " to " + d.end, "#/delegations");
            store.log(u.id, "delegation.add", d.id, from.username + " -> " + to.username + " " + d.start + ".." + d.end);
            store.save("delegations", "tasks", "notes", "audit");
            return d;
        }
    }

    public Delegation cancelDelegation(User u, String id) {
        synchronized (store) {
            Delegation d = store.delegations.get(id);
            if (d == null || !(d.fromId.equals(u.id) || u.admin())) throw Problem.notFound("Delegation");
            if (d.cancelled) return d;
            d.cancelled = true;
            store.log(u.id, "delegation.cancel", d.id, null);
            store.save("delegations", "audit");
            return d;
        }
    }

    static String date(String v, String what) {
        try { return LocalDate.parse(v == null ? "" : v.trim()).toString(); } catch (Exception e) { throw Problem.bad("Give a valid " + what + " date."); }
    }

    // ================================================================== ledger check

    public Map<String, Object> checkLedger() {
        synchronized (store) {
            Ledger.Check c = Ledger.verify(store.ledger, store.secret());
            return Json.obj("ok", c.ok(), "checked", c.checked(), "brokenAt", c.brokenAt(), "reason", c.reason(),
                    "last", store.ledger.isEmpty() ? null : store.ledger.get(store.ledger.size() - 1).hash());
        }
    }

    public BusinessCalendar calendar() { return store.calendar(); }
}
