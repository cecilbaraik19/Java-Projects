package com.cecil.bijak.service;

import com.cecil.bijak.core.BankMatch;
import com.cecil.bijak.core.FinYear;
import com.cecil.bijak.model.Alloc;
import com.cecil.bijak.model.BankLine;
import com.cecil.bijak.model.Customer;
import com.cecil.bijak.model.Invoice;
import com.cecil.bijak.model.Payment;
import com.cecil.bijak.model.User;
import com.cecil.bijak.store.DataStore;
import com.cecil.bijak.util.Clock;
import com.cecil.bijak.util.Csv;
import com.cecil.bijak.util.Json;
import com.cecil.bijak.util.Money;
import com.cecil.bijak.util.Text;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Money received: payments (receipts), giving them to invoices, voiding a wrong one,
 * and the bank statement (import a CSV, get match suggestions, accept them).
 */
public class PaymentService {
    private final DataStore store;
    private final InvoiceService invoices;

    public PaymentService(DataStore store, InvoiceService invoices) {
        this.store = store;
        this.invoices = invoices;
    }

    /**
     * Records a payment. Body: {customerId, date, amount, tds, mode, reference, notes,
     *                           allocations: [{invoiceId, amount}]  (leave out = oldest invoices first)}
     */
    public Payment record(User u, Map<String, Object> b) {
        Access.money(u);
        synchronized (store) {
            Customer c = store.customers.get(Json.str(b, "customerId", ""));
            if (c == null) throw Problem.bad("Pick the customer who paid.");
            Payment p = new Payment();
            p.customerId = c.id;
            p.date = InvoiceService.date(Json.str(b, "date", Clock.today().toString()), "Payment date").toString();
            if (LocalDate.parse(p.date).isAfter(Clock.today())) throw Problem.bad("The payment date cannot be in the future.");
            p.amount = amount(Json.str(b, "amount", ""), "Amount");
            if (p.amount <= 0) throw Problem.bad("The amount must be more than zero.");
            String tds = Json.str(b, "tds", "");
            p.tds = Text.blank(tds) ? 0 : amount(tds, "TDS");
            if (p.tds < 0) throw Problem.bad("TDS cannot be below zero.");
            if (p.tds > Money.pct(p.amount + p.tds, 10)) throw Problem.bad("TDS cannot be more than 10 % of the payment.");
            p.mode = Json.str(b, "mode", "NEFT").toUpperCase(Locale.ROOT);
            if (!Payment.MODES.contains(p.mode)) throw Problem.bad("Pick how it was paid: " + String.join(", ", Payment.MODES) + ".");
            p.reference = Text.clean(Json.str(b, "reference", ""), 60);
            if (!p.mode.equals("CASH") && Text.blank(p.reference)) throw Problem.bad("Type the UTR / cheque number (the bank reference).");
            p.notes = Text.clean(Json.str(b, "notes", ""), 300);
            if (p.mode.equals("CASH") && p.amount > 1_99_999_00L)
                throw Problem.bad("Cash of Rs. 2,00,000 or more from one person in one day is not allowed (Income-tax Act, section 269ST).");
            // the same reference twice is almost always a double entry
            for (Payment o : store.payments.values())
                if (!o.voided && !Text.blank(p.reference) && p.reference.equalsIgnoreCase(o.reference) && o.customerId.equals(c.id))
                    throw Problem.conflict("Receipt " + o.number + " already has reference " + p.reference + ".");
            p.bankLineId = Json.str(b, "bankLineId");
            p.createdBy = u.id;
            p.createdAt = Clock.millis();

            List<Object> raw = Json.list(b, "allocations");
            if (!raw.isEmpty()) {
                long sum = 0;
                Set<String> seen = new HashSet<>();
                for (Object o : raw) {
                    if (!(o instanceof Map<?, ?> mm)) continue;
                    @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) mm;
                    Invoice inv = store.invoices.get(Json.str(m, "invoiceId", ""));
                    if (inv == null || inv.creditNote() || !inv.customerId.equals(c.id)) throw Problem.bad("That invoice is not one of " + c.name + "'s.");
                    if (!inv.status.open()) throw Problem.bad(inv.number + " is not open.");
                    if (!seen.add(inv.id)) throw Problem.bad(inv.number + " is given twice.");
                    long a = amount(Json.str(m, "amount", ""), inv.number);
                    if (a <= 0) continue;
                    if (a > inv.balance()) throw Problem.bad(inv.number + " needs only " + Money.format(inv.balance()) + ".");
                    sum += a;
                    p.allocations.add(new Alloc(inv.id, a, p.createdAt));
                }
                if (sum > p.amount + p.tds) throw Problem.bad("You gave invoices " + Money.format(sum) + " but the payment is only " + Money.format(p.amount + p.tds) + ".");
            }
            LocalDate d = LocalDate.parse(p.date);
            p.number = FinYear.number(store.settings.receiptPrefix, d, store.nextNumber(store.settings.receiptPrefix + "/" + FinYear.of(d)));
            p.id = store.nextId("R", 501);
            store.payments.put(p.id, p);
            for (Alloc a : p.allocations) invoices.settle(store.invoices.get(a.invoiceId));
            if (raw.isEmpty()) invoices.applyAdvance(c.id, u.id);   // oldest invoices first
            store.log(u.id, "payment.record", p.id, p.number + " " + Money.format(p.amount) + " " + p.mode
                    + (p.tds > 0 ? " + TDS " + Money.format(p.tds) : "") + " from " + c.name);
            store.mail(Letters.receipt(store.settings, c, p, allocText(p)));
            store.save("payments", "invoices", "mails", "audit");
            return p;
        }
    }

    String allocText(Payment p) {
        StringBuilder sb = new StringBuilder();
        if (!p.allocations.isEmpty()) {
            sb.append("It settles:\n");
            for (Alloc a : p.allocations) {
                Invoice i = store.invoices.get(a.invoiceId);
                sb.append("  ").append(i.number).append("  Rs. ").append(Money.format(a.amount))
                  .append(i.balance() <= 0 ? "  (fully paid)" : "  (Rs. " + Money.format(i.balance()) + " still due)").append('\n');
            }
        }
        if (p.unapplied() > 0) sb.append("Rs. ").append(Money.format(p.unapplied())).append(" is kept as an advance for your next invoice.\n");
        return sb.toString();
    }

    /** Takes a wrong payment back out of the books (the receipt number stays, marked VOID). */
    public Payment voidPayment(User u, String id, String reason) {
        Access.money(u);
        synchronized (store) {
            Payment p = store.payments.get(id);
            if (p == null) throw Problem.notFound("Payment");
            if (p.voided) throw Problem.conflict("This payment is already void.");
            String why = Text.clean(reason, 200);
            if (Text.blank(why)) throw Problem.bad("Say why (for example: cheque bounced).");
            List<String> touched = new ArrayList<>();
            for (Alloc a : p.allocations) touched.add(a.invoiceId);
            p.voided = true;
            p.voidReason = why;
            p.voidedBy = u.id;
            p.voidedAt = Clock.millis();
            for (String iid : touched) {
                Invoice i = store.invoices.get(iid);
                if (i != null) { invoices.settle(i); if (i.status.open()) i.reminderLevel = Math.min(i.reminderLevel, 2); }
            }
            if (p.bankLineId != null) {
                BankLine bl = store.bank.get(p.bankLineId);
                if (bl != null) { bl.state = BankLine.State.NEW; bl.paymentId = null; }
            }
            store.log(u.id, "payment.void", p.id, p.number + ": " + why);
            store.save("payments", "invoices", "bank", "audit");
            return p;
        }
    }

    static long amount(String s, String what) {
        try { return Money.parse(s); } catch (IllegalArgumentException e) { throw Problem.bad(what + ": " + e.getMessage()); }
    }

    // ------------------------------------------------------------------ bank statement

    private static final List<DateTimeFormatter> DATES = List.of(
            DateTimeFormatter.ofPattern("d/M/uuuu"), DateTimeFormatter.ofPattern("d-M-uuuu"), DateTimeFormatter.ofPattern("d.M.uuuu"),
            DateTimeFormatter.ofPattern("d/M/uu"), DateTimeFormatter.ofPattern("uuuu-M-d"),
            DateTimeFormatter.ofPattern("d-MMM-uuuu", Locale.ENGLISH), DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d-MMM-uu", Locale.ENGLISH));

    static LocalDate bankDate(String s) {
        String t = s == null ? "" : s.trim();
        if (t.length() > 3) t = t.substring(0, 1).toUpperCase() + t.substring(1);
        for (DateTimeFormatter f : DATES) {
            try { return LocalDate.parse(fixMonth(t), f); } catch (DateTimeParseException ignored) { }
        }
        return null;
    }

    /** "12-NOV-2026" -> "12-Nov-2026" (Java wants the month name as Nov). */
    private static String fixMonth(String t) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([A-Za-z]{3})").matcher(t);
        if (!m.find()) return t;
        String mon = m.group(1);
        return t.substring(0, m.start()) + mon.charAt(0) + mon.substring(1).toLowerCase(Locale.ROOT) + t.substring(m.end());
    }

    /**
     * Reads a bank statement CSV (as banks give it: Date, Narration, Chq/Ref No, Withdrawal, Deposit ...).
     * The columns are found by their names. Only money IN is kept. A line already imported is skipped.
     */
    public Map<String, Object> importStatement(User u, String csv) {
        Access.money(u);
        if (csv == null || csv.isBlank()) throw Problem.bad("The file is empty.");
        List<List<String>> rows = Csv.parse(csv);
        int head = -1;
        int cDate = -1, cNarr = -1, cRef = -1, cCredit = -1, cDebit = -1, cAmount = -1, cType = -1;
        for (int r = 0; r < Math.min(rows.size(), 25) && head < 0; r++) {
            List<String> row = rows.get(r);
            int d = -1, n = -1, f = -1, cr = -1, db = -1, am = -1, ty = -1;
            for (int i = 0; i < row.size(); i++) {
                String k = Text.key(row.get(i)).replaceAll("[^a-z/ ]", "").trim();
                if (d < 0 && (k.equals("date") || k.equals("txn date") || k.equals("transaction date") || k.equals("tran date"))) d = i;
                else if (n < 0 && (k.contains("narration") || k.contains("description") || k.contains("particulars") || k.equals("remarks") || k.equals("details"))) n = i;
                else if (f < 0 && (k.contains("ref") || k.contains("chq") || k.contains("cheque") || k.equals("utr"))) f = i;
                else if (cr < 0 && (k.startsWith("credit") || k.startsWith("deposit") || k.equals("cr") || k.equals("cr amount"))) cr = i;
                else if (db < 0 && (k.startsWith("debit") || k.startsWith("withdrawal") || k.equals("dr") || k.equals("dr amount"))) db = i;
                else if (am < 0 && (k.equals("amount") || k.equals("amount inr") || k.equals("txn amount"))) am = i;
                else if (ty < 0 && (k.equals("type") || k.equals("dr/cr") || k.equals("cr/dr"))) ty = i;
            }
            if (d >= 0 && n >= 0 && (cr >= 0 || am >= 0)) { head = r; cDate = d; cNarr = n; cRef = f; cCredit = cr; cDebit = db; cAmount = am; cType = ty; }
        }
        if (head < 0) throw Problem.bad("Could not find the columns. The file needs a header row with Date, Narration (or Description) and Deposit/Credit (or Amount).");

        synchronized (store) {
            Set<String> known = new HashSet<>();
            for (BankLine bl : store.bank.values()) known.add(bl.date + "|" + bl.amount + "|" + Text.key(bl.narration));
            String importId = store.nextId("S", 1);
            int added = 0, dup = 0, debits = 0;
            List<Map<String, Object>> errors = new ArrayList<>();
            for (int r = head + 1; r < rows.size(); r++) {
                List<String> row = rows.get(r);
                if (row.stream().allMatch(String::isBlank)) continue;
                String rawDate = cell(row, cDate);
                LocalDate d = bankDate(rawDate);
                if (d == null) {
                    if (!rawDate.isBlank() && errors.size() < 20) errors.add(Json.obj("row", r + 1, "message", "\"" + rawDate + "\" is not a date"));
                    continue;   // summary rows at the end ("Opening balance", totals ...)
                }
                long amount;
                try {
                    if (cCredit >= 0) {
                        String cr = cell(row, cCredit);
                        if (cr.isBlank() || cr.equals("0") || cr.equals("0.00") || cr.equals("-")) { debits++; continue; }
                        amount = Money.parse(cr);
                    } else {
                        amount = Money.parse(cell(row, cAmount));
                        String type = cell(row, cType).toUpperCase(Locale.ROOT);
                        if (type.startsWith("D") || amount < 0) { debits++; continue; }
                    }
                } catch (IllegalArgumentException e) {
                    if (errors.size() < 20) errors.add(Json.obj("row", r + 1, "message", e.getMessage()));
                    continue;
                }
                if (amount <= 0) { debits++; continue; }
                String narr = Text.clean(cell(row, cNarr), 200);
                String key = d + "|" + amount + "|" + Text.key(narr);
                if (!known.add(key)) { dup++; continue; }
                BankLine bl = new BankLine();
                bl.id = store.nextId("B", 1);
                bl.importId = importId;
                bl.date = d.toString();
                bl.narration = narr;
                bl.reference = Text.clean(cell(row, cRef), 60);
                bl.amount = amount;
                bl.importedAt = Clock.millis();
                store.bank.put(bl.id, bl);
                added++;
            }
            store.log(u.id, "bank.import", importId, added + " new, " + dup + " already there, " + debits + " debits skipped");
            store.save("bank", "audit");
            return Json.obj("importId", importId, "added", added, "duplicates", dup, "debits", debits, "errors", errors);
        }
    }

    private static String cell(List<String> row, int i) { return i < 0 || i >= row.size() ? "" : row.get(i).trim(); }

    /** Match suggestions for one bank line (best first). */
    public List<BankMatch.Suggestion> suggestions(String lineId) {
        synchronized (store) {
            BankLine bl = store.bank.get(lineId);
            if (bl == null) throw Problem.notFound("Bank line");
            return suggest(bl);
        }
    }

    List<BankMatch.Suggestion> suggest(BankLine bl) {
        List<BankMatch.Cust> cs = new ArrayList<>();
        for (Customer c : store.customers.values()) cs.add(new BankMatch.Cust(c.id, c.name, c.gstin, c.tdsBp));
        List<BankMatch.Open> open = new ArrayList<>();
        for (Invoice i : store.invoices.values()) {
            if (i.creditNote() || !i.status.open() || i.balance() <= 0) continue;
            long tdsBase = i.total == 0 ? 0 : Money.div(i.taxable * i.balance(), i.total);
            open.add(new BankMatch.Open(i.id, i.number, i.customerId, i.dueDate, i.balance(), tdsBase));
        }
        return BankMatch.suggest(bl.amount, bl.narration, bl.reference, store.settings.invoicePrefix, cs, open);
    }

    /** Accept a match: books the payment from the bank line. Body: {customerId, allocations:[{invoiceId, amount}], tds} */
    public Payment accept(User u, String lineId, Map<String, Object> b) {
        Access.money(u);
        synchronized (store) {
            BankLine bl = store.bank.get(lineId);
            if (bl == null) throw Problem.notFound("Bank line");
            if (bl.state != BankLine.State.NEW) throw Problem.conflict("This bank line is already done.");
            Map<String, Object> body = new LinkedHashMap<>(b);
            body.put("date", bl.date);
            body.put("amount", Money.format(bl.amount));
            body.put("mode", guessMode(bl.narration));
            body.put("reference", Text.blank(bl.reference) ? utr(bl) : bl.reference);
            body.put("bankLineId", bl.id);
            body.putIfAbsent("notes", "From bank statement: " + bl.narration);
            Payment p = record(u, body);
            bl.state = BankLine.State.MATCHED;
            bl.paymentId = p.id;
            store.save("bank");
            return p;
        }
    }

    /** Accepts every "Sure" suggestion that has no close second (two customers with a good score = a person decides). */
    public Map<String, Object> acceptAllSure(User u) {
        Access.money(u);
        synchronized (store) {
            int done = 0, skipped = 0;
            List<BankLine> lines = new ArrayList<>(store.bank.values());
            lines.sort(Comparator.comparing((BankLine x) -> x.date));
            for (BankLine bl : lines) {
                if (bl.state != BankLine.State.NEW) continue;
                List<BankMatch.Suggestion> s = suggest(bl);
                if (s.isEmpty() || s.get(0).score() < BankMatch.SURE || (s.size() > 1 && s.get(1).score() >= BankMatch.LIKELY)) { skipped++; continue; }
                BankMatch.Suggestion top = s.get(0);
                List<Object> alloc = new ArrayList<>();
                for (int i = 0; i < top.invoiceIds().size(); i++)
                    alloc.add(Json.obj("invoiceId", top.invoiceIds().get(i), "amount", Money.format(top.amounts().get(i))));
                try {
                    accept(u, bl.id, Json.obj("customerId", top.customerId(), "allocations", alloc, "tds", Money.format(top.tds())));
                    done++;
                } catch (Problem p) {
                    skipped++;
                }
            }
            return Json.obj("matched", done, "left", skipped);
        }
    }

    public BankLine ignore(User u, String lineId, boolean ignore) {
        Access.money(u);
        synchronized (store) {
            BankLine bl = store.bank.get(lineId);
            if (bl == null) throw Problem.notFound("Bank line");
            if (bl.state == BankLine.State.MATCHED) throw Problem.conflict("This line is matched. Void its payment first.");
            bl.state = ignore ? BankLine.State.IGNORED : BankLine.State.NEW;
            bl.ignoredBy = ignore ? u.id : null;
            store.log(u.id, ignore ? "bank.ignore" : "bank.unignore", bl.id, bl.narration);
            store.save("bank", "audit");
            return bl;
        }
    }

    static String guessMode(String narration) {
        String n = narration == null ? "" : narration.toUpperCase(Locale.ROOT);
        for (String m : List.of("RTGS", "IMPS", "UPI", "NEFT")) if (n.contains(m)) return m;
        if (n.contains("CHQ") || n.contains("CLG") || n.contains("CHEQUE")) return "CHEQUE";
        return "NEFT";
    }

    /** A bank reference from the narration (the longest run of letters+digits with at least 8 digits), or the line id. */
    static String utr(BankLine bl) {
        String best = null;
        for (String w : (bl.narration == null ? "" : bl.narration).toUpperCase(Locale.ROOT).split("[^A-Z0-9]+")) {
            if (w.replaceAll("\\D", "").length() >= 8 && (best == null || w.length() > best.length())) best = w;
        }
        return best == null ? "BANK-" + bl.id : best;
    }
}
