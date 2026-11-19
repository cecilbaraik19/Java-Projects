package com.cecil.bijak.service;

import com.cecil.bijak.core.FinYear;
import com.cecil.bijak.core.Gstin;
import com.cecil.bijak.core.Tax;
import com.cecil.bijak.model.Alloc;
import com.cecil.bijak.model.Customer;
import com.cecil.bijak.model.Invoice;
import com.cecil.bijak.model.Item;
import com.cecil.bijak.model.Line;
import com.cecil.bijak.model.Payment;
import com.cecil.bijak.model.Role;
import com.cecil.bijak.model.Settings;
import com.cecil.bijak.model.Status;
import com.cecil.bijak.model.User;
import com.cecil.bijak.store.DataStore;
import com.cecil.bijak.util.Clock;
import com.cecil.bijak.util.Json;
import com.cecil.bijak.util.Money;
import com.cecil.bijak.util.Passwords;
import com.cecil.bijak.util.Text;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Invoices and credit notes, from draft to paid.
 *
 *  - A draft has no number. The number is given at the moment of issue, from a counter per series and financial
 *    year, inside the store lock: numbers have no gaps and no repeats (a GST rule).
 *  - Invoices of one series must be in date order: an invoice cannot be dated before the last one issued.
 *  - Credit limit: if the customer would owe more than the limit, Sales is stopped; the Accountant or Owner
 *    may go on but must write why (kept on the invoice).
 *  - Approval: an invoice by Sales above the approval limit waits for the Accountant/Owner, who must be a
 *    different person (four eyes).
 *  - Issued invoices never change. Wrong amount -> credit note. Cancel only when nothing was paid or credited.
 */
public class InvoiceService {
    public static final String SYSTEM = "system";
    public static final int MAX_LINES = 40;

    private final DataStore store;

    public InvoiceService(DataStore store) { this.store = store; }

    // ------------------------------------------------------------------ drafts

    public Invoice create(User u, Map<String, Object> b) {
        Access.writes(u);
        synchronized (store) {
            Invoice inv = new Invoice();
            inv.createdBy = u.id;
            inv.createdAt = Clock.millis();
            fill(inv, b);
            inv.id = store.nextId("I", 1001);
            store.invoices.put(inv.id, inv);
            store.log(u.id, "invoice.draft", inv.id, inv.buyerName + " " + Money.format(inv.total));
            store.save("invoices", "audit");
            return inv;
        }
    }

    public Invoice update(User u, String id, Map<String, Object> b) {
        Access.writes(u);
        synchronized (store) {
            Invoice inv = editable(u, id);
            fill(inv, b);
            inv.sentBackNote = null;
            store.log(u.id, "invoice.edit", inv.id, Money.format(inv.total));
            store.save("invoices", "audit");
            return inv;
        }
    }

    public Map<String, Object> deleteDraft(User u, String id) {
        Access.writes(u);
        synchronized (store) {
            Invoice inv = editable(u, id);
            store.invoices.remove(inv.id);
            store.log(u.id, "invoice.delete", inv.id, "draft deleted");
            store.save("invoices", "audit");
            return Json.obj("ok", true);
        }
    }

    /** Works out a draft without saving it (the live totals while typing). */
    public Invoice preview(User u, Map<String, Object> b) {
        Access.writes(u);
        synchronized (store) {
            Invoice inv = new Invoice();
            fill(inv, b);
            return inv;
        }
    }

    /** A new draft with the same customer and lines (handy for repeat orders). */
    public Invoice copy(User u, String id) {
        Access.writes(u);
        synchronized (store) {
            Invoice src = get(id);
            if (src.creditNote()) throw Problem.bad("A credit note cannot be copied.");
            Invoice inv = new Invoice();
            inv.createdBy = u.id;
            inv.createdAt = Clock.millis();
            inv.customerId = src.customerId;
            inv.placeOfSupply = src.placeOfSupply;
            inv.poNumber = null;
            inv.notes = src.notes;
            inv.shipTo = src.shipTo;
            inv.issueDate = Clock.today().toString();
            for (Line l : src.lines) inv.lines.add(l.copy());
            Customer c = store.customers.get(inv.customerId);
            preview(inv, c);
            inv.id = store.nextId("I", 1001);
            store.invoices.put(inv.id, inv);
            store.log(u.id, "invoice.copy", inv.id, "copy of " + (src.number == null ? src.id : src.number));
            store.save("invoices", "audit");
            return inv;
        }
    }

    private Invoice editable(User u, String id) {
        Invoice inv = get(id);
        if (inv.status != Status.DRAFT) throw Problem.conflict("Only a draft can be changed. An issued invoice is corrected with a credit note.");
        if (!inv.createdBy.equals(u.id) && !u.role.money()) throw Problem.forbidden("Only the person who made this draft (or the Accountant) can change it.");
        return inv;
    }

    /** Reads the form into the invoice and works out the totals. */
    void fill(Invoice inv, Map<String, Object> b) {
        Customer c = store.customers.get(Json.str(b, "customerId", ""));
        if (c == null) throw Problem.bad("Pick a customer.");
        if (!c.active) throw Problem.bad(c.name + " is marked inactive.");
        inv.customerId = c.id;
        String pos = Json.str(b, "placeOfSupply", c.state);
        if (!Gstin.STATES.containsKey(pos)) throw Problem.bad("Pick the place of supply (the state where the goods go).");
        inv.placeOfSupply = pos;
        LocalDate date = date(Json.str(b, "issueDate", Clock.today().toString()), "Invoice date");
        if (date.isAfter(Clock.today())) throw Problem.bad("The invoice date cannot be in the future.");
        if (date.isBefore(Clock.today().minusDays(60))) throw Problem.bad("The invoice date can be at most 60 days back.");
        inv.issueDate = date.toString();
        inv.poNumber = Text.clean(Json.str(b, "poNumber", ""), 40);
        inv.notes = Text.block(Json.str(b, "notes", ""), 600);
        inv.shipTo = Text.block(Json.str(b, "shipTo", ""), 400);
        inv.lines = readLines(Json.list(b, "lines"));
        preview(inv, c);
    }

    /** Totals and the expected due date (both final only at issue). */
    void preview(Invoice inv, Customer c) {
        inv.buyerName = c.name;
        inv.buyerGstin = c.gstin;
        inv.buyerAddress = c.billingAddress;
        inv.dueDate = LocalDate.parse(inv.issueDate).plusDays(c.termsDays).toString();
        Tax.compute(inv, store.settings.state);
    }

    List<Line> readLines(List<Object> raw) {
        if (raw.size() > MAX_LINES) throw Problem.bad("At most " + MAX_LINES + " lines on one invoice.");
        List<Line> out = new ArrayList<>();
        int n = 0;
        for (Object o : raw) {
            n++;
            if (!(o instanceof Map<?, ?> mm)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) mm;
            Line l = new Line();
            Item item = store.items.get(Json.str(m, "itemId", ""));
            l.itemId = item == null ? null : item.id;
            l.description = Text.clean(Json.str(m, "description", item == null ? "" : item.name), 160);
            if (Text.blank(l.description)) {
                if (Text.blank(Json.str(m, "qty", "")) && Text.blank(Json.str(m, "rate", ""))) continue;   // an empty row
                throw Problem.bad("Line " + n + ": type what it is for.");
            }
            l.hsn = Text.clean(Json.str(m, "hsn", item == null ? "" : item.hsn), 8);
            if (!l.hsn.matches("\\d{4}|\\d{6}|\\d{8}")) throw Problem.bad("Line " + n + ": the HSN/SAC code needs 4, 6 or 8 digits.");
            l.unit = Text.clean(Json.str(m, "unit", item == null ? "Nos" : item.unit), 12);
            l.qtyMilli = qty(Json.str(m, "qty", ""), n);
            try { l.rate = Money.parse(Json.str(m, "rate", "")); } catch (IllegalArgumentException e) { throw Problem.bad("Line " + n + " rate: " + e.getMessage()); }
            if (l.rate <= 0) throw Problem.bad("Line " + n + ": the rate must be more than zero.");
            double disc = Json.dbl(m, "discount", 0);
            if (disc < 0 || disc > 100) throw Problem.bad("Line " + n + ": discount must be 0 to 100 %.");
            l.discountBp = (int) Math.round(disc * 100);
            l.gstBp = (int) Json.num(m, "gstBp", item == null ? 1800 : item.gstBp);
            if (!Tax.RATES.contains(l.gstBp)) throw Problem.bad("Line " + n + ": GST must be 0, 5, 12, 18 or 28 %.");
            out.add(l);
        }
        return out;
    }

    /** "2", "2.5", "1,000" -> thousandths. */
    static long qty(String s, int line) {
        String t = s == null ? "" : s.replace(",", "").trim();
        if (!t.matches("\\d{1,7}(\\.\\d{1,3})?")) throw Problem.bad("Line " + line + ": quantity must be a number like 2 or 2.5.");
        String[] p = t.split("\\.");
        long v = Long.parseLong(p[0]) * 1000 + (p.length > 1 ? Long.parseLong((p[1] + "00").substring(0, 3)) : 0);
        if (v <= 0) throw Problem.bad("Line " + line + ": quantity must be more than zero.");
        return v;
    }

    static LocalDate date(String s, String what) {
        try { return LocalDate.parse(s == null ? "" : s.trim()); } catch (DateTimeParseException e) { throw Problem.bad(what + " is not a date."); }
    }

    // ------------------------------------------------------------------ issue, approve

    /** Issue a draft. Returns the invoice (ISSUED, or PENDING_APPROVAL when it needs an approver). */
    public Invoice issue(User u, String id, String override) {
        Access.writes(u);
        synchronized (store) {
            Invoice inv = editable(u, id);
            Customer c = store.customers.get(inv.customerId);
            if (c == null || !c.active) throw Problem.bad("The customer is not active.");
            preview(inv, c);
            if (inv.lines.isEmpty() || inv.total <= 0) throw Problem.bad("Add at least one line before issuing.");
            creditCheck(u, inv, c, override);
            if (u.role == Role.SALES && inv.total > store.settings.approvalLimit) {
                inv.status = Status.PENDING_APPROVAL;
                store.log(u.id, "invoice.submit", inv.id, "waits for approval (over " + Money.rupees(store.settings.approvalLimit) + ")");
                store.save("invoices", "audit");
                return inv;
            }
            book(inv, c, u.id, null);
            return inv;
        }
    }

    public Invoice approve(User u, String id) {
        Access.money(u);
        synchronized (store) {
            Invoice inv = get(id);
            if (inv.status != Status.PENDING_APPROVAL) throw Problem.conflict("This invoice is not waiting for approval.");
            if (inv.createdBy.equals(u.id)) throw Problem.forbidden("Four eyes: somebody else must approve the invoice you made.");
            Customer c = store.customers.get(inv.customerId);
            book(inv, c, inv.createdBy, u.id);
            return inv;
        }
    }

    public Invoice sendBack(User u, String id, String note) {
        Access.money(u);
        synchronized (store) {
            Invoice inv = get(id);
            if (inv.status != Status.PENDING_APPROVAL) throw Problem.conflict("This invoice is not waiting for approval.");
            String n = Text.clean(note, 300);
            if (Text.blank(n)) throw Problem.bad("Say what should be changed.");
            inv.status = Status.DRAFT;
            inv.sentBackNote = n;
            store.log(u.id, "invoice.sendback", inv.id, n);
            store.save("invoices", "audit");
            return inv;
        }
    }

    /** Stops Sales above the credit limit; the Accountant/Owner may go on with a written reason. */
    void creditCheck(User u, Invoice inv, Customer c, String override) {
        if (c.creditLimit <= 0) return;
        long after = store.outstanding(c.id) - store.advance(c.id) + inv.total;
        if (after <= c.creditLimit) return;
        Map<String, Object> data = Json.obj("limit", c.creditLimit, "owes", store.outstanding(c.id), "after", after, "needsOverride", u.role.money());
        if (!u.role.money())
            throw Problem.conflict(c.name + " would owe " + Money.rupees(after) + ", above the credit limit of " + Money.rupees(c.creditLimit)
                    + ". Ask the Accountant to issue it, or collect a payment first.").with(data);
        String why = Text.clean(override, 200);
        if (Text.blank(why))
            throw Problem.conflict(c.name + " would owe " + Money.rupees(after) + ", above the credit limit of " + Money.rupees(c.creditLimit)
                    + ". Write why you are going above the limit.").with(data);
        inv.overrideNote = why;
        store.log(u.id, "invoice.override", inv.id, "credit limit: " + why);
    }

    /**
     * Gives the number and locks the content. Inside the store lock, so two invoices can never get the same number.
     * Also used by the automation (recurring invoices), with by = "system".
     */
    void book(Invoice inv, Customer c, String by, String approver) {
        Settings s = store.settings;
        boolean cn = inv.creditNote();
        String prefix = cn ? s.creditPrefix : s.invoicePrefix;
        LocalDate date = LocalDate.parse(inv.issueDate);
        String counter = prefix + "/" + FinYear.of(date);
        // date order inside a series: never before the last one issued
        String last = Json.str(store.meta, "last." + counter);
        if (last != null && date.isBefore(LocalDate.parse(last))) {
            store.log(by, "invoice.redate", inv.id, inv.issueDate + " -> " + last + " (keeps the series in date order)");
            date = LocalDate.parse(last);
            inv.issueDate = last;
            counter = prefix + "/" + FinYear.of(date);
        }
        if (!cn) preview(inv, c);
        else { Tax.compute(inv, s.state); inv.dueDate = inv.issueDate; }
        inv.buyerName = c.name;
        inv.buyerGstin = c.gstin;
        inv.buyerAddress = c.billingAddress;
        if (Text.blank(inv.shipTo)) inv.shipTo = c.shippingAddress;
        long n = store.nextNumber(counter);
        inv.number = FinYear.number(prefix, date, n);
        store.meta.put("last." + counter, inv.issueDate);
        inv.status = Status.ISSUED;
        inv.issuedBy = by;
        inv.approvedBy = approver;
        inv.issuedAt = Clock.millis();
        inv.fingerprint = fingerprint(inv);
        store.log(approver != null ? approver : by, cn ? "creditnote.issue" : "invoice.issue", inv.id,
                inv.number + " " + Money.format(inv.total) + (approver != null ? " (approved)" : ""));
        if (!cn) {
            store.mail(Letters.invoice(s, c, inv));
            if (s.autoApplyAdvance) applyAdvance(c.id, by);
        }
        settle(inv);
        store.save("invoices", "payments", "mails", "audit");
    }

    // ------------------------------------------------------------------ cancel, hold, credit notes

    public Invoice cancel(User u, String id, String reason) {
        Access.money(u);
        synchronized (store) {
            Invoice inv = get(id);
            if (inv.creditNote()) throw Problem.bad("A credit note cannot be cancelled.");
            if (!inv.status.booked()) throw Problem.conflict("Only an issued invoice can be cancelled (delete a draft instead).");
            if (inv.paid > 0 || inv.credited > 0)
                throw Problem.conflict("Money or a credit note is already against this invoice. Make a credit note for the rest instead.");
            String why = Text.clean(reason, 300);
            if (Text.blank(why)) throw Problem.bad("Say why it is cancelled (kept in the books).");
            inv.status = Status.CANCELLED;
            inv.cancelReason = why;
            inv.cancelledBy = u.id;
            inv.cancelledAt = Clock.millis();
            store.log(u.id, "invoice.cancel", inv.id, inv.number + ": " + why);
            store.save("invoices", "audit");
            return inv;
        }
    }

    public Invoice hold(User u, String id, boolean on, String note) {
        Access.writes(u);
        synchronized (store) {
            Invoice inv = get(id);
            if (!inv.status.open()) throw Problem.conflict("Only an unpaid invoice can be put on hold.");
            String n = Text.clean(note, 300);
            if (on && Text.blank(n)) throw Problem.bad("Say what the customer disputes.");
            inv.onHold = on;
            inv.holdNote = on ? n : null;
            store.log(u.id, on ? "invoice.hold" : "invoice.unhold", inv.id, on ? n : "reminders back on");
            store.save("invoices", "audit");
            return inv;
        }
    }

    /**
     * A credit note against an issued invoice. Body: {reason, lines: [{src: 0, qty: "2", rate: "1200"}]}
     * (src = line of the invoice; qty and rate may be lower than on the invoice - a return or a price cut).
     * The credited value of any invoice line can never be more than that line's taxable value.
     */
    public Invoice creditNote(User u, String invoiceId, Map<String, Object> b) {
        Access.money(u);
        synchronized (store) {
            Invoice inv = get(invoiceId);
            if (inv.creditNote() || !inv.status.booked()) throw Problem.conflict("A credit note can only be made against an issued invoice.");
            String reason = Text.clean(Json.str(b, "reason"), 200);
            if (Text.blank(reason)) throw Problem.bad("Say why (for example: 2 boxes returned damaged).");
            long[] already = creditedPerLine(inv);
            Invoice cn = new Invoice();
            cn.kind = Invoice.CREDIT_NOTE;
            cn.customerId = inv.customerId;
            cn.placeOfSupply = inv.placeOfSupply;
            cn.issueDate = Clock.today().toString();
            cn.dueDate = cn.issueDate;
            cn.againstId = inv.id;
            cn.reason = reason;
            cn.createdBy = u.id;
            cn.createdAt = Clock.millis();
            for (Object o : Json.list(b, "lines")) {
                if (!(o instanceof Map<?, ?> mm)) continue;
                @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) mm;
                int src = (int) Json.num(m, "src", -1);
                if (src < 0 || src >= inv.lines.size()) throw Problem.bad("Unknown invoice line.");
                if (Text.blank(Json.str(m, "qty", ""))) continue;
                Line orig = inv.lines.get(src);
                Line l = orig.copy();
                l.src = src;
                l.qtyMilli = qty(Json.str(m, "qty", ""), src + 1);
                if (!Text.blank(Json.str(m, "rate", ""))) {
                    try { l.rate = Money.parse(Json.str(m, "rate", "")); } catch (IllegalArgumentException e) { throw Problem.bad("Line " + (src + 1) + " rate: " + e.getMessage()); }
                }
                if (l.rate <= 0 || l.rate > orig.rate) throw Problem.bad("Line " + (src + 1) + ": the rate must be more than 0 and at most the invoice rate.");
                Tax.line(l, Tax.intraState(store.settings.state, cn.placeOfSupply));
                if (already[src] + l.taxable > orig.taxable)
                    throw Problem.bad("Line " + (src + 1) + " (" + orig.description + "): only " + Money.format(orig.taxable - already[src]) + " is left to credit.");
                already[src] += l.taxable;
                cn.lines.add(l);
            }
            if (cn.lines.isEmpty()) throw Problem.bad("Fill the quantity of at least one line.");
            Tax.compute(cn, store.settings.state);
            cn.id = store.nextId("I", 1001);
            store.invoices.put(cn.id, cn);
            Customer c = store.customers.get(inv.customerId);
            book(cn, c, u.id, null);
            // the credit goes to the invoice first; what is left is kept for the customer's next invoice
            long use = Math.min(cn.total, Math.max(0, inv.balance()));
            if (use > 0) cn.applied.add(new Alloc(inv.id, use, Clock.millis()));
            settle(cn);
            settle(inv);
            if (store.settings.autoApplyAdvance && cn.balance() > 0) applyAdvance(c.id, u.id);
            store.mail(Letters.creditNote(store.settings, c, cn, inv));
            store.save("invoices", "mails", "audit");
            return cn;
        }
    }

    /** Taxable value already credited for each line of the invoice. */
    long[] creditedPerLine(Invoice inv) {
        long[] a = new long[inv.lines.size()];
        for (Invoice x : store.invoices.values())
            if (x.creditNote() && inv.id.equals(x.againstId) && x.status.booked())
                for (Line l : x.lines) if (l.src >= 0 && l.src < a.length) a[l.src] += l.taxable;
        return a;
    }

    // ------------------------------------------------------------------ settling

    /** Works out paid / credited from the payments and credit notes, and the status from that. */
    public void settle(Invoice inv) {
        synchronized (store) {
            if (inv.creditNote()) {
                if (inv.status.booked()) inv.status = inv.balance() <= 0 ? Status.APPLIED : Status.ISSUED;
                return;
            }
            long paid = 0, credited = 0;
            for (Payment p : store.payments.values())
                if (!p.voided) for (Alloc a : p.allocations) if (a.invoiceId.equals(inv.id)) paid += a.amount;
            for (Invoice x : store.invoices.values())
                if (x.creditNote() && x.status.booked()) for (Alloc a : x.applied) if (a.invoiceId.equals(inv.id)) credited += a.amount;
            inv.paid = paid;
            inv.credited = credited;
            if (!inv.status.booked()) return;
            if (inv.balance() <= 0) inv.status = Status.PAID;
            else if (paid + credited > 0) inv.status = Status.PART_PAID;
            else inv.status = Status.ISSUED;
        }
    }

    /**
     * Uses the customer's advance (unapplied payments, then unused credit notes - oldest first)
     * for the open invoices (oldest due date first). Returns how much was used.
     */
    public long applyAdvance(String customerId, String by) {
        synchronized (store) {
            List<Invoice> open = new ArrayList<>();
            for (Invoice i : store.invoices.values())
                if (i.customerId.equals(customerId) && !i.creditNote() && i.status.open() && i.balance() > 0) open.add(i);
            if (open.isEmpty()) return 0;
            open.sort(Comparator.comparing((Invoice i) -> i.dueDate).thenComparing(i -> i.number));
            List<Payment> pays = new ArrayList<>(store.paymentsOf(customerId));
            pays.removeIf(p -> p.unapplied() <= 0);
            pays.sort(Comparator.comparing((Payment p) -> p.date).thenComparing(p -> p.number));
            List<Invoice> cns = new ArrayList<>();
            for (Invoice i : store.invoices.values())
                if (i.customerId.equals(customerId) && i.creditNote() && i.status == Status.ISSUED && i.balance() > 0) cns.add(i);
            cns.sort(Comparator.comparing((Invoice i) -> i.issueDate).thenComparing(i -> i.number));
            long used = 0;
            long now = Clock.millis();
            for (Invoice target : open) {
                long need = target.balance();
                for (Payment p : pays) {
                    if (need <= 0) break;
                    long take = Math.min(need, p.unapplied());
                    if (take <= 0) continue;
                    p.allocations.add(new Alloc(target.id, take, now));
                    need -= take;
                    used += take;
                    store.log(by, "advance.apply", target.id, Money.format(take) + " from receipt " + p.number);
                }
                for (Invoice cn : cns) {
                    if (need <= 0) break;
                    long take = Math.min(need, cn.balance());
                    if (take <= 0) continue;
                    cn.applied.add(new Alloc(target.id, take, now));
                    need -= take;
                    used += take;
                    settle(cn);
                    store.log(by, "credit.apply", target.id, Money.format(take) + " from credit note " + cn.number);
                }
                settle(target);
            }
            if (used > 0) store.save("invoices", "payments", "audit");
            return used;
        }
    }

    public Map<String, Object> useAdvance(User u, String customerId) {
        Access.money(u);
        if (!store.customers.containsKey(customerId)) throw Problem.notFound("Customer");
        long used = applyAdvance(customerId, u.id);
        if (used == 0) throw Problem.conflict("There is no advance to use, or nothing is open.");
        return Json.obj("used", used);
    }

    // ------------------------------------------------------------------ helpers

    public Invoice get(String id) {
        Invoice inv = id == null ? null : store.invoices.get(id);
        if (inv == null) throw Problem.notFound("Invoice");
        return inv;
    }

    /**
     * SHA-256 over everything printed on the invoice. Kept at issue; if anybody later edits the data file by hand,
     * the fingerprint no longer matches and the invoice page shows a red warning.
     */
    public static String fingerprint(Invoice i) {
        StringBuilder sb = new StringBuilder();
        sb.append(i.kind).append('|').append(i.number).append('|').append(i.issueDate).append('|').append(i.dueDate).append('|')
          .append(i.customerId).append('|').append(i.buyerName).append('|').append(i.buyerGstin).append('|').append(i.placeOfSupply).append('|')
          .append(i.againstId).append('|');
        for (Line l : i.lines)
            sb.append(l.description).append(';').append(l.hsn).append(';').append(l.qtyMilli).append(';').append(l.rate).append(';')
              .append(l.discountBp).append(';').append(l.gstBp).append(';').append(l.taxable).append(';').append(l.cgst).append(';')
              .append(l.sgst).append(';').append(l.igst).append('|');
        sb.append(i.taxable).append('|').append(i.cgst).append('|').append(i.sgst).append('|').append(i.igst).append('|')
          .append(i.roundOff).append('|').append(i.total);
        return Passwords.sha256(sb.toString());
    }
}
