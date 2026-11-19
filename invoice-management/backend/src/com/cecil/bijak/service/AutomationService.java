package com.cecil.bijak.service;

import com.cecil.bijak.core.Dunning;
import com.cecil.bijak.core.Schedule;
import com.cecil.bijak.core.Tax;
import com.cecil.bijak.model.Customer;
import com.cecil.bijak.model.Invoice;
import com.cecil.bijak.model.Line;
import com.cecil.bijak.model.Recurring;
import com.cecil.bijak.model.Settings;
import com.cecil.bijak.model.Status;
import com.cecil.bijak.model.User;
import com.cecil.bijak.store.DataStore;
import com.cecil.bijak.util.Clock;
import com.cecil.bijak.util.Json;
import com.cecil.bijak.util.Text;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The automation - the part that makes invoicing happen without anybody clicking:
 *
 *   1. RECURRING INVOICES. Every schedule whose next date has come makes its invoice (and issues it, if
 *      "issue automatically" is on), then moves on to the next date. If Bijak was off for a while it catches up,
 *      oldest first, so the invoice numbers stay in date order.
 *   2. PAYMENT REMINDERS. Every open invoice is checked against the reminder ladder (core.Dunning) and the
 *      right e-mail goes to the outbox - once per level, never for an invoice on hold.
 *
 * tick() runs every 10 minutes in the background (App) and can be started by hand by the Owner/Accountant.
 * Running it twice in a row does nothing the second time (it is "idempotent").
 */
public class AutomationService {
    public static final int MAX_CATCH_UP = 12;   // at most 12 missed runs of one schedule in one go

    private final DataStore store;
    private final InvoiceService invoices;

    public AutomationService(DataStore store, InvoiceService invoices) {
        this.store = store;
        this.invoices = invoices;
    }

    // ------------------------------------------------------------------ the schedules

    public Recurring save(User u, String id, Map<String, Object> b) {
        Access.money(u);
        synchronized (store) {
            Recurring r;
            if (id == null) {
                r = new Recurring();
                r.createdBy = u.id;
                r.createdAt = Clock.millis();
            } else {
                r = store.recurring.get(id);
                if (r == null) throw Problem.notFound("Repeating invoice");
            }
            Customer c = store.customers.get(Json.str(b, "customerId", ""));
            if (c == null) throw Problem.bad("Pick a customer.");
            String name = Text.clean(Json.str(b, "name", ""), 80);
            if (Text.blank(name)) throw Problem.bad("Give it a name, like \"Monthly AMC\".");
            Recurring.Every every;
            try { every = Recurring.Every.valueOf(Json.str(b, "every", "MONTHLY")); } catch (IllegalArgumentException e) { throw Problem.bad("Pick how often."); }
            int anchor = (int) Json.num(b, "anchorDay", 1);
            String ap = Schedule.anchorProblem(every, anchor);
            if (ap != null) throw Problem.bad(ap);
            LocalDate start = InvoiceService.date(Json.str(b, "startDate", Clock.today().toString()), "Start date");
            if (id == null && start.isBefore(Clock.today())) throw Problem.bad("The first bill cannot be in the past.");
            String endS = Json.str(b, "endDate", "");
            LocalDate end = Text.blank(endS) ? null : InvoiceService.date(endS, "End date");
            if (end != null && end.isBefore(start)) throw Problem.bad("The end date is before the start date.");
            List<Line> lines = invoices.readLines(Json.list(b, "lines"));
            if (lines.isEmpty()) throw Problem.bad("Add at least one line.");
            r.name = name;
            r.customerId = c.id;
            boolean timingChanged = id == null || r.every != every || r.anchorDay != anchor || !start.toString().equals(r.startDate);
            r.every = every;
            r.anchorDay = anchor;
            r.startDate = start.toString();
            r.endDate = end == null ? null : end.toString();
            r.autoIssue = Json.bool(b, "autoIssue", true);
            r.active = Json.bool(b, "active", true);
            r.poNumber = Text.clean(Json.str(b, "poNumber", ""), 40);
            r.notes = Text.block(Json.str(b, "notes", ""), 600);
            r.lines = lines;
            if (timingChanged) {
                LocalDate from = start.isBefore(Clock.today()) ? Clock.today() : start;
                r.nextDate = Schedule.first(every, anchor, from).toString();
            }
            if (id == null) {
                r.id = store.nextId("Q", 1);
                store.recurring.put(r.id, r);
            }
            store.log(u.id, id == null ? "recurring.add" : "recurring.edit", r.id, r.name + " - " + Schedule.describe(every, anchor));
            store.save("recurring", "audit");
            return r;
        }
    }

    /** The next few dates of a schedule (shown on the page so people can see what will happen). */
    public static List<String> upcoming(Recurring r, int n) {
        List<String> out = new ArrayList<>();
        if (!r.active || r.nextDate == null) return out;
        LocalDate d = LocalDate.parse(r.nextDate);
        LocalDate end = r.endDate == null ? null : LocalDate.parse(r.endDate);
        for (int i = 0; i < n; i++) {
            if (end != null && d.isAfter(end)) break;
            out.add(d.toString());
            d = Schedule.step(r.every, r.anchorDay, d);
        }
        return out;
    }

    // ------------------------------------------------------------------ the tick

    public Map<String, Object> tick() {
        synchronized (store) {
            LocalDate today = Clock.today();
            List<String> made = runRecurring(today);
            int reminders = runReminders(today);
            store.meta.put("lastTick", Clock.millis());
            store.save("invoices", "recurring", "payments", "mails", "audit");
            return Json.obj("invoices", made, "reminders", reminders, "at", Clock.millis());
        }
    }

    /** Makes all the recurring invoices that are due, in date order (oldest first) across all schedules. */
    List<String> runRecurring(LocalDate today) {
        record Run(Recurring r, LocalDate date) { }
        List<Run> runs = new ArrayList<>();
        for (Recurring r : store.recurring.values()) {
            if (!r.active || r.nextDate == null) continue;
            LocalDate d = LocalDate.parse(r.nextDate);
            LocalDate end = r.endDate == null ? null : LocalDate.parse(r.endDate);
            int n = 0;
            while (!d.isAfter(today) && (end == null || !d.isAfter(end)) && n < MAX_CATCH_UP) {
                runs.add(new Run(r, d));
                d = Schedule.step(r.every, r.anchorDay, d);
                n++;
            }
        }
        runs.sort(Comparator.comparing(Run::date).thenComparing(x -> x.r().id));
        List<String> made = new ArrayList<>();
        for (Run run : runs) {
            Invoice inv = makeFrom(run.r(), run.date());
            if (inv != null) made.add(inv.number != null ? inv.number : inv.id);
        }
        for (Recurring r : store.recurring.values()) {   // a schedule past its end date stops
            if (r.active && r.endDate != null && r.nextDate != null && LocalDate.parse(r.nextDate).isAfter(LocalDate.parse(r.endDate))) {
                r.active = false;
                store.log(InvoiceService.SYSTEM, "recurring.end", r.id, r.name + " finished");
            }
        }
        return made;
    }

    Invoice makeFrom(Recurring r, LocalDate date) {
        Customer c = store.customers.get(r.customerId);
        r.nextDate = Schedule.step(r.every, r.anchorDay, date).toString();
        if (c == null || !c.active) {
            store.log(InvoiceService.SYSTEM, "recurring.skip", r.id, "customer inactive - " + date + " skipped");
            return null;
        }
        Invoice inv = new Invoice();
        inv.customerId = c.id;
        inv.placeOfSupply = c.state;
        inv.issueDate = date.toString();
        inv.poNumber = r.poNumber;
        inv.notes = (Text.blank(r.notes) ? "" : r.notes + "\n") + r.name + " - " + period(r, date);
        for (Line l : r.lines) inv.lines.add(l.copy());
        inv.createdBy = InvoiceService.SYSTEM;
        inv.createdAt = Clock.millis();
        inv.recurringId = r.id;
        invoices.preview(inv, c);
        inv.id = store.nextId("I", 1001);
        store.invoices.put(inv.id, inv);
        r.generated++;
        r.lastInvoiceId = inv.id;
        String held = null;
        if (r.autoIssue) {
            if (c.creditLimit > 0 && store.outstanding(c.id) - store.advance(c.id) + inv.total > c.creditLimit)
                held = "kept as draft: " + c.name + " would go above the credit limit";
            else if (inv.total > store.settings.approvalLimit && !creatorIsMoney(r))
                held = "waits for approval (over the approval limit)";
        }
        if (r.autoIssue && held == null) {
            invoices.book(inv, c, InvoiceService.SYSTEM, null);
        } else {
            if (held != null && held.startsWith("waits")) inv.status = Status.PENDING_APPROVAL;
            store.log(InvoiceService.SYSTEM, "recurring.draft", inv.id, r.name + " " + date + (held == null ? " - draft to check" : " - " + held));
        }
        return inv;
    }

    private boolean creatorIsMoney(Recurring r) {
        User u = store.users.get(r.createdBy);
        return u != null && u.role.money();
    }

    /** "November 2026", "Oct-Dec 2026", "week of 16 Nov 2026" - written on the invoice. */
    static String period(Recurring r, LocalDate d) {
        String[] m = { "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December" };
        return switch (r.every) {
            case WEEKLY -> "week of " + Letters.date(d.toString());
            case MONTHLY -> m[d.getMonthValue() - 1] + " " + d.getYear();
            case QUARTERLY -> {
                LocalDate e = d.plusMonths(2);
                yield m[d.getMonthValue() - 1].substring(0, 3) + "-" + m[e.getMonthValue() - 1].substring(0, 3) + " " + e.getYear();
            }
            case YEARLY -> "year from " + Letters.date(d.toString());
        };
    }

    /** Sends the reminders that are due today. Returns how many. */
    int runReminders(LocalDate today) {
        Settings s = store.settings;
        if (!s.remindersOn) return 0;
        int sent = 0;
        List<Invoice> open = new ArrayList<>();
        for (Invoice i : store.invoices.values()) if (!i.creditNote() && i.status.open() && !i.onHold && i.balance() > 0) open.add(i);
        open.sort(Comparator.comparing((Invoice i) -> i.dueDate).thenComparing(i -> i.number));
        for (Invoice i : open) {
            Customer c = store.customers.get(i.customerId);
            if (c == null || c.noReminders) continue;
            LocalDate due = LocalDate.parse(i.dueDate);
            // an invoice issued less than 3 days before its due date (for example "due on receipt") skips the early ones
            int already = Math.max(i.reminderLevel, Dunning.levelDue(s.reminderDays, due, LocalDate.parse(i.issueDate)));
            int level = Dunning.toSend(s.reminderDays, due, today, already);
            if (level == 0) { if (already > i.reminderLevel) i.reminderLevel = already; continue; }
            long late = Dunning.daysLate(due, today);
            long interest = level >= 4 ? Dunning.interest(i.balance(), s.interestBp, late) : 0;
            store.mail(Letters.reminder(s, c, i, level, late, interest));
            i.reminderLevel = level;
            i.lastReminderAt = Clock.millis();
            store.log(InvoiceService.SYSTEM, "reminder.send", i.id, i.number + " level " + level + " (" + Dunning.TITLES[level] + ")");
            sent++;
        }
        return sent;
    }

    /** A reminder sent by hand (the "Remind now" button): the next level, even if its day has not come. */
    public Map<String, Object> remindNow(User u, String invoiceId) {
        Access.writes(u);
        synchronized (store) {
            Invoice i = invoices.get(invoiceId);
            if (i.creditNote() || !i.status.open()) throw Problem.conflict("Only an unpaid invoice can get a reminder.");
            if (i.onHold) throw Problem.conflict("This invoice is on hold (disputed). Take it off hold first.");
            Customer c = store.customers.get(i.customerId);
            if (Text.blank(c.email)) throw Problem.bad(c.name + " has no e-mail address.");
            if (i.lastReminderAt > Clock.millis() - 24 * 3_600_000L) throw Problem.conflict("A reminder already went out in the last 24 hours.");
            int level = Math.min(5, Math.max(1, i.reminderLevel + 1));
            long late = Dunning.daysLate(LocalDate.parse(i.dueDate), Clock.today());
            if (late < 0 && level > 1) level = 1;
            long interest = level >= 4 ? Dunning.interest(i.balance(), store.settings.interestBp, late) : 0;
            var m = store.mail(Letters.reminder(store.settings, c, i, level, late, interest));
            m.by = u.id;
            i.reminderLevel = Math.max(i.reminderLevel, level);
            i.lastReminderAt = Clock.millis();
            store.log(u.id, "reminder.manual", i.id, i.number + " level " + level);
            store.save("invoices", "mails", "audit");
            return Json.obj("mailId", m.id, "level", level, "subject", m.subject);
        }
    }

    /** When the next automatic reminder will go (shown on the invoice page), or null. */
    public static Map<String, Object> nextReminder(Settings s, Invoice i, Customer c) {
        if (!s.remindersOn || i.creditNote() || !i.status.open() || i.onHold || (c != null && c.noReminders)) return null;
        int next = i.reminderLevel + 1;
        if (next > 5) return null;
        LocalDate due = LocalDate.parse(i.dueDate);
        LocalDate when = due.plusDays(s.reminderDays.get(next - 1));
        if (when.isBefore(Clock.today())) when = Clock.today();
        return Json.obj("level", next, "title", Dunning.TITLES[next], "date", when.toString());
    }

    /** Interest so far on an overdue invoice (0 when not late or switched off). */
    public static long interestNow(Settings s, Invoice i) {
        if (i.creditNote() || !i.status.open()) return 0;
        return Dunning.interest(i.balance(), s.interestBp, Dunning.daysLate(LocalDate.parse(i.dueDate), Clock.today()));
    }

    /** Tax of a schedule's lines (shown in the list). */
    public long scheduleTotal(Recurring r) {
        Customer c = store.customers.get(r.customerId);
        Invoice tmp = new Invoice();
        tmp.placeOfSupply = c == null ? store.settings.state : c.state;
        for (Line l : r.lines) tmp.lines.add(l.copy());
        Tax.compute(tmp, store.settings.state);
        return tmp.total;
    }
}
