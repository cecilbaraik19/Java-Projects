package com.cecil.pipeline.service;

import com.cecil.pipeline.insights.DealScorer;
import com.cecil.pipeline.model.Account;
import com.cecil.pipeline.model.Activity;
import com.cecil.pipeline.model.Contact;
import com.cecil.pipeline.model.Deal;
import com.cecil.pipeline.model.Enums;
import com.cecil.pipeline.model.Enums.LeadSource;
import com.cecil.pipeline.model.Enums.Priority;
import com.cecil.pipeline.model.Enums.Stage;
import com.cecil.pipeline.model.User;
import com.cecil.pipeline.store.DataStore;
import com.cecil.pipeline.util.Csv;
import com.cecil.pipeline.util.Json;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Creating, editing and moving deals through the pipeline. */
public class DealService {

    public static final long MAX_VALUE = 1_000_000_000_000L; // ₹1 lakh crore, a sanity limit
    /** A deal with no activity for this many days is "going cold". */
    public static final int STALE_DAYS = 14;

    private final DataStore store;

    public DealService(DataStore store) {
        this.store = store;
    }

    // ------------------------------------------------------------------ permissions

    public static boolean canEdit(User u, Deal d) {
        return u != null && (u.canEditAll() || u.username.equals(d.owner));
    }

    private static void requireEdit(User u, Deal d) {
        if (!canEdit(u, d)) throw ServiceException.forbidden("Only the deal owner or a manager can change this deal");
    }

    // ------------------------------------------------------------------ reading

    /** Query options for the deal list. All are optional. */
    public static class Filter {
        public String status;    // open | won | lost | closed | all  (default: all)
        public String stage;
        public String owner;
        public String accountId;
        public String q;
        public String priority;
        public String closing;   // this-month | next-30 | overdue
        public String health;    // HOT | WARM | COLD
        public String sort;      // value | close | score | updated | created | title
    }

    public List<Map<String, Object>> list(User me, Filter f) {
        synchronized (store) {
            LocalDate today = LocalDate.now();
            Stage stage = f.stage == null ? null : Enums.parse(Stage.class, f.stage, null);
            Priority prio = f.priority == null ? null : Enums.parse(Priority.class, f.priority, null);
            String needle = f.q == null ? "" : f.q.trim().toLowerCase(Locale.ROOT);
            String status = f.status == null ? "all" : f.status.toLowerCase(Locale.ROOT);

            List<Map<String, Object>> out = new ArrayList<>();
            for (Deal d : store.deals) {
                switch (status) {
                    case "open" -> { if (!d.isOpen()) continue; }
                    case "won" -> { if (d.stage != Stage.WON) continue; }
                    case "lost" -> { if (d.stage != Stage.LOST) continue; }
                    case "closed" -> { if (d.isOpen()) continue; }
                    default -> { }
                }
                if (stage != null && d.stage != stage) continue;
                if (prio != null && d.priority != prio) continue;
                if (f.owner != null && !f.owner.isBlank()) {
                    String owner = f.owner.equals("me") ? me.username : f.owner;
                    if (!owner.equalsIgnoreCase(d.owner)) continue;
                }
                if (f.accountId != null && !f.accountId.isBlank() && !f.accountId.equals(d.accountId)) continue;
                if (f.closing != null && !f.closing.isBlank()) {
                    if (!d.isOpen() || d.expectedClose == null) continue;
                    boolean ok = switch (f.closing) {
                        case "this-month" -> d.expectedClose.getYear() == today.getYear() && d.expectedClose.getMonth() == today.getMonth();
                        case "next-30" -> !d.expectedClose.isBefore(today) && !d.expectedClose.isAfter(today.plusDays(30));
                        case "overdue" -> d.expectedClose.isBefore(today);
                        default -> true;
                    };
                    if (!ok) continue;
                }
                Account a = store.account(d.accountId);
                if (!needle.isEmpty()) {
                    String hay = (d.title + " " + d.id + " " + (a == null ? "" : a.name)).toLowerCase(Locale.ROOT);
                    if (!hay.contains(needle)) continue;
                }
                Map<String, Object> v = view(me, d, today, false);
                if (f.health != null && !f.health.isBlank() && !f.health.equalsIgnoreCase(String.valueOf(v.get("healthLabel")))) continue;
                out.add(v);
            }
            sort(out, f.sort);
            return out;
        }
    }

    @SuppressWarnings("unchecked")
    private static void sort(List<Map<String, Object>> list, String by) {
        Comparator<Map<String, Object>> c = switch (by == null ? "" : by) {
            case "value" -> Comparator.comparing((Map<String, Object> m) -> (Long) m.get("value")).reversed();
            case "weighted" -> Comparator.comparing((Map<String, Object> m) -> (Long) m.get("weightedValue")).reversed();
            case "close" -> Comparator.comparing((Map<String, Object> m) -> m.get("expectedClose") == null ? "9999" : String.valueOf(m.get("expectedClose")));
            case "score" -> Comparator.comparing((Map<String, Object> m) -> (Integer) m.get("health")).reversed();
            case "created" -> Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("createdAt"))).reversed();
            case "title" -> Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("title")).toLowerCase(Locale.ROOT));
            default -> Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("updatedAt"))).reversed();
        };
        list.sort(c);
    }

    /** Full deal page: the deal plus account, contact, activities, health score and timeline. */
    public Map<String, Object> get(User me, String id) {
        synchronized (store) {
            Deal d = store.deal(id);
            if (d == null) throw ServiceException.notFound("Deal");
            return view(me, d, LocalDate.now(), true);
        }
    }

    /** Deal as JSON with handy calculated fields. Must be called while holding the store lock. */
    Map<String, Object> view(User me, Deal d, LocalDate today, boolean full) {
        Map<String, Object> m = d.toMap();
        Account a = store.account(d.accountId);
        Contact c = d.contactId == null ? null : store.contact(d.contactId);
        User owner = store.user(d.owner);
        List<Activity> acts = activitiesFor(d.id);
        DealScorer.Result score = DealScorer.score(d, acts, c, today);

        m.put("accountName", a == null ? "(deleted account)" : a.name);
        m.put("contactName", c == null ? null : c.name);
        m.put("ownerName", owner == null ? d.owner : owner.displayName);
        m.put("effectiveProbability", d.effectiveProbability());
        m.put("weightedValue", d.weightedValue());
        m.put("health", score.score());
        m.put("healthLabel", score.label());
        m.put("canEdit", canEdit(me, d));
        ZoneId zone = ZoneId.systemDefault();
        m.put("daysInStage", ChronoUnit.DAYS.between(d.stageEnteredAt().atZone(zone).toLocalDate(), today));
        String last = d.lastActivityAt != null ? d.lastActivityAt : d.createdAt;
        long idle = last == null ? 0 : ChronoUnit.DAYS.between(Instant.parse(last).atZone(zone).toLocalDate(), today);
        m.put("daysIdle", idle);
        m.put("stale", d.isOpen() && idle > STALE_DAYS);
        m.put("closeOverdue", d.isOpen() && d.expectedClose != null && d.expectedClose.isBefore(today));
        Activity next = acts.stream().filter(x -> !x.done && x.dueDate != null)
            .min(Comparator.comparing(x -> x.dueDate)).orElse(null);
        m.put("nextActivity", next == null ? null : Json.obj("id", next.id, "subject", next.subject, "type", next.type,
            "dueDate", next.dueDate.toString(), "overdue", next.isOverdue(today)));
        if (full) {
            m.put("account", a == null ? null : a.toMap());
            m.put("contact", c == null ? null : c.toMap());
            List<Object> al = new ArrayList<>();
            acts.stream().sorted(Comparator.comparing((Activity x) -> x.done)
                    .thenComparing(x -> x.dueDate == null ? LocalDate.MAX : x.dueDate))
                .forEach(x -> al.add(ActivityService.view(store, x, today)));
            m.put("activities", al);
            m.put("healthDetail", score.toMap());
            List<Object> contacts = new ArrayList<>();
            for (Contact x : store.contacts) if (x.accountId.equals(d.accountId)) contacts.add(Json.obj("id", x.id, "name", x.name));
            m.put("accountContacts", contacts);
            m.put("timeline", timeline(d, acts));
        }
        return m;
    }

    private List<Activity> activitiesFor(String dealId) {
        List<Activity> out = new ArrayList<>();
        for (Activity a : store.activities) if (dealId.equals(a.dealId)) out.add(a);
        return out;
    }

    /** Newest first list of everything that happened on the deal: stage moves, notes, finished activities. */
    private List<Object> timeline(Deal d, List<Activity> acts) {
        List<Map<String, Object>> t = new ArrayList<>();
        for (Deal.StageChange c : d.history) {
            t.add(Json.obj("kind", "stage", "at", c.at, "by", name(c.by),
                "text", c.from == null ? "Created in " + c.to.label : "Moved from " + c.from.label + " to " + c.to.label));
        }
        for (Deal.Note n : d.notes) t.add(Json.obj("kind", "note", "at", n.at, "by", name(n.by), "text", n.text, "id", n.id));
        for (Activity a : acts) {
            if (a.done && a.doneAt != null) {
                String txt = a.type.label + ": " + a.subject + (a.outcome == null || a.outcome.isBlank() ? "" : " - " + a.outcome);
                t.add(Json.obj("kind", "activity", "at", a.doneAt, "by", name(a.owner), "text", txt, "type", a.type));
            }
        }
        t.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("at"))).reversed());
        return new ArrayList<>(t);
    }

    private String name(String username) {
        User u = store.user(username);
        return u == null ? username : u.displayName;
    }

    // ------------------------------------------------------------------ writing

    public Deal create(User me, Map<String, Object> body) {
        synchronized (store) {
            Deal d = new Deal();
            d.id = store.nextId("D");
            String now = Instant.now().toString();
            d.createdAt = now;
            d.updatedAt = now;
            d.lastActivityAt = now;
            d.owner = me.username;
            apply(me, d, body, true);
            Deal.StageChange sc = new Deal.StageChange();
            sc.from = null; sc.to = d.stage; sc.at = now; sc.by = me.username;
            d.history.add(sc);
            if (d.stage.isClosed()) d.closedAt = now;
            store.deals.add(d);
            store.save();
            return d;
        }
    }

    public Deal update(User me, String id, Map<String, Object> body) {
        synchronized (store) {
            Deal d = store.deal(id);
            if (d == null) throw ServiceException.notFound("Deal");
            requireEdit(me, d);
            Stage before = d.stage;
            apply(me, d, body, false);
            // a stage sent inside a normal edit goes through the same rules as a move
            Stage wanted = body.containsKey("stage") ? Enums.parse(Stage.class, Json.str(body, "stage"), before) : before;
            if (wanted != before) changeStage(me, d, wanted, Json.str(body, "lostReason"));
            d.updatedAt = Instant.now().toString();
            store.save();
            return d;
        }
    }

    /** Moves a deal to another stage (used by the board drag-and-drop and the stage buttons). */
    public Deal moveStage(User me, String id, String stageName, String lostReason) {
        synchronized (store) {
            Deal d = store.deal(id);
            if (d == null) throw ServiceException.notFound("Deal");
            requireEdit(me, d);
            Stage to = Enums.parse(Stage.class, stageName, null);
            if (to == null) throw ServiceException.bad("Unknown stage");
            if (to != d.stage) {
                changeStage(me, d, to, lostReason);
                d.updatedAt = Instant.now().toString();
                store.save();
            }
            return d;
        }
    }

    private void changeStage(User me, Deal d, Stage to, String lostReason) {
        if (to == Stage.LOST) {
            String r = lostReason == null ? "" : lostReason.trim();
            if (r.isEmpty()) throw ServiceException.bad("Please give a reason why the deal was lost");
            d.lostReason = AccountService.limit(r, 200, "Lost reason");
        } else {
            d.lostReason = null;
        }
        String now = Instant.now().toString();
        Deal.StageChange sc = new Deal.StageChange();
        sc.from = d.stage; sc.to = to; sc.at = now; sc.by = me.username;
        d.history.add(sc);
        d.stage = to;
        d.probability = null; // go back to the default chance of the new stage
        d.closedAt = to.isClosed() ? now : null;
        d.lastActivityAt = now;
        if (to == Stage.WON && (d.expectedClose == null || d.expectedClose.isAfter(LocalDate.now()))) {
            d.expectedClose = LocalDate.now();
        }
    }

    private void apply(User me, Deal d, Map<String, Object> body, boolean creating) {
        if (creating || body.containsKey("title")) {
            String t = Json.str(body, "title", "").trim();
            if (t.isEmpty()) throw ServiceException.bad("Deal name is required");
            d.title = AccountService.limit(t, 120, "Deal name");
        }
        if (creating || body.containsKey("accountId")) {
            String acc = Json.str(body, "accountId");
            if (acc == null || store.account(acc) == null) throw ServiceException.bad("Choose the company for this deal");
            if (!acc.equals(d.accountId)) d.contactId = null;
            d.accountId = acc;
        }
        if (body.containsKey("contactId")) {
            String cid = Json.str(body, "contactId");
            if (cid == null || cid.isBlank()) {
                d.contactId = null;
            } else {
                Contact c = store.contact(cid);
                if (c == null || !c.accountId.equals(d.accountId)) throw ServiceException.bad("That contact does not work at this company");
                d.contactId = cid;
            }
        }
        if (creating || body.containsKey("value")) {
            long v = Json.lng(body, "value", -1);
            if (v < 0) throw ServiceException.bad("Deal value must be 0 or more");
            if (v > MAX_VALUE) throw ServiceException.bad("Deal value is too large");
            d.value = v;
        }
        if (creating && body.containsKey("stage")) {
            Stage s = Enums.parse(Stage.class, Json.str(body, "stage"), Stage.LEAD);
            if (s == Stage.LOST) throw ServiceException.bad("A new deal cannot start as lost");
            d.stage = s;
        }
        if (body.containsKey("probability")) {
            Integer p = Json.intOrNull(body, "probability");
            if (p != null && (p < 0 || p > 100)) throw ServiceException.bad("Probability must be between 0 and 100");
            d.probability = p;
        }
        if (body.containsKey("expectedClose")) {
            String ec = Json.str(body, "expectedClose");
            if (ec == null || ec.isBlank()) {
                d.expectedClose = null;
            } else {
                try {
                    d.expectedClose = LocalDate.parse(ec.trim());
                } catch (DateTimeParseException e) {
                    throw ServiceException.bad("Close date must look like 2026-12-31");
                }
            }
        }
        if (body.containsKey("source")) d.source = Enums.parse(LeadSource.class, Json.str(body, "source"), LeadSource.OTHER);
        if (body.containsKey("priority")) d.priority = Enums.parse(Priority.class, Json.str(body, "priority"), Priority.MEDIUM);
        if (body.containsKey("description")) d.description = AccountService.limit(Json.str(body, "description", ""), 4000, "Description");
        if (body.containsKey("owner")) {
            String o = Json.str(body, "owner");
            if (o != null && !o.isBlank() && !o.equalsIgnoreCase(d.owner)) {
                User nu = store.user(o);
                if (nu == null || !nu.active) throw ServiceException.bad("Unknown deal owner");
                if (!me.canEditAll() && !nu.username.equals(me.username)) {
                    throw ServiceException.forbidden("Only a manager can give a deal to someone else");
                }
                d.owner = nu.username;
            }
        }
    }

    public void delete(User me, String id) {
        synchronized (store) {
            Deal d = store.deal(id);
            if (d == null) throw ServiceException.notFound("Deal");
            requireEdit(me, d);
            store.deals.remove(d);
            store.activities.removeIf(a -> id.equals(a.dealId));
            store.save();
        }
    }

    public Deal.Note addNote(User me, String id, String text) {
        synchronized (store) {
            Deal d = store.deal(id);
            if (d == null) throw ServiceException.notFound("Deal");
            String t = text == null ? "" : text.trim();
            if (t.isEmpty()) throw ServiceException.bad("Write something in the note");
            Deal.Note n = new Deal.Note();
            n.id = store.nextId("N");
            n.text = AccountService.limit(t, 4000, "Note");
            n.at = Instant.now().toString();
            n.by = me.username;
            d.notes.add(n);
            d.lastActivityAt = n.at;
            d.updatedAt = n.at;
            store.save();
            return n;
        }
    }

    public void deleteNote(User me, String dealId, String noteId) {
        synchronized (store) {
            Deal d = store.deal(dealId);
            if (d == null) throw ServiceException.notFound("Deal");
            Deal.Note n = d.notes.stream().filter(x -> x.id.equals(noteId)).findFirst().orElse(null);
            if (n == null) throw ServiceException.notFound("Note");
            if (!me.canEditAll() && !me.username.equals(n.by)) throw ServiceException.forbidden("You can only delete your own notes");
            d.notes.remove(n);
            store.save();
        }
    }

    // ------------------------------------------------------------------ export

    public String exportCsv(User me, Filter f) {
        List<Map<String, Object>> rows = list(me, f);
        List<List<Object>> out = new ArrayList<>();
        for (Map<String, Object> m : rows) {
            out.add(List.of(
                m.get("id"), m.get("title"), m.get("accountName"), nz(m.get("contactName")), m.get("ownerName"),
                m.get("stage"), m.get("value"), m.get("effectiveProbability"), m.get("weightedValue"),
                nz(m.get("expectedClose")), m.get("source"), m.get("priority"), m.get("health"),
                nz(m.get("lostReason")), String.valueOf(m.get("createdAt")).substring(0, 10),
                m.get("closedAt") == null ? "" : String.valueOf(m.get("closedAt")).substring(0, 10)));
        }
        return Csv.build(List.of("ID", "Deal", "Company", "Contact", "Owner", "Stage", "Value (INR)", "Probability %",
            "Weighted (INR)", "Expected close", "Source", "Priority", "Health score", "Lost reason", "Created", "Closed"), out);
    }

    private static Object nz(Object o) { return o == null ? "" : o; }
}
