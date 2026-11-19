package com.cecil.bijak.service;

import com.cecil.bijak.core.BankMatch;
import com.cecil.bijak.core.FinYear;
import com.cecil.bijak.core.Gstin;
import com.cecil.bijak.core.Schedule;
import com.cecil.bijak.core.Stats;
import com.cecil.bijak.core.Tax;
import com.cecil.bijak.model.Alloc;
import com.cecil.bijak.model.AuditEntry;
import com.cecil.bijak.model.BankLine;
import com.cecil.bijak.model.Customer;
import com.cecil.bijak.model.Invoice;
import com.cecil.bijak.model.Item;
import com.cecil.bijak.model.Line;
import com.cecil.bijak.model.Mail;
import com.cecil.bijak.model.Payment;
import com.cecil.bijak.model.Recurring;
import com.cecil.bijak.model.Role;
import com.cecil.bijak.model.Settings;
import com.cecil.bijak.model.Status;
import com.cecil.bijak.model.User;
import com.cecil.bijak.store.DataStore;
import com.cecil.bijak.util.Clock;
import com.cecil.bijak.util.Json;
import com.cecil.bijak.util.Money;
import com.cecil.bijak.util.Text;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Read-only pages: everything the website shows. Nothing here changes data. */
public class ViewService {
    private final DataStore store;
    private final PaymentService payments;
    private final AutomationService auto;

    public ViewService(DataStore store, PaymentService payments, AutomationService auto) {
        this.store = store;
        this.payments = payments;
        this.auto = auto;
    }

    // ------------------------------------------------------------------ small JSON pieces

    public static Map<String, Object> person(User u) {
        return Json.obj("id", u.id, "name", u.name, "initials", u.initials(), "role", u.role, "roleLabel", u.role.label(),
                "email", u.email, "active", u.active);
    }

    Map<String, Object> row(Invoice i) {
        LocalDate today = Clock.today();
        long late = i.dueDate == null ? 0 : ChronoUnit.DAYS.between(LocalDate.parse(i.dueDate), today);
        boolean overdue = !i.creditNote() && i.status.open() && late > 0 && i.balance() > 0;
        return Json.obj("id", i.id, "kind", i.kind, "number", i.number, "customerId", i.customerId, "buyerName", i.buyerName,
                "issueDate", i.issueDate, "dueDate", i.dueDate, "taxable", i.taxable, "tax", i.tax(), "total", i.total,
                "balance", i.balance(), "paid", i.paid, "credited", i.credited, "status", i.status, "overdue", overdue,
                "daysLate", late, "onHold", i.onHold, "reminderLevel", i.reminderLevel, "recurringId", i.recurringId,
                "createdBy", i.createdBy, "againstId", i.againstId, "lineCount", i.lines.size());
    }

    Map<String, Object> customerRow(Customer c) {
        long owes = 0, overdue = 0, oldest = 0;
        int open = 0;
        LocalDate today = Clock.today();
        for (Invoice i : store.invoicesOf(c.id)) {
            if (i.creditNote() || !i.status.open()) continue;
            open++;
            owes += i.balance();
            long late = ChronoUnit.DAYS.between(LocalDate.parse(i.dueDate), today);
            if (late > 0) { overdue += i.balance(); oldest = Math.max(oldest, late); }
        }
        Map<String, Object> m = c.toJson();
        m.put("stateName", Gstin.STATES.get(c.state));
        m.put("owes", owes);
        m.put("overdue", overdue);
        m.put("oldestLate", oldest);
        m.put("open", open);
        m.put("advance", store.advance(c.id));
        m.put("b2b", c.b2b());
        m.put("interState", !Tax.intraState(store.settings.state, c.state));
        return m;
    }

    Map<String, Object> payRow(Payment p) {
        Customer c = store.customers.get(p.customerId);
        return Json.obj("id", p.id, "number", p.number, "customerId", p.customerId, "customer", c == null ? "?" : c.name,
                "date", p.date, "amount", p.amount, "tds", p.tds, "mode", p.mode, "reference", p.reference,
                "applied", p.value() - p.unapplied(), "unapplied", p.unapplied(), "voided", p.voided, "voidReason", p.voidReason,
                "allocations", p.allocations, "notes", p.notes, "createdBy", p.createdBy, "bankLineId", p.bankLineId);
    }

    // ------------------------------------------------------------------ meta (who is who, sent once after sign-in)

    public Map<String, Object> meta(User me) {
        synchronized (store) {
            List<Object> people = new ArrayList<>();
            for (User u : store.users.values()) people.add(person(u));
            List<Object> customers = new ArrayList<>();
            for (Customer c : store.customers.values())
                customers.add(Json.obj("id", c.id, "name", c.name, "gstin", c.gstin, "state", c.state, "termsDays", c.termsDays,
                        "active", c.active, "tdsBp", c.tdsBp));
            List<Object> items = new ArrayList<>();
            for (Item i : store.items.values()) items.add(i.toJson());
            Settings s = store.settings;
            return Json.obj("people", people, "customers", customers, "items", items, "states", Gstin.STATES,
                    "modes", Payment.MODES, "rates", Tax.RATES,
                    "company", Json.obj("name", s.companyName, "gstin", s.gstin, "state", s.state, "address", s.address,
                            "phone", s.phone, "email", s.email, "bankName", s.bankName, "bankAccount", s.bankAccount,
                            "ifsc", s.ifsc, "upiId", s.upiId, "terms", s.terms, "invoicePrefix", s.invoicePrefix,
                            "approvalLimit", s.approvalLimit, "interestBp", s.interestBp),
                    "today", Clock.today().toString(), "counts", counts(me));
        }
    }

    Map<String, Object> counts(User me) {
        int approvals = 0, drafts = 0, bank = 0, back = 0;
        for (Invoice i : store.invoices.values()) {
            if (i.status == Status.PENDING_APPROVAL && !i.createdBy.equals(me.id)) approvals++;
            if (i.status == Status.DRAFT && (i.createdBy.equals(me.id) || (InvoiceService.SYSTEM.equals(i.createdBy) && me.role.money()))) drafts++;
            if (i.status == Status.DRAFT && i.sentBackNote != null && i.createdBy.equals(me.id)) back++;
        }
        for (BankLine b : store.bank.values()) if (b.state == BankLine.State.NEW) bank++;
        return Json.obj("approvals", me.role.money() ? approvals : 0, "drafts", drafts, "bank", me.role.money() ? bank : 0, "sentBack", back);
    }

    // ------------------------------------------------------------------ dashboard

    public Map<String, Object> dashboard(User me) {
        synchronized (store) {
            LocalDate today = Clock.today();
            long owes = 0, overdue = 0;
            long[] buckets = new long[5];
            int[] bucketCount = new int[5];
            Map<String, long[]> debtors = new LinkedHashMap<>();   // customer -> {owes, overdue, oldest}
            for (Invoice i : store.invoices.values()) {
                if (i.creditNote() || !i.status.open() || i.balance() <= 0) continue;
                long late = ChronoUnit.DAYS.between(LocalDate.parse(i.dueDate), today);
                owes += i.balance();
                if (late > 0) overdue += i.balance();
                int b = Stats.bucket(late);
                buckets[b] += i.balance();
                bucketCount[b]++;
                long[] d = debtors.computeIfAbsent(i.customerId, k -> new long[3]);
                d[0] += i.balance();
                if (late > 0) { d[1] += i.balance(); d[2] = Math.max(d[2], late); }
            }
            long advance = 0;
            for (Customer c : store.customers.values()) advance += store.advance(c.id);

            // 12 months: invoiced (by invoice date, less credit notes) and collected (by payment date)
            YearMonth now = YearMonth.from(today);
            Map<YearMonth, long[]> months = new TreeMap<>();
            for (int k = 11; k >= 0; k--) months.put(now.minusMonths(k), new long[2]);
            long sales90 = 0;
            for (Invoice i : store.invoices.values()) {
                if (!i.status.booked()) continue;
                YearMonth ym = YearMonth.from(LocalDate.parse(i.issueDate));
                long[] m = months.get(ym);
                long v = i.creditNote() ? -i.total : i.total;
                if (m != null) m[0] += v;
                if (!LocalDate.parse(i.issueDate).isBefore(today.minusDays(90))) sales90 += v;
            }
            for (Payment p : store.payments.values()) {
                if (p.voided) continue;
                long[] m = months.get(YearMonth.from(LocalDate.parse(p.date)));
                if (m != null) m[1] += p.value();
            }
            List<Object> monthList = new ArrayList<>();
            for (var e : months.entrySet()) monthList.add(Json.obj("month", e.getKey().toString(), "invoiced", e.getValue()[0], "collected", e.getValue()[1]));
            long[] thisMonth = months.get(now);

            List<Map.Entry<String, long[]>> top = new ArrayList<>(debtors.entrySet());
            top.sort((a, b) -> Long.compare(b.getValue()[1] * 10 + b.getValue()[0], a.getValue()[1] * 10 + a.getValue()[0]));
            List<Object> topList = new ArrayList<>();
            for (int k = 0; k < Math.min(6, top.size()); k++) {
                var e = top.get(k);
                Customer c = store.customers.get(e.getKey());
                topList.add(Json.obj("customerId", c.id, "name", c.name, "owes", e.getValue()[0], "overdue", e.getValue()[1], "oldest", e.getValue()[2],
                        "limit", c.creditLimit));
            }

            // things that need this person
            List<Object> needs = new ArrayList<>();
            for (Invoice i : store.invoices.values()) {
                if (i.status == Status.PENDING_APPROVAL && me.role.money() && !i.createdBy.equals(me.id)) needs.add(Json.obj("kind", "approve", "invoice", row(i)));
                else if (i.status == Status.DRAFT && i.sentBackNote != null && i.createdBy.equals(me.id)) needs.add(Json.obj("kind", "sentBack", "invoice", row(i), "note", i.sentBackNote));
                else if (i.status == Status.DRAFT && InvoiceService.SYSTEM.equals(i.createdBy) && me.role.money()) needs.add(Json.obj("kind", "autoDraft", "invoice", row(i)));
                else if (i.onHold && i.status.open()) needs.add(Json.obj("kind", "hold", "invoice", row(i), "note", i.holdNote));
            }
            int bankNew = 0;
            for (BankLine b : store.bank.values()) if (b.state == BankLine.State.NEW) bankNew++;

            // the next repeating invoices (2 weeks)
            List<Object> soon = new ArrayList<>();
            for (Recurring r : store.recurring.values()) {
                for (String d : AutomationService.upcoming(r, 3)) {
                    if (LocalDate.parse(d).isAfter(today.plusDays(14))) break;
                    Customer c = store.customers.get(r.customerId);
                    soon.add(Json.obj("id", r.id, "name", r.name, "customer", c == null ? "?" : c.name, "date", d,
                            "total", auto.scheduleTotal(r), "autoIssue", r.autoIssue));
                }
            }
            soon.sort(Comparator.comparing(o -> Json.str(cast(o), "date")));

            List<Mail> mails = new ArrayList<>(store.mails.values());
            mails.sort(Comparator.comparingLong((Mail m) -> m.at).reversed());
            List<Object> recent = new ArrayList<>();
            for (int k = 0; k < Math.min(6, mails.size()); k++) recent.add(mailRow(mails.get(k), false));

            Long lastTick = store.meta.get("lastTick") instanceof Number n ? n.longValue() : null;
            return Json.obj("owes", owes, "overdue", overdue, "advance", advance,
                    "invoicedThisMonth", thisMonth[0], "collectedThisMonth", thisMonth[1],
                    "dso", sales90 > 0 ? Math.round(owes * 90.0 / sales90) : null,
                    "buckets", List.of(buckets[0], buckets[1], buckets[2], buckets[3], buckets[4]),
                    "bucketCount", List.of(bucketCount[0], bucketCount[1], bucketCount[2], bucketCount[3], bucketCount[4]),
                    "bucketNames", Stats.BUCKETS, "months", monthList, "debtors", topList, "needs", needs,
                    "bankNew", bankNew, "soon", soon, "recentMail", recent, "lastTick", lastTick, "counts", counts(me));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) { return (Map<String, Object>) o; }

    // ------------------------------------------------------------------ invoices

    public Map<String, Object> invoices(String status, String kind, String customerId, String q, String from, String to) {
        synchronized (store) {
            List<Invoice> list = new ArrayList<>();
            String key = Text.key(q);
            for (Invoice i : store.invoices.values()) {
                if (kind != null && !kind.isBlank() && !kind.equals(i.kind)) continue;
                if (customerId != null && !customerId.isBlank() && !customerId.equals(i.customerId)) continue;
                if (from != null && !from.isBlank() && i.issueDate.compareTo(from) < 0) continue;
                if (to != null && !to.isBlank() && i.issueDate.compareTo(to) > 0) continue;
                if (status != null && !status.isBlank() && !"ALL".equals(status)) {
                    Map<String, Object> r = row(i);
                    boolean ok = switch (status) {
                        case "OPEN" -> i.status.open();
                        case "OVERDUE" -> Boolean.TRUE.equals(r.get("overdue"));
                        case "DRAFTS" -> i.status == Status.DRAFT || i.status == Status.PENDING_APPROVAL;
                        case "HOLD" -> i.onHold && i.status.open();
                        default -> i.status.name().equals(status);
                    };
                    if (!ok) continue;
                }
                if (!key.isEmpty()) {
                    String hay = Text.key((i.number == null ? "" : i.number) + " " + i.buyerName + " " + i.poNumber + " " + i.id + " "
                            + Money.format(i.total) + " " + (i.total / 100));
                    if (!hay.contains(key)) continue;
                }
                list.add(i);
            }
            list.sort((a, b) -> {
                int c = b.issueDate.compareTo(a.issueDate);
                if (c != 0) return c;
                return Long.compare(b.createdAt, a.createdAt);
            });
            long total = 0, balance = 0;
            List<Object> rows = new ArrayList<>();
            for (Invoice i : list) {
                rows.add(row(i));
                if (i.status.booked()) { total += i.creditNote() ? -i.total : i.total; balance += i.creditNote() ? 0 : i.balance(); }
            }
            return Json.obj("rows", rows, "count", rows.size(), "total", total, "balance", balance);
        }
    }

    public Map<String, Object> invoice(User me, String id) {
        synchronized (store) {
            Invoice i = store.invoices.get(id);
            if (i == null) throw Problem.notFound("Invoice");
            Customer c = store.customers.get(i.customerId);
            Map<String, Object> m = i.toJson();
            m.putAll(row(i));
            m.put("words", Money.words(i.total));
            m.put("intra", Tax.intraState(store.settings.state, i.placeOfSupply));
            m.put("posName", Gstin.STATES.get(i.placeOfSupply));
            List<Object> rates = new ArrayList<>();
            for (var e : Tax.byRate(i.lines).entrySet())
                rates.add(Json.obj("rate", e.getKey(), "taxable", e.getValue()[0], "cgst", e.getValue()[1], "sgst", e.getValue()[2], "igst", e.getValue()[3]));
            m.put("byRate", rates);
            m.put("customer", c == null ? null : customerRow(c));
            m.put("intact", i.fingerprint == null ? null : i.fingerprint.equals(InvoiceService.fingerprint(i)));
            m.put("interest", AutomationService.interestNow(store.settings, i));
            m.put("nextReminder", AutomationService.nextReminder(store.settings, i, c));

            // money against it
            List<Object> pays = new ArrayList<>();
            for (Payment p : store.payments.values())
                for (Alloc a : p.allocations)
                    if (a.invoiceId.equals(i.id)) pays.add(Json.obj("paymentId", p.id, "number", p.number, "date", p.date, "mode", p.mode,
                            "reference", p.reference, "amount", a.amount, "voided", p.voided, "tds", p.tds));
            m.put("payments", pays);
            List<Object> cns = new ArrayList<>();
            for (Invoice x : store.invoices.values()) {
                if (!x.creditNote() || !x.status.booked()) continue;
                if (i.id.equals(x.againstId)) cns.add(Json.obj("id", x.id, "number", x.number, "date", x.issueDate, "total", x.total, "reason", x.reason,
                        "applied", sumTo(x, i.id), "self", true));
                else { long a = sumTo(x, i.id); if (a > 0) cns.add(Json.obj("id", x.id, "number", x.number, "date", x.issueDate, "total", x.total, "reason", x.reason, "applied", a, "self", false)); }
            }
            m.put("creditNotes", cns);
            if (i.creditNote()) {
                Invoice against = store.invoices.get(i.againstId);
                m.put("against", against == null ? null : row(against));
                List<Object> used = new ArrayList<>();
                for (Alloc a : i.applied) {
                    Invoice t = store.invoices.get(a.invoiceId);
                    used.add(Json.obj("invoiceId", a.invoiceId, "number", t == null ? "?" : t.number, "amount", a.amount, "at", a.at));
                }
                m.put("usedFor", used);
            } else if (i.status.booked()) {
                long[] done = new InvoiceService(store).creditedPerLine(i);
                List<Long> left = new ArrayList<>();
                for (int k = 0; k < i.lines.size(); k++) left.add(i.lines.get(k).taxable - done[k]);
                m.put("creditLeft", left);
            }
            List<Object> mails = new ArrayList<>();
            for (Mail x : store.mails.values()) if (i.id.equals(x.invoiceId)) mails.add(mailRow(x, true));
            m.put("mails", mails);
            List<Object> history = new ArrayList<>();
            for (AuditEntry a : store.audit) if (i.id.equals(a.target())) history.add(a.toJson());
            m.put("history", history);
            Recurring r = i.recurringId == null ? null : store.recurring.get(i.recurringId);
            m.put("recurringName", r == null ? null : r.name);
            boolean mine = i.createdBy.equals(me.id);
            m.put("can", Json.obj(
                    "edit", i.status == Status.DRAFT && me.role.writes() && (mine || me.role.money()),
                    "approve", i.status == Status.PENDING_APPROVAL && me.role.money() && !mine,
                    "cancel", !i.creditNote() && i.status.booked() && me.role.money() && i.paid == 0 && i.credited == 0,
                    "credit", !i.creditNote() && i.status.booked() && me.role.money(),
                    "pay", !i.creditNote() && i.status.open() && me.role.money(),
                    "remind", !i.creditNote() && i.status.open() && me.role.writes() && !i.onHold,
                    "hold", !i.creditNote() && i.status.open() && me.role.writes(),
                    "copy", !i.creditNote() && me.role.writes()));
            return m;
        }
    }

    private static long sumTo(Invoice cn, String invoiceId) {
        long s = 0;
        for (Alloc a : cn.applied) if (a.invoiceId.equals(invoiceId)) s += a.amount;
        return s;
    }

    Map<String, Object> mailRow(Mail m, boolean body) {
        Customer c = store.customers.get(m.customerId);
        Invoice i = m.invoiceId == null ? null : store.invoices.get(m.invoiceId);
        return Json.obj("id", m.id, "kind", m.kind, "level", m.level, "to", m.to, "customer", c == null ? "?" : c.name,
                "customerId", m.customerId, "invoiceId", m.invoiceId, "number", i == null ? null : i.number,
                "subject", m.subject, "body", body ? m.body : null, "at", m.at, "by", m.by);
    }

    // ------------------------------------------------------------------ customers

    public Map<String, Object> customers(String q) {
        synchronized (store) {
            String key = Text.key(q);
            List<Object> rows = new ArrayList<>();
            List<Customer> list = new ArrayList<>(store.customers.values());
            list.sort(Comparator.comparing(c -> Text.key(c.name)));
            for (Customer c : list) {
                if (!key.isEmpty() && !Text.key(c.name + " " + c.gstin + " " + c.contact + " " + c.email).contains(key)) continue;
                rows.add(customerRow(c));
            }
            return Json.obj("rows", rows);
        }
    }

    /** One customer, with the statement of account from `from` to `to` (default: this financial year). */
    public Map<String, Object> customer(String id, String from, String to) {
        synchronized (store) {
            Customer c = store.customers.get(id);
            if (c == null) throw Problem.notFound("Customer");
            LocalDate today = Clock.today();
            LocalDate f = Text.blank(from) ? FinYear.start(today) : InvoiceService.date(from, "From");
            LocalDate t = Text.blank(to) ? today : InvoiceService.date(to, "To");
            if (t.isBefore(f)) throw Problem.bad("'To' is before 'From'.");
            record E(String date, String kind, String id, String number, String text, long debit, long credit) { }
            List<E> all = new ArrayList<>();
            for (Invoice i : store.invoicesOf(c.id)) {
                if (!i.status.booked()) continue;
                if (i.creditNote()) all.add(new E(i.issueDate, "CN", i.id, i.number, "Credit note - " + i.reason, 0, i.total));
                else all.add(new E(i.issueDate, "INV", i.id, i.number, "Invoice" + (i.poNumber == null || i.poNumber.isBlank() ? "" : " (PO " + i.poNumber + ")"), i.total, 0));
            }
            for (Payment p : store.paymentsOf(c.id)) {
                if (p.voided) continue;
                all.add(new E(p.date, "RCT", p.id, p.number, "Payment " + p.mode + (p.reference == null || p.reference.isBlank() ? "" : " " + p.reference), 0, p.amount));
                if (p.tds > 0) all.add(new E(p.date, "TDS", p.id, p.number, "TDS deducted by customer", 0, p.tds));
            }
            all.sort(Comparator.comparing(E::date).thenComparing(e -> e.kind().equals("INV") ? 0 : 1).thenComparing(e -> e.number() == null ? "" : e.number()));
            long opening = 0, bal;
            List<Object> lines = new ArrayList<>();
            for (E e : all) if (LocalDate.parse(e.date()).isBefore(f)) opening += e.debit() - e.credit();
            bal = opening;
            long debit = 0, credit = 0;
            for (E e : all) {
                LocalDate d = LocalDate.parse(e.date());
                if (d.isBefore(f) || d.isAfter(t)) continue;
                bal += e.debit() - e.credit();
                debit += e.debit();
                credit += e.credit();
                lines.add(Json.obj("date", e.date(), "kind", e.kind(), "id", e.id(), "number", e.number(), "text", e.text(),
                        "debit", e.debit(), "credit", e.credit(), "balance", bal));
            }
            List<Object> open = new ArrayList<>();
            long interest = 0;
            for (Invoice i : store.invoicesOf(c.id)) {
                if (i.creditNote() || !i.status.open()) continue;
                Map<String, Object> r = row(i);
                long in = AutomationService.interestNow(store.settings, i);
                interest += in;
                r.put("interest", in);
                open.add(r);
            }
            open.sort(Comparator.comparing(o -> Json.str(cast(o), "dueDate")));
            List<Object> invs = new ArrayList<>();
            List<Invoice> mine = store.invoicesOf(c.id);
            mine.sort(Comparator.comparing((Invoice i) -> i.issueDate).reversed());
            for (Invoice i : mine) invs.add(row(i));
            List<Object> pays = new ArrayList<>();
            List<Payment> ps = store.paymentsOf(c.id);
            ps.sort(Comparator.comparing((Payment p) -> p.date).reversed());
            for (Payment p : ps) pays.add(payRow(p));

            // how quickly they pay: days from due date to the day the invoice was fully settled
            List<Long> lateDays = new ArrayList<>();
            int onTime = 0;
            for (Invoice i : mine) {
                if (i.creditNote() || i.status != Status.PAID) continue;
                LocalDate paidOn = paidOn(i);
                if (paidOn == null) continue;
                long late = ChronoUnit.DAYS.between(LocalDate.parse(i.dueDate), paidOn);
                lateDays.add(late);
                if (late <= 0) onTime++;
            }
            List<Object> recs = new ArrayList<>();
            for (Recurring r : store.recurring.values()) if (r.customerId.equals(c.id)) recs.add(recurringRow(r));
            Map<String, Object> out = customerRow(c);
            out.put("statement", Json.obj("from", f.toString(), "to", t.toString(), "opening", opening, "lines", lines,
                    "debit", debit, "credit", credit, "closing", bal));
            out.put("openInvoices", open);
            out.put("interest", interest);
            out.put("invoices", invs);
            out.put("payments", pays);
            out.put("recurring", recs);
            out.put("paidCount", lateDays.size());
            out.put("onTimePct", lateDays.isEmpty() ? null : Math.round(onTime * 100.0 / lateDays.size()));
            out.put("medianLate", Stats.median(lateDays));
            return out;
        }
    }

    /** The day an invoice was fully settled: the date of the last payment or credit note that went to it. */
    LocalDate paidOn(Invoice i) {
        LocalDate last = null;
        for (Payment p : store.payments.values()) {
            if (p.voided) continue;
            for (Alloc a : p.allocations) if (a.invoiceId.equals(i.id)) {
                LocalDate d = LocalDate.parse(p.date);
                // money paid in advance counts on the invoice date, not before
                if (d.isBefore(LocalDate.parse(i.issueDate))) d = LocalDate.parse(i.issueDate);
                if (last == null || d.isAfter(last)) last = d;
            }
        }
        for (Invoice x : store.invoices.values())
            if (x.creditNote() && x.status.booked()) for (Alloc a : x.applied) if (a.invoiceId.equals(i.id)) {
                LocalDate d = LocalDate.parse(x.issueDate);
                if (last == null || d.isAfter(last)) last = d;
            }
        return last;
    }

    public Map<String, Object> items() {
        synchronized (store) {
            List<Object> rows = new ArrayList<>();
            Map<String, long[]> sold = new LinkedHashMap<>();
            for (Invoice i : store.invoices.values()) {
                if (i.creditNote() || !i.status.booked()) continue;
                for (Line l : i.lines) if (l.itemId != null) { long[] a = sold.computeIfAbsent(l.itemId, k -> new long[2]); a[0] += l.qtyMilli; a[1] += l.taxable; }
            }
            for (Item it : store.items.values()) {
                Map<String, Object> m = it.toJson();
                long[] a = sold.getOrDefault(it.id, new long[2]);
                m.put("soldQty", a[0]);
                m.put("soldValue", a[1]);
                rows.add(m);
            }
            return Json.obj("rows", rows);
        }
    }

    // ------------------------------------------------------------------ payments, recurring, bank, outbox

    public Map<String, Object> payments(String q) {
        synchronized (store) {
            String key = Text.key(q);
            List<Payment> list = new ArrayList<>(store.payments.values());
            list.sort(Comparator.comparing((Payment p) -> p.date).thenComparing(p -> p.createdAt).reversed());
            List<Object> rows = new ArrayList<>();
            long total = 0;
            for (Payment p : list) {
                Map<String, Object> r = payRow(p);
                if (!key.isEmpty() && !Text.key(p.number + " " + r.get("customer") + " " + p.reference + " " + Money.format(p.amount)).contains(key)) continue;
                List<Object> nums = new ArrayList<>();
                for (Alloc a : p.allocations) { Invoice i = store.invoices.get(a.invoiceId); nums.add(Json.obj("invoiceId", a.invoiceId, "number", i == null ? "?" : i.number, "amount", a.amount)); }
                r.put("allocations", nums);
                rows.add(r);
                total += p.voided ? 0 : p.amount;
            }
            return Json.obj("rows", rows, "total", total);
        }
    }

    Map<String, Object> recurringRow(Recurring r) {
        Customer c = store.customers.get(r.customerId);
        Map<String, Object> m = r.toJson();
        m.put("customer", c == null ? "?" : c.name);
        m.put("describe", Schedule.describe(r.every, r.anchorDay));
        m.put("total", auto.scheduleTotal(r));
        m.put("upcoming", AutomationService.upcoming(r, 6));
        Invoice last = r.lastInvoiceId == null ? null : store.invoices.get(r.lastInvoiceId);
        m.put("last", last == null ? null : row(last));
        return m;
    }

    public Map<String, Object> recurring() {
        synchronized (store) {
            List<Object> rows = new ArrayList<>();
            List<Recurring> list = new ArrayList<>(store.recurring.values());
            list.sort(Comparator.comparing((Recurring r) -> !r.active).thenComparing(r -> r.nextDate == null ? "9" : r.nextDate));
            long monthly = 0;
            for (Recurring r : list) {
                rows.add(recurringRow(r));
                if (!r.active) continue;
                long t = auto.scheduleTotal(r);
                monthly += switch (r.every) { case WEEKLY -> t * 52 / 12; case MONTHLY -> t; case QUARTERLY -> t / 3; case YEARLY -> t / 12; };
            }
            return Json.obj("rows", rows, "monthly", monthly);
        }
    }

    public Map<String, Object> bank(String state) {
        synchronized (store) {
            List<BankLine> list = new ArrayList<>(store.bank.values());
            list.sort(Comparator.comparing((BankLine b) -> b.date).reversed());
            List<Object> rows = new ArrayList<>();
            int n = 0, m = 0, ig = 0;
            for (BankLine b : list) {
                switch (b.state) { case NEW -> n++; case MATCHED -> m++; case IGNORED -> ig++; }
                if (state != null && !state.isBlank() && !b.state.name().equals(state)) continue;
                Map<String, Object> r = b.toJson();
                if (b.state == BankLine.State.NEW) {
                    List<Object> sug = new ArrayList<>();
                    for (BankMatch.Suggestion s : payments.suggest(b)) {
                        Customer c = store.customers.get(s.customerId());
                        List<Object> inv = new ArrayList<>();
                        for (int k = 0; k < s.invoiceIds().size(); k++) {
                            Invoice i = store.invoices.get(s.invoiceIds().get(k));
                            inv.add(Json.obj("invoiceId", i.id, "number", i.number, "amount", s.amounts().get(k), "balance", i.balance(), "dueDate", i.dueDate));
                        }
                        sug.add(Json.obj("customerId", s.customerId(), "customer", c.name, "invoices", inv, "tds", s.tds(),
                                "score", s.score(), "label", s.label(), "why", s.why()));
                    }
                    r.put("suggestions", sug);
                } else if (b.paymentId != null) {
                    Payment p = store.payments.get(b.paymentId);
                    if (p != null) r.put("payment", payRow(p));
                }
                rows.add(r);
            }
            return Json.obj("rows", rows, "new", n, "matched", m, "ignored", ig);
        }
    }

    public Map<String, Object> outbox(String kind, String q) {
        synchronized (store) {
            List<Mail> list = new ArrayList<>(store.mails.values());
            list.sort(Comparator.comparingLong((Mail m) -> m.at).reversed());
            String key = Text.key(q);
            List<Object> rows = new ArrayList<>();
            Map<String, Integer> kinds = new TreeMap<>();
            for (Mail m : list) {
                kinds.merge(m.kind, 1, Integer::sum);
                if (kind != null && !kind.isBlank() && !kind.equals(m.kind)) continue;
                if (!key.isEmpty() && !Text.key(m.subject + " " + m.to).contains(key)) continue;
                if (rows.size() >= 300) continue;
                rows.add(mailRow(m, true));
            }
            return Json.obj("rows", rows, "kinds", kinds, "total", list.size());
        }
    }

    public Map<String, Object> activity(User me, int limit) {
        if (!me.role.money() && me.role != Role.AUDITOR) throw Problem.forbidden("Only the Owner, Accountant and Auditor see the activity log.");
        synchronized (store) {
            List<Object> rows = new ArrayList<>();
            for (int k = store.audit.size() - 1; k >= 0 && rows.size() < limit; k--) rows.add(store.audit.get(k).toJson());
            return Json.obj("rows", rows);
        }
    }

    // ------------------------------------------------------------------ reports

    public Map<String, Object> reports(String month) {
        synchronized (store) {
            LocalDate today = Clock.today();
            YearMonth ym;
            try { ym = Text.blank(month) ? YearMonth.from(today) : YearMonth.parse(month); } catch (Exception e) { throw Problem.bad("Month must look like 2026-11."); }

            // ageing per customer
            List<Object> ageing = new ArrayList<>();
            long[] tot = new long[5];
            for (Customer c : store.customers.values()) {
                long[] b = new long[5];
                long interest = 0;
                boolean any = false;
                for (Invoice i : store.invoicesOf(c.id)) {
                    if (i.creditNote() || !i.status.open() || i.balance() <= 0) continue;
                    any = true;
                    b[Stats.bucket(ChronoUnit.DAYS.between(LocalDate.parse(i.dueDate), today))] += i.balance();
                    interest += AutomationService.interestNow(store.settings, i);
                }
                if (!any) continue;
                for (int k = 0; k < 5; k++) tot[k] += b[k];
                ageing.add(Json.obj("customerId", c.id, "name", c.name, "buckets", List.of(b[0], b[1], b[2], b[3], b[4]),
                        "total", b[0] + b[1] + b[2] + b[3] + b[4], "interest", interest, "advance", store.advance(c.id)));
            }
            ageing.sort((a, b) -> Long.compare(Json.num(cast(b), "total", 0), Json.num(cast(a), "total", 0)));

            Map<String, Object> gst = gst(ym);

            // collection speed (paid invoices in the last 12 months)
            List<Long> late = new ArrayList<>();
            int onTime = 0;
            Map<String, List<Long>> perCustomer = new LinkedHashMap<>();
            for (Invoice i : store.invoices.values()) {
                if (i.creditNote() || i.status != Status.PAID) continue;
                if (LocalDate.parse(i.issueDate).isBefore(today.minusMonths(12))) continue;
                LocalDate p = paidOn(i);
                if (p == null) continue;
                long d = ChronoUnit.DAYS.between(LocalDate.parse(i.dueDate), p);
                late.add(d);
                if (d <= 0) onTime++;
                perCustomer.computeIfAbsent(i.customerId, k -> new ArrayList<>()).add(d);
            }
            List<Object> payers = new ArrayList<>();
            for (var e : perCustomer.entrySet()) {
                Customer c = store.customers.get(e.getKey());
                long ok = e.getValue().stream().filter(d -> d <= 0).count();
                payers.add(Json.obj("customerId", c.id, "name", c.name, "count", e.getValue().size(), "median", Stats.median(e.getValue()),
                        "onTimePct", Math.round(ok * 100.0 / e.getValue().size())));
            }
            payers.sort(Comparator.comparingDouble(o -> -((Number) cast(o).get("median")).doubleValue()));

            List<String> months = new ArrayList<>();
            for (int k = 0; k < 12; k++) months.add(YearMonth.from(today).minusMonths(k).toString());
            return Json.obj("ageing", ageing, "ageingTotal", List.of(tot[0], tot[1], tot[2], tot[3], tot[4]), "bucketNames", Stats.BUCKETS,
                    "gst", gst, "paidCount", late.size(), "onTimePct", late.isEmpty() ? null : Math.round(onTime * 100.0 / late.size()),
                    "medianLate", Stats.median(late), "p85Late", Stats.percentile(late, 85), "payers", payers, "months", months);
        }
    }

    /**
     * GST summary for one month, in the shape of GSTR-1 (the return of outward supplies):
     * B2B invoices (buyer has a GSTIN), B2C (no GSTIN), credit notes, the tax by rate and the HSN summary.
     */
    public Map<String, Object> gst(YearMonth ym) {
        List<Object> b2b = new ArrayList<>(), notes = new ArrayList<>();
        Map<String, long[]> b2c = new TreeMap<>();        // "state|rate" -> {taxable, cgst, sgst, igst}
        Map<Integer, long[]> rates = new TreeMap<>();
        Map<String, long[]> hsn = new TreeMap<>();        // hsn|rate -> {qty, taxable, cgst, sgst, igst}
        Map<String, String> hsnName = new LinkedHashMap<>();
        long[] sum = new long[5];                          // taxable, cgst, sgst, igst, total (credit notes taken off)
        int count = 0;
        for (Invoice i : store.invoices.values()) {
            if (!i.status.booked() || i.issueDate == null || !YearMonth.from(LocalDate.parse(i.issueDate)).equals(ym)) continue;
            int sign = i.creditNote() ? -1 : 1;
            count++;
            Map<String, Object> r = Json.obj("id", i.id, "number", i.number, "date", i.issueDate, "buyer", i.buyerName, "gstin", i.buyerGstin,
                    "pos", i.placeOfSupply, "taxable", i.taxable, "cgst", i.cgst, "sgst", i.sgst, "igst", i.igst, "total", i.total);
            if (i.creditNote()) {
                Invoice a = store.invoices.get(i.againstId);
                r.put("against", a == null ? null : a.number);
                notes.add(r);
            } else if (i.buyerGstin != null && !i.buyerGstin.isBlank()) b2b.add(r);
            for (Line l : i.lines) {
                long[] t = rates.computeIfAbsent(l.gstBp, k -> new long[4]);
                t[0] += sign * l.taxable; t[1] += sign * l.cgst; t[2] += sign * l.sgst; t[3] += sign * l.igst;
                String hk = l.hsn + "|" + l.gstBp;
                long[] h = hsn.computeIfAbsent(hk, k -> new long[5]);
                h[0] += sign * l.qtyMilli; h[1] += sign * l.taxable; h[2] += sign * l.cgst; h[3] += sign * l.sgst; h[4] += sign * l.igst;
                hsnName.putIfAbsent(hk, l.description + "|" + l.unit);
                if (!i.creditNote() && (i.buyerGstin == null || i.buyerGstin.isBlank())) {
                    long[] c = b2c.computeIfAbsent(i.placeOfSupply + "|" + l.gstBp, k -> new long[4]);
                    c[0] += l.taxable; c[1] += l.cgst; c[2] += l.sgst; c[3] += l.igst;
                }
            }
            sum[0] += sign * i.taxable; sum[1] += sign * i.cgst; sum[2] += sign * i.sgst; sum[3] += sign * i.igst; sum[4] += sign * i.total;
        }
        b2b.sort(Comparator.comparing(o -> Json.str(cast(o), "number")));
        notes.sort(Comparator.comparing(o -> Json.str(cast(o), "number")));
        List<Object> rateList = new ArrayList<>();
        for (var e : rates.entrySet()) rateList.add(Json.obj("rate", e.getKey(), "taxable", e.getValue()[0], "cgst", e.getValue()[1], "sgst", e.getValue()[2], "igst", e.getValue()[3]));
        List<Object> hsnList = new ArrayList<>();
        for (var e : hsn.entrySet()) {
            String[] k = e.getKey().split("\\|");
            String[] n = hsnName.get(e.getKey()).split("\\|", 2);
            long[] v = e.getValue();
            hsnList.add(Json.obj("hsn", k[0], "rate", Integer.parseInt(k[1]), "description", n[0], "unit", n.length > 1 ? n[1] : "",
                    "qtyMilli", v[0], "taxable", v[1], "cgst", v[2], "sgst", v[3], "igst", v[4]));
        }
        List<Object> b2cList = new ArrayList<>();
        for (var e : b2c.entrySet()) {
            String[] k = e.getKey().split("\\|");
            long[] v = e.getValue();
            b2cList.add(Json.obj("pos", k[0], "posName", Gstin.STATES.get(k[0]), "rate", Integer.parseInt(k[1]), "taxable", v[0], "cgst", v[1], "sgst", v[2], "igst", v[3]));
        }
        return Json.obj("month", ym.toString(), "count", count, "b2b", b2b, "b2c", b2cList, "creditNotes", notes, "rates", rateList,
                "hsn", hsnList, "taxable", sum[0], "cgst", sum[1], "sgst", sum[2], "igst", sum[3], "total", sum[4]);
    }
}
