package com.cecil.sambandh.service;

import com.cecil.sambandh.model.Customer;
import com.cecil.sambandh.model.FollowUp;
import com.cecil.sambandh.model.Interaction;
import com.cecil.sambandh.model.Order;
import com.cecil.sambandh.model.Role;
import com.cecil.sambandh.model.Stage;
import com.cecil.sambandh.model.Ticket;
import com.cecil.sambandh.model.User;
import com.cecil.sambandh.store.DataStore;
import com.cecil.sambandh.util.Clock;
import com.cecil.sambandh.util.Json;
import com.cecil.sambandh.util.Money;
import com.cecil.sambandh.util.Text;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Everything that happens with a customer: conversations (the timeline), follow-ups, orders and support tickets.
 * Consent is enforced here: no outgoing calls to people on Do-Not-Call, no outgoing e-mails without consent.
 */
public class ActivityService {
    public static final List<String> PRIORITIES = List.of("HIGH", "MEDIUM", "LOW");
    public static final List<String> ORDER_STATUSES = List.of("PAID", "PENDING", "REFUNDED");
    public static final List<String> TICKET_STATUSES = List.of("OPEN", "IN_PROGRESS", "RESOLVED");

    private final DataStore store;
    private final CustomerService customers;

    public ActivityService(DataStore store, CustomerService customers) {
        this.store = store;
        this.customers = customers;
    }

    // ------------------------------------------------------------------ conversations

    public Interaction logInteraction(User actor, String customerId, Map<String, Object> b) {
        synchronized (store) {
            Customer c = customers.findWritable(actor, customerId);
            String type = Json.str(b, "type", "").toUpperCase();
            if (!Interaction.TYPES.contains(type)) throw Problem.bad("Choose what kind of contact it was.");
            String direction = "NOTE".equals(type) ? null : Json.str(b, "direction", "").toUpperCase();
            if (direction != null && !direction.equals("IN") && !direction.equals("OUT"))
                throw Problem.bad("Say who started it: the customer (incoming) or us (outgoing).");
            String summary = Text.clean(Json.str(b, "summary"));
            if (summary == null) throw Problem.bad("Write a short summary of what was said.");
            if (summary.length() > 1000) throw Problem.bad("Summary is too long (1000 characters max).");
            String outcome = Json.str(b, "outcome", "NEUTRAL").toUpperCase();
            if (!Interaction.OUTCOMES.contains(outcome)) throw Problem.bad("Unknown outcome.");
            long minutes = Json.num(b, "minutes", 0);
            if (minutes < 0 || minutes > 600) throw Problem.bad("Minutes must be between 0 and 600.");
            long at = parseWhen(Json.str(b, "at"));

            // consent rules
            if ("OUT".equals(direction) && "CALL".equals(type) && c.doNotCall)
                throw Problem.conflict(c.name + " is on the Do-Not-Call list. Do not phone them – use e-mail if they agreed, "
                        + "or log it as incoming if they called you.");
            if ("OUT".equals(direction) && "EMAIL".equals(type) && !c.consentEmail)
                throw Problem.conflict(c.name + " has not agreed to receive e-mails. Ask first, then tick “Agreed to e-mails”.");
            if ("OUT".equals(direction) && "EMAIL".equals(type) && c.email == null)
                throw Problem.bad("This customer has no e-mail address saved.");
            if ("OUT".equals(direction) && ("CALL".equals(type) || "WHATSAPP".equals(type)) && c.phone == null)
                throw Problem.bad("This customer has no phone number saved.");

            Interaction i = new Interaction();
            i.id = store.nextId("I", 5001);
            i.customerId = c.id;
            i.type = type;
            i.direction = direction;
            i.summary = summary;
            i.outcome = outcome;
            i.minutes = (int) minutes;
            i.at = at;
            i.byUserId = actor.id;
            store.interactions.put(i.id, i);
            // a first real conversation turns a lead into a prospect when it went well
            if (c.stage == Stage.LEAD && !"NOTE".equals(type) && "POSITIVE".equals(outcome)) {
                c.stage = Stage.PROSPECT;
                store.log(actor.id, "stage.auto", c.id, "Lead → Prospect (good conversation)");
            }
            c.updatedAt = Clock.millis();
            store.log(actor.id, "interaction.add", c.id, type.toLowerCase() + (direction == null ? "" : " " + direction.toLowerCase()) + ", " + outcome.toLowerCase());
            store.save("interactions", "customers", "audit");
            return i;
        }
    }

    /** "2026-11-07T10:30" (local time) -> epoch millis. Empty = now. Not in the future, not before 2000. */
    static long parseWhen(String s) {
        if (s == null || s.isBlank()) return Clock.millis();
        try {
            long t = LocalDateTime.parse(s.trim()).atZone(Clock.ZONE).toInstant().toEpochMilli();
            if (t > Clock.millis() + 5 * 60_000) throw Problem.bad("The time cannot be in the future.");
            if (t < 946_684_800_000L) throw Problem.bad("The time is too far in the past.");
            return t;
        } catch (DateTimeParseException e) {
            throw Problem.bad("Time should look like 2026-11-07T10:30.");
        }
    }

    // ------------------------------------------------------------------ follow-ups

    public FollowUp addFollowUp(User actor, String customerId, Map<String, Object> b) {
        synchronized (store) {
            Customer c = customers.findWritable(actor, customerId);
            String title = Text.clean(Json.str(b, "title"));
            if (title == null) throw Problem.bad("What needs to be done?");
            if (title.length() > 120) throw Problem.bad("Keep it short (120 characters max).");
            LocalDate due = parseDate(Json.str(b, "due"), "Due date");
            if (due.isBefore(Clock.today())) throw Problem.bad("The due date cannot be in the past.");
            if (due.isAfter(Clock.today().plusYears(2))) throw Problem.bad("The due date is too far away.");
            String priority = Json.str(b, "priority", "MEDIUM").toUpperCase();
            if (!PRIORITIES.contains(priority)) throw Problem.bad("Unknown priority.");
            String assignee = Json.str(b, "assigneeId");
            if (assignee == null || assignee.isBlank()) assignee = actor.id;
            if (!assignee.equals(actor.id) && !actor.role.atLeast(Role.MANAGER))
                throw Problem.forbidden("Only a manager can give a follow-up to someone else.");
            User a = store.users.get(assignee);
            if (a == null || !a.active) throw Problem.bad("Choose an active staff member.");
            FollowUp f = new FollowUp();
            f.id = store.nextId("F", 3001);
            f.customerId = c.id;
            f.title = title;
            f.due = due;
            f.priority = priority;
            f.assigneeId = assignee;
            f.createdAt = Clock.millis();
            f.createdBy = actor.id;
            store.followUps.put(f.id, f);
            store.log(actor.id, "followup.add", c.id, title + " (due " + due + ", " + a.name + ")");
            store.save("followups", "audit");
            return f;
        }
    }

    public FollowUp setFollowUpDone(User actor, String id, boolean done) {
        synchronized (store) {
            FollowUp f = store.followUps.get(id);
            if (f == null) throw Problem.notFound("Follow-up");
            Customer c = customers.find(actor, f.customerId);   // not visible -> not found
            if (c.erased) throw Problem.conflict("This customer's data was erased.");
            boolean mine = actor.id.equals(f.assigneeId) || actor.id.equals(c.ownerId);
            if (!mine && !actor.role.atLeast(Role.MANAGER)) throw Problem.forbidden("This follow-up belongs to someone else.");
            f.done = done;
            f.doneAt = done ? Clock.millis() : 0;
            store.log(actor.id, done ? "followup.done" : "followup.reopen", c.id, f.title);
            store.save("followups", "audit");
            return f;
        }
    }

    /** Follow-up queue: overdue, today, upcoming (the agent's own, or everyone's for a manager with scope=all). */
    public Map<String, Object> followUpQueue(User actor, String scope) {
        LocalDate today = Clock.today();
        boolean all = "all".equals(scope) && actor.role.atLeast(Role.MANAGER);
        List<Map<String, Object>> overdue = new ArrayList<>(), dueToday = new ArrayList<>(), upcoming = new ArrayList<>(), done = new ArrayList<>();
        synchronized (store) {
            List<FollowUp> list = new ArrayList<>(store.followUps.values());
            list.sort(Comparator.comparing((FollowUp f) -> f.due).thenComparing(f -> PRIORITIES.indexOf(f.priority)));
            long weekAgo = Clock.millis() - 7 * 86_400_000L;
            for (FollowUp f : list) {
                Customer c = store.customers.get(f.customerId);
                if (!CustomerService.canSee(actor, c) || c.erased) continue;
                if (!all && !actor.id.equals(f.assigneeId)) continue;
                Map<String, Object> x = f.toJson();
                x.put("customerName", c.name);
                x.put("company", c.company);
                x.put("phone", c.phone);
                x.put("doNotCall", c.doNotCall);
                x.put("assigneeName", store.userName(f.assigneeId));
                if (f.done) { if (f.doneAt >= weekAgo) done.add(x); }
                else if (f.due.isBefore(today)) overdue.add(x);
                else if (f.due.equals(today)) dueToday.add(x);
                else upcoming.add(x);
            }
        }
        done.sort(Comparator.comparingLong((Map<String, Object> m) -> -((Number) m.get("doneAt")).longValue()));
        return Json.obj("overdue", overdue, "today", dueToday, "upcoming", upcoming, "doneThisWeek", done, "scope", all ? "all" : "mine");
    }

    // ------------------------------------------------------------------ orders

    public Order addOrder(User actor, String customerId, Map<String, Object> b) {
        synchronized (store) {
            Customer c = customers.findWritable(actor, customerId);
            String item = Text.clean(Json.str(b, "item"));
            if (item == null) throw Problem.bad("What did they buy?");
            if (item.length() > 80) throw Problem.bad("Item name is too long (80 characters max).");
            long paise = Money.parseRupees(Json.str(b, "amount"));
            if (paise <= 0) throw Problem.bad("Amount should be a number of rupees, like 2500 or 2,500.50.");
            if (paise > 10_00_00_000_00L) throw Problem.bad("Amount is too large (10 crore max).");
            LocalDate date = parseDate(Json.str(b, "date", Clock.today().toString()), "Date");
            if (date.isAfter(Clock.today())) throw Problem.bad("The order date cannot be in the future.");
            if (date.isBefore(LocalDate.of(2000, 1, 1))) throw Problem.bad("The order date is too old.");
            String status = Json.str(b, "status", "PAID").toUpperCase();
            if (!ORDER_STATUSES.contains(status)) throw Problem.bad("Unknown order status.");
            Order o = new Order();
            o.id = store.nextId("O", 7001);
            o.customerId = c.id;
            o.item = item;
            o.amountPaise = paise;
            o.date = date;
            o.status = status;
            o.byUserId = actor.id;
            store.orders.put(o.id, o);
            store.log(actor.id, "order.add", c.id, item + " " + Money.format(paise) + " (" + status.toLowerCase() + ")");
            afterOrderChange(actor, c);
            store.save("orders", "customers", "audit");
            return o;
        }
    }

    public Order setOrderStatus(User actor, String orderId, String status) {
        synchronized (store) {
            Order o = store.orders.get(orderId);
            if (o == null) throw Problem.notFound("Order");
            Customer c = customers.findWritable(actor, o.customerId);
            String st = status == null ? "" : status.toUpperCase();
            if (!ORDER_STATUSES.contains(st)) throw Problem.bad("Unknown order status.");
            if ("REFUNDED".equals(o.status)) throw Problem.conflict("A refunded order cannot be changed.");
            String before = o.status;
            o.status = st;
            store.log(actor.id, "order.status", c.id, o.id + " " + before.toLowerCase() + " → " + st.toLowerCase());
            afterOrderChange(actor, c);
            store.save("orders", "customers", "audit");
            return o;
        }
    }

    private void afterOrderChange(User actor, Customer c) {
        List<Order> mine = store.orders.values().stream().filter(x -> c.id.equals(x.customerId)).toList();
        Stage next = Insights.autoStage(c, mine, Clock.today());
        if (next != null && next != c.stage) {
            store.log(actor.id, "stage.auto", c.id, c.stage.label + " → " + next.label + " (purchases)");
            c.stage = next;
        }
        c.updatedAt = Clock.millis();
    }

    // ------------------------------------------------------------------ support tickets

    public Ticket openTicket(User actor, String customerId, Map<String, Object> b) {
        synchronized (store) {
            Customer c = customers.findWritable(actor, customerId);
            String subject = Text.clean(Json.str(b, "subject"));
            if (subject == null) throw Problem.bad("What is the problem?");
            if (subject.length() > 120) throw Problem.bad("Keep the subject short (120 characters max).");
            String priority = Json.str(b, "priority", "MEDIUM").toUpperCase();
            if (!PRIORITIES.contains(priority)) throw Problem.bad("Unknown priority.");
            Ticket t = new Ticket();
            t.id = store.nextId("T", 9001);
            t.customerId = c.id;
            t.subject = subject;
            t.priority = priority;
            t.createdAt = Clock.millis();
            t.byUserId = actor.id;
            store.tickets.put(t.id, t);
            store.log(actor.id, "ticket.open", c.id, t.id + " " + subject + " (" + priority.toLowerCase() + ")");
            store.save("tickets", "audit");
            return t;
        }
    }

    public Ticket setTicketStatus(User actor, String ticketId, String status) {
        synchronized (store) {
            Ticket t = store.tickets.get(ticketId);
            if (t == null) throw Problem.notFound("Ticket");
            Customer c = customers.findWritable(actor, t.customerId);
            String st = status == null ? "" : status.toUpperCase();
            if (!TICKET_STATUSES.contains(st)) throw Problem.bad("Unknown ticket status.");
            if (st.equals(t.status)) return t;
            String before = t.status;
            t.status = st;
            t.resolvedAt = "RESOLVED".equals(st) ? Clock.millis() : 0;
            store.log(actor.id, "ticket.status", c.id, t.id + " " + before.toLowerCase() + " → " + st.toLowerCase());
            store.save("tickets", "audit");
            return t;
        }
    }

    public List<Map<String, Object>> ticketList(User actor, String status) {
        long now = Clock.millis();
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (store) {
            for (Ticket t : store.tickets.values()) {
                Customer c = store.customers.get(t.customerId);
                if (!CustomerService.canSee(actor, c) || c.erased) continue;
                if ("open".equals(status) && !t.open()) continue;
                if ("breached".equals(status) && !t.breached(now)) continue;
                if ("resolved".equals(status) && t.open()) continue;
                Map<String, Object> x = t.toJson();
                x.put("customerName", c.name);
                x.put("ownerName", store.userName(c.ownerId));
                x.put("deadline", t.deadline());
                x.put("breached", t.breached(now));
                out.add(x);
            }
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> !(Boolean) m.get("breached"))
                .thenComparing(m -> "RESOLVED".equals(m.get("status")))
                .thenComparingLong(m -> ((Number) m.get("deadline")).longValue()));
        return out;
    }

    static LocalDate parseDate(String s, String what) {
        try {
            return LocalDate.parse(s == null ? "" : s.trim());
        } catch (DateTimeParseException e) {
            throw Problem.bad(what + " should look like 2026-11-07.");
        }
    }
}
