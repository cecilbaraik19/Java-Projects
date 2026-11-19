package com.cecil.bijak.service;

import com.cecil.bijak.core.Dunning;
import com.cecil.bijak.core.FinYear;
import com.cecil.bijak.core.Gstin;
import com.cecil.bijak.model.Alloc;
import com.cecil.bijak.model.Customer;
import com.cecil.bijak.model.Invoice;
import com.cecil.bijak.model.Payment;
import com.cecil.bijak.model.Role;
import com.cecil.bijak.model.Settings;
import com.cecil.bijak.model.User;
import com.cecil.bijak.store.DataStore;
import com.cecil.bijak.util.Clock;
import com.cecil.bijak.util.Csv;
import com.cecil.bijak.util.Json;
import com.cecil.bijak.util.Money;
import com.cecil.bijak.util.Passwords;
import com.cecil.bijak.util.Text;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** The Owner's desk: people, company settings, and the CSV files for Excel and for the CA. */
public class AdminService {
    private final DataStore store;
    private final ViewService views;

    public AdminService(DataStore store, ViewService views) {
        this.store = store;
        this.views = views;
    }

    public User saveUser(User me, String id, Map<String, Object> b) {
        Access.owner(me);
        synchronized (store) {
            User u;
            if (id == null) {
                u = new User();
                u.createdAt = Clock.millis();
                String username = Text.clean(Json.str(b, "username", ""), 30).toLowerCase();
                if (!username.matches("[a-z][a-z0-9._]{2,29}")) throw Problem.bad("Username: 3-30 small letters or digits, starting with a letter.");
                if (store.userByName(username) != null) throw Problem.conflict("Username " + username + " is taken.");
                u.username = username;
            } else {
                u = store.users.get(id);
                if (u == null) throw Problem.notFound("Person");
            }
            String name = Text.clean(Json.str(b, "name", ""), 80);
            if (Text.blank(name)) throw Problem.bad("Type the person's name.");
            Role role = Role.of(Json.str(b, "role", ""));
            if (role == null) throw Problem.bad("Pick a role.");
            boolean active = Json.bool(b, "active", true);
            if (u == me && (role != Role.OWNER || !active)) throw Problem.bad("You cannot take away your own Owner access.");
            u.name = name;
            u.email = Text.clean(Json.str(b, "email", ""), 120);
            u.role = role;
            u.active = active;
            String pw = Json.str(b, "password", "");
            if (id == null || !Text.blank(pw)) {
                String p = Passwords.problem(pw);
                if (p != null) throw Problem.bad(p);
                u.passwordHash = Passwords.hash(pw);
                u.failedLogins = 0;
                u.lockedUntil = 0;
            }
            if (id == null) {
                u.id = store.nextId("U", 1);
                store.users.put(u.id, u);
            }
            store.log(me.id, id == null ? "user.add" : "user.edit", u.id, u.username + " (" + u.role.label() + ")");
            store.save("users", "audit");
            return u;
        }
    }

    public Settings saveSettings(User me, Map<String, Object> b) {
        Access.owner(me);
        synchronized (store) {
            Settings s = Settings.fromJson(store.settings.toJson());
            s.companyName = req(Text.clean(Json.str(b, "companyName", s.companyName), 120), "Company name");
            String g = Gstin.normalize(Json.str(b, "gstin", s.gstin));
            String gp = Gstin.problem(g);
            if (gp != null) throw Problem.bad("Company GSTIN: " + gp);
            s.gstin = g;
            s.state = Gstin.stateOf(g);
            s.address = Text.block(Json.str(b, "address", s.address), 400);
            s.phone = Text.clean(Json.str(b, "phone", s.phone), 30);
            s.email = Text.clean(Json.str(b, "email", s.email), 120);
            s.bankName = Text.clean(Json.str(b, "bankName", s.bankName), 80);
            s.bankAccount = Text.clean(Json.str(b, "bankAccount", s.bankAccount), 30);
            s.ifsc = Text.clean(Json.str(b, "ifsc", s.ifsc), 11).toUpperCase();
            if (!s.ifsc.isEmpty() && !s.ifsc.matches("[A-Z]{4}0[A-Z0-9]{6}")) throw Problem.bad("An IFSC has 11 characters: 4 letters, a 0, then 6 letters or digits (like SBIN0001234).");
            s.upiId = Text.clean(Json.str(b, "upiId", s.upiId), 60);
            if (!s.upiId.isEmpty() && !s.upiId.matches("[A-Za-z0-9._-]{2,}@[A-Za-z]{2,}")) throw Problem.bad("A UPI ID looks like name@bank.");
            String ip = Json.str(b, "invoicePrefix", s.invoicePrefix).toUpperCase();
            String cp = Json.str(b, "creditPrefix", s.creditPrefix).toUpperCase();
            String rp = Json.str(b, "receiptPrefix", s.receiptPrefix).toUpperCase();
            for (String p : List.of(ip, cp, rp)) { String e = FinYear.prefixProblem(p); if (e != null) throw Problem.bad(e); }
            if (ip.equals(cp) || ip.equals(rp) || cp.equals(rp)) throw Problem.bad("Invoices, credit notes and receipts need three different prefixes.");
            s.invoicePrefix = ip;
            s.creditPrefix = cp;
            s.receiptPrefix = rp;
            try { s.approvalLimit = Money.parse(Json.str(b, "approvalLimit", Money.format(s.approvalLimit))); } catch (IllegalArgumentException e) { throw Problem.bad("Approval limit: " + e.getMessage()); }
            if (s.approvalLimit < 0) throw Problem.bad("The approval limit cannot be below zero.");
            List<Integer> days = new ArrayList<>();
            for (Object o : Json.list(b, "reminderDays")) {
                try { days.add(Integer.parseInt(String.valueOf(o).trim())); } catch (NumberFormatException e) { throw Problem.bad("Reminder days must be whole numbers."); }
            }
            if (!days.isEmpty()) {
                String dp = Dunning.daysProblem(days);
                if (dp != null) throw Problem.bad(dp);
                s.reminderDays = days;
            }
            s.remindersOn = Json.bool(b, "remindersOn", s.remindersOn);
            double interest = Json.dbl(b, "interestPct", s.interestBp / 100.0);
            if (interest < 0 || interest > 36) throw Problem.bad("Interest must be 0 to 36 % a year.");
            s.interestBp = (int) Math.round(interest * 100);
            s.autoApplyAdvance = Json.bool(b, "autoApplyAdvance", s.autoApplyAdvance);
            s.terms = Text.block(Json.str(b, "terms", s.terms), 600);
            store.settings = s;
            store.log(me.id, "settings.edit", "settings", s.companyName);
            store.save("settings", "audit");
            return s;
        }
    }

    private static String req(String v, String what) {
        if (Text.blank(v)) throw Problem.bad(what + " is needed.");
        return v;
    }

    public Map<String, Object> desk(User me) {
        if (!me.role.money() && me.role != Role.AUDITOR) throw Problem.forbidden("Only the Owner, Accountant and Auditor open this page.");
        synchronized (store) {
            List<Object> people = new ArrayList<>();
            for (User u : store.users.values()) {
                Map<String, Object> p = ViewService.person(u);
                p.put("username", u.username);
                p.put("lastSeen", u.lastSeen);
                p.put("locked", u.lockedUntil > Clock.millis());
                people.add(p);
            }
            return Json.obj("people", people, "settings", store.settings.toJson(), "roles", List.of(Role.values()),
                    "nextNumbers", Json.obj(
                            "invoice", FinYear.number(store.settings.invoicePrefix, Clock.today(), store.peekNumber(store.settings.invoicePrefix + "/" + FinYear.of(Clock.today()))),
                            "credit", FinYear.number(store.settings.creditPrefix, Clock.today(), store.peekNumber(store.settings.creditPrefix + "/" + FinYear.of(Clock.today()))),
                            "receipt", FinYear.number(store.settings.receiptPrefix, Clock.today(), store.peekNumber(store.settings.receiptPrefix + "/" + FinYear.of(Clock.today())))));
        }
    }

    // ------------------------------------------------------------------ CSV for Excel

    private static String r(long paise) { return Money.format(paise).replace(",", ""); }

    public String invoiceRegister(String from, String to) {
        synchronized (store) {
            List<List<String>> rows = new ArrayList<>();
            rows.add(List.of("Number", "Type", "Date", "Due", "Customer", "GSTIN", "Place of supply", "Taxable", "CGST", "SGST", "IGST",
                    "Round off", "Total", "Paid", "Credited", "Balance", "Status", "PO"));
            List<Invoice> list = new ArrayList<>();
            for (Invoice i : store.invoices.values()) {
                if (i.number == null) continue;
                if (!Text.blank(from) && i.issueDate.compareTo(from) < 0) continue;
                if (!Text.blank(to) && i.issueDate.compareTo(to) > 0) continue;
                list.add(i);
            }
            list.sort(Comparator.comparing((Invoice i) -> i.issueDate).thenComparing(i -> i.number));
            for (Invoice i : list)
                rows.add(List.of(i.number, i.creditNote() ? "Credit note" : "Invoice", i.issueDate, i.dueDate, i.buyerName, nz(i.buyerGstin),
                        i.placeOfSupply + "-" + Gstin.STATES.getOrDefault(i.placeOfSupply, ""), r(i.taxable), r(i.cgst), r(i.sgst), r(i.igst),
                        r(i.roundOff), r(i.total), r(i.paid), r(i.credited), r(i.balance()), i.status.name(), nz(i.poNumber)));
            return Csv.write(rows);
        }
    }

    /** GSTR-1 style CSV for one month: B2B, B2C, credit notes, HSN summary - what the CA needs to file the return. */
    @SuppressWarnings("unchecked")
    public String gstCsv(String month) {
        YearMonth ym;
        try { ym = YearMonth.parse(month); } catch (Exception e) { throw Problem.bad("Month must look like 2026-11."); }
        Map<String, Object> g = views.gst(ym);
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("GST summary for " + ym + " - " + store.settings.companyName + " - GSTIN " + store.settings.gstin));
        rows.add(List.of());
        rows.add(List.of("B2B invoices"));
        rows.add(List.of("GSTIN of buyer", "Buyer", "Invoice number", "Date", "Place of supply", "Taxable", "CGST", "SGST", "IGST", "Invoice value"));
        for (Object o : (List<Object>) g.get("b2b")) {
            Map<String, Object> m = (Map<String, Object>) o;
            rows.add(List.of(Json.str(m, "gstin"), Json.str(m, "buyer"), Json.str(m, "number"), Json.str(m, "date"), Json.str(m, "pos"),
                    r(Json.num(m, "taxable", 0)), r(Json.num(m, "cgst", 0)), r(Json.num(m, "sgst", 0)), r(Json.num(m, "igst", 0)), r(Json.num(m, "total", 0))));
        }
        rows.add(List.of());
        rows.add(List.of("B2C (no GSTIN)"));
        rows.add(List.of("Place of supply", "Rate %", "Taxable", "CGST", "SGST", "IGST"));
        for (Object o : (List<Object>) g.get("b2c")) {
            Map<String, Object> m = (Map<String, Object>) o;
            rows.add(List.of(Json.str(m, "pos") + "-" + Json.str(m, "posName"), String.valueOf(Json.num(m, "rate", 0) / 100.0),
                    r(Json.num(m, "taxable", 0)), r(Json.num(m, "cgst", 0)), r(Json.num(m, "sgst", 0)), r(Json.num(m, "igst", 0))));
        }
        rows.add(List.of());
        rows.add(List.of("Credit notes"));
        rows.add(List.of("Number", "Date", "Buyer", "GSTIN", "Against invoice", "Taxable", "CGST", "SGST", "IGST", "Value"));
        for (Object o : (List<Object>) g.get("creditNotes")) {
            Map<String, Object> m = (Map<String, Object>) o;
            rows.add(List.of(Json.str(m, "number"), Json.str(m, "date"), Json.str(m, "buyer"), nz(Json.str(m, "gstin")), nz(Json.str(m, "against")),
                    r(Json.num(m, "taxable", 0)), r(Json.num(m, "cgst", 0)), r(Json.num(m, "sgst", 0)), r(Json.num(m, "igst", 0)), r(Json.num(m, "total", 0))));
        }
        rows.add(List.of());
        rows.add(List.of("HSN summary"));
        rows.add(List.of("HSN/SAC", "Description", "Unit", "Quantity", "Rate %", "Taxable", "CGST", "SGST", "IGST"));
        for (Object o : (List<Object>) g.get("hsn")) {
            Map<String, Object> m = (Map<String, Object>) o;
            rows.add(List.of(Json.str(m, "hsn"), Json.str(m, "description"), Json.str(m, "unit"), String.valueOf(Json.num(m, "qtyMilli", 0) / 1000.0),
                    String.valueOf(Json.num(m, "rate", 0) / 100.0), r(Json.num(m, "taxable", 0)), r(Json.num(m, "cgst", 0)), r(Json.num(m, "sgst", 0)), r(Json.num(m, "igst", 0))));
        }
        rows.add(List.of());
        rows.add(List.of("Total (credit notes taken off)", "", "", "", "", r(Json.num(g, "taxable", 0)), r(Json.num(g, "cgst", 0)), r(Json.num(g, "sgst", 0)), r(Json.num(g, "igst", 0))));
        return Csv.write(rows);
    }

    public String paymentsCsv() {
        synchronized (store) {
            List<List<String>> rows = new ArrayList<>();
            rows.add(List.of("Receipt", "Date", "Customer", "Mode", "Reference", "Amount", "TDS", "Given to invoices", "Advance left", "Void"));
            List<Payment> list = new ArrayList<>(store.payments.values());
            list.sort(Comparator.comparing((Payment p) -> p.date).thenComparing(p -> p.number));
            for (Payment p : list) {
                Customer c = store.customers.get(p.customerId);
                StringBuilder inv = new StringBuilder();
                for (Alloc a : p.allocations) {
                    Invoice i = store.invoices.get(a.invoiceId);
                    if (inv.length() > 0) inv.append("; ");
                    inv.append(i == null ? "?" : i.number).append(" ").append(r(a.amount));
                }
                rows.add(List.of(p.number, p.date, c == null ? "?" : c.name, p.mode, nz(p.reference), r(p.amount), r(p.tds), inv.toString(),
                        r(p.unapplied()), p.voided ? "VOID: " + p.voidReason : ""));
            }
            return Csv.write(rows);
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
