package com.cecil.pipeline.service;

import com.cecil.pipeline.model.Account;
import com.cecil.pipeline.model.Activity;
import com.cecil.pipeline.model.Deal;
import com.cecil.pipeline.model.Enums;
import com.cecil.pipeline.model.Enums.ActivityType;
import com.cecil.pipeline.model.User;
import com.cecil.pipeline.store.DataStore;
import com.cecil.pipeline.util.Json;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Calls, emails, meetings and to-do tasks. Finishing one counts as "working on" its deal. */
public class ActivityService {

    private final DataStore store;

    public ActivityService(DataStore store) {
        this.store = store;
    }

    /** JSON for one activity plus its deal and account names. Call while holding the store lock. */
    static Map<String, Object> view(DataStore store, Activity a, LocalDate today) {
        Map<String, Object> m = a.toMap();
        Deal d = a.dealId == null ? null : store.deal(a.dealId);
        Account acc = d == null ? null : store.account(d.accountId);
        User o = store.user(a.owner);
        m.put("dealTitle", d == null ? null : d.title);
        m.put("accountName", acc == null ? null : acc.name);
        m.put("ownerName", o == null ? a.owner : o.displayName);
        m.put("overdue", a.isOverdue(today));
        m.put("dueToday", !a.done && today.equals(a.dueDate));
        return m;
    }

    /**
     * status: open (default) | done | overdue | today | upcoming | all.
     * owner: a username, "me", or blank for everyone.
     */
    public List<Map<String, Object>> list(User me, String owner, String dealId, String status) {
        synchronized (store) {
            LocalDate today = LocalDate.now();
            String st = status == null || status.isBlank() ? "open" : status;
            String who = "me".equals(owner) ? me.username : owner;
            List<Activity> found = new ArrayList<>();
            for (Activity a : store.activities) {
                if (who != null && !who.isBlank() && !who.equalsIgnoreCase(a.owner)) continue;
                if (dealId != null && !dealId.isBlank() && !dealId.equals(a.dealId)) continue;
                boolean keep = switch (st) {
                    case "done" -> a.done;
                    case "overdue" -> a.isOverdue(today);
                    case "today" -> !a.done && today.equals(a.dueDate);
                    case "upcoming" -> !a.done && (a.dueDate == null || a.dueDate.isAfter(today));
                    case "all" -> true;
                    default -> !a.done;
                };
                if (keep) found.add(a);
            }
            if ("done".equals(st)) {
                found.sort(Comparator.comparing((Activity a) -> a.doneAt == null ? "" : a.doneAt).reversed());
            } else {
                found.sort(Comparator.comparing((Activity a) -> a.done)
                    .thenComparing(a -> a.dueDate == null ? LocalDate.MAX : a.dueDate));
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Activity a : found) out.add(view(store, a, today));
            return out;
        }
    }

    public Activity create(User me, Map<String, Object> body) {
        synchronized (store) {
            Activity a = new Activity();
            a.id = store.nextId("T");
            a.createdAt = Instant.now().toString();
            a.createdBy = me.username;
            a.owner = me.username;
            String dealId = Json.str(body, "dealId");
            if (dealId != null && !dealId.isBlank()) {
                Deal d = store.deal(dealId);
                if (d == null) throw ServiceException.bad("Unknown deal");
                a.dealId = d.id;
            }
            apply(me, a, body, true);
            if (Json.bool(body, "done", false)) markDone(a, Json.str(body, "outcome", ""));
            store.activities.add(a);
            store.save();
            return a;
        }
    }

    public Activity update(User me, String id, Map<String, Object> body) {
        synchronized (store) {
            Activity a = find(me, id);
            apply(me, a, body, false);
            store.save();
            return a;
        }
    }

    /** Marks an activity as done (or not done when done=false). */
    public Activity complete(User me, String id, boolean done, String outcome) {
        synchronized (store) {
            Activity a = find(me, id);
            if (done) {
                markDone(a, outcome);
            } else {
                a.done = false;
                a.doneAt = null;
            }
            store.save();
            return a;
        }
    }

    public void delete(User me, String id) {
        synchronized (store) {
            Activity a = find(me, id);
            store.activities.remove(a);
            store.save();
        }
    }

    private void markDone(Activity a, String outcome) {
        a.done = true;
        a.doneAt = Instant.now().toString();
        if (outcome != null && !outcome.isBlank()) a.outcome = AccountService.limit(outcome, 1000, "Outcome");
        if (a.dealId != null) {
            Deal d = store.deal(a.dealId);
            if (d != null) d.lastActivityAt = a.doneAt;
        }
    }

    private Activity find(User me, String id) {
        Activity a = store.activity(id);
        if (a == null) throw ServiceException.notFound("Activity");
        Deal d = a.dealId == null ? null : store.deal(a.dealId);
        boolean allowed = me.canEditAll() || me.username.equals(a.owner) || me.username.equals(a.createdBy)
            || (d != null && me.username.equals(d.owner));
        if (!allowed) throw ServiceException.forbidden("This activity belongs to someone else");
        return a;
    }

    private void apply(User me, Activity a, Map<String, Object> body, boolean creating) {
        if (creating || body.containsKey("subject")) {
            String s = Json.str(body, "subject", "").trim();
            if (s.isEmpty()) throw ServiceException.bad("Write what the activity is about");
            a.subject = AccountService.limit(s, 160, "Subject");
        }
        if (body.containsKey("type")) a.type = Enums.parse(ActivityType.class, Json.str(body, "type"), ActivityType.TASK);
        if (body.containsKey("notes")) a.notes = AccountService.limit(Json.str(body, "notes", ""), 2000, "Notes");
        if (body.containsKey("outcome")) a.outcome = AccountService.limit(Json.str(body, "outcome", ""), 1000, "Outcome");
        if (body.containsKey("dueDate")) {
            String due = Json.str(body, "dueDate");
            if (due == null || due.isBlank()) {
                a.dueDate = null;
            } else {
                try {
                    a.dueDate = LocalDate.parse(due.trim());
                } catch (DateTimeParseException e) {
                    throw ServiceException.bad("Due date must look like 2026-12-31");
                }
            }
        }
        if (body.containsKey("owner")) {
            String o = Json.str(body, "owner");
            if (o != null && !o.isBlank()) {
                User u = store.user(o);
                if (u == null || !u.active) throw ServiceException.bad("Unknown person");
                a.owner = u.username;
            }
        }
    }
}
