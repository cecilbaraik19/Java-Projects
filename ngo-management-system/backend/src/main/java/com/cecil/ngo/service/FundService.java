package com.cecil.ngo.service;

import com.cecil.ngo.model.Donation;
import com.cecil.ngo.model.Donor;
import com.cecil.ngo.model.Enums;
import com.cecil.ngo.model.Enums.DonorType;
import com.cecil.ngo.model.Enums.ExpenseCategory;
import com.cecil.ngo.model.Enums.PayMode;
import com.cecil.ngo.model.Enums.ProgramStatus;
import com.cecil.ngo.model.Enums.Review;
import com.cecil.ngo.model.Enums.Role;
import com.cecil.ngo.model.Expense;
import com.cecil.ngo.model.Program;
import com.cecil.ngo.model.User;
import com.cecil.ngo.store.DataStore;
import com.cecil.ngo.util.Csv;
import com.cecil.ngo.util.Json;
import com.cecil.ngo.util.Money;
import com.cecil.ngo.util.Text;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Money coming in (donors, donations, receipts) and money going out (expenses).
 * Two safety rules for expenses:
 *  1. Nobody can approve their own expense (one person submits, a different person approves).
 *  2. An expense that would take a program over its budget can only be approved by the admin, with a note.
 */
public class FundService {
    private final DataStore store;

    public FundService(DataStore store) { this.store = store; }

    // ================================================================== donors

    private Map<String, Object> donorView(Donor d) {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) d.toJson();
        long total = 0; int count = 0; String last = null;
        for (Donation x : store.donations) {
            if (!x.donorId.equals(d.id)) continue;
            total += x.amount; count++;
            if (last == null || x.date.compareTo(last) > 0) last = x.date;
        }
        m.put("total", total);
        m.put("gifts", count);
        m.put("lastGift", last);
        m.put("typeLabel", d.type.label);
        return m;
    }

    public List<Map<String, Object>> donors(User me, Map<String, String> q) {
        AuthService.needStaff(me, "see donors");
        String text = q.getOrDefault("q", "").trim().toLowerCase(Locale.ROOT), type = q.getOrDefault("type", "");
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (store) {
            for (Donor d : store.donors) {
                if (!type.isEmpty() && !d.type.name().equals(type)) continue;
                if (!text.isEmpty() && !(d.name + " " + d.city + " " + d.email + " " + d.id).toLowerCase(Locale.ROOT).contains(text)) continue;
                out.add(donorView(d));
            }
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> -((Number) m.get("total")).longValue()));
        return out;
    }

    public Map<String, Object> donor(User me, String id) {
        AuthService.needStaff(me, "see donors");
        synchronized (store) {
            Donor d = mustDonor(id);
            Map<String, Object> m = donorView(d);
            List<Object> gifts = new ArrayList<>();
            store.donations.stream().filter(x -> x.donorId.equals(id)).sorted(Comparator.comparing((Donation x) -> x.date).reversed())
                    .forEach(x -> gifts.add(donationView(x)));
            m.put("donations", gifts);
            return m;
        }
    }

    public Map<String, Object> saveDonor(User me, String id, Map<String, Object> b) {
        AuthService.needFinance(me, "add or edit donors");
        synchronized (store) {
            Donor d = id == null ? new Donor() : mustDonor(id);
            String name = Text.line(Json.str(b, "name"), "Donor name", 100, true);
            DonorType type = Enums.parse(DonorType.class, Json.str(b, "type"), "Donor type");
            String email = Text.email(Json.str(b, "email"));
            String phone = Text.phone(Json.str(b, "phone"));
            String city = Text.line(Json.str(b, "city"), "City", 60, false);
            for (Donor o : store.donors) {
                if (o == d) continue;
                if (!email.isEmpty() && email.equals(o.email)) throw ApiError.conflict("Donor " + o.name + " already has this email");
                if (Text.key(o.name).equals(Text.key(name)) && Text.key(o.city).equals(Text.key(city)))
                    throw ApiError.conflict("A donor with this name and city already exists (" + o.id + ")");
            }
            d.name = name; d.type = type; d.email = email; d.phone = phone; d.city = city;
            if (id == null) {
                d.id = store.newId("D", 3);
                d.createdAt = store.now();
                store.donors.add(d);
            }
            store.saveDonors();
            store.log(me.username, id == null ? "added donor" : "edited donor", d.name, "donor:" + d.id);
            return donorView(d);
        }
    }

    public void deleteDonor(User me, String id) {
        AuthService.needFinance(me, "delete donors");
        synchronized (store) {
            Donor d = mustDonor(id);
            if (store.donations.stream().anyMatch(x -> x.donorId.equals(id))) throw ApiError.conflict("This donor has donations on record and cannot be deleted");
            store.donors.remove(d);
            store.saveDonors();
            store.log(me.username, "deleted donor", d.name, null);
        }
    }

    Donor mustDonor(String id) {
        Donor d = store.donor(id);
        if (d == null) throw ApiError.notFound("Donor not found");
        return d;
    }

    // ================================================================== donations

    private Map<String, Object> donationView(Donation x) {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) x.toJson();
        Donor d = store.donor(x.donorId);
        Program p = x.programId == null ? null : store.program(x.programId);
        m.put("donorName", d == null ? x.donorId : d.name);
        m.put("donorType", d == null ? null : d.type);
        m.put("programName", p == null ? "General fund" : p.name);
        m.put("modeLabel", x.mode.label);
        return m;
    }

    /** Filters: from, to, program ("GENERAL" = general fund), mode, q. */
    public List<Map<String, Object>> donations(User me, Map<String, String> q) {
        AuthService.needStaff(me, "see donations");
        LocalDate from = Text.date(q.get("from"), "From", false), to = Text.date(q.get("to"), "To", false);
        String program = q.getOrDefault("program", ""), mode = q.getOrDefault("mode", "");
        String text = q.getOrDefault("q", "").trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (store) {
            List<Donation> list = new ArrayList<>(store.donations);
            list.sort(Comparator.comparing((Donation x) -> x.date).thenComparing(x -> x.id).reversed());
            for (Donation x : list) {
                if (!Calc.inRange(x.date, from, to)) continue;
                if ("GENERAL".equals(program) && x.programId != null) continue;
                if (!program.isEmpty() && !"GENERAL".equals(program) && !program.equals(x.programId)) continue;
                if (!mode.isEmpty() && !x.mode.name().equals(mode)) continue;
                Map<String, Object> m = donationView(x);
                if (!text.isEmpty() && !(m.get("donorName") + " " + x.receiptNo + " " + x.reference).toLowerCase(Locale.ROOT).contains(text)) continue;
                out.add(m);
            }
        }
        return out;
    }

    /**
     * Records a donation and gives it the next receipt number for its financial year,
     * like RCPT/2026-27/0012. Cash above ₹2,000 is refused (Indian tax rules do not allow 80G on it).
     */
    public Map<String, Object> recordDonation(User me, Map<String, Object> b) {
        AuthService.needFinance(me, "record donations");
        synchronized (store) {
            Donor d = mustDonor(Json.str(b, "donorId"));
            long amount = Money.amount(Json.longOrNull(b, "amount"), "Amount");
            LocalDate date = Text.date(Json.str(b, "date"), "Date", true);
            if (date.isAfter(store.today())) throw ApiError.bad("Date cannot be in the future");
            PayMode mode = Enums.parse(PayMode.class, Json.str(b, "mode"), "Payment mode");
            if (mode == PayMode.CASH && amount > 2000) throw ApiError.bad("Cash donations above ₹2,000 are not allowed. Use UPI, bank or cheque.");
            String ref = Text.line(Json.str(b, "reference"), "Reference", 60, false);
            if (mode != PayMode.CASH && ref.isEmpty()) throw ApiError.bad("Enter the UTR / cheque / transaction number");
            String programId = Json.str(b, "programId");
            if (programId != null && programId.isBlank()) programId = null;
            if (programId != null) {
                Program p = store.program(programId);
                if (p == null) throw ApiError.bad("Program not found");
                if (p.status == ProgramStatus.COMPLETED) throw ApiError.conflict("This program is completed. Record the gift to the general fund instead.");
            }
            if (!ref.isEmpty())
                for (Donation o : store.donations)
                    if (o.mode == mode && ref.equalsIgnoreCase(o.reference))
                        throw ApiError.conflict("This reference number is already recorded on " + o.receiptNo);
            Donation x = new Donation();
            x.id = store.newId("G", 4);
            x.receiptNo = nextReceipt(date);
            x.donorId = d.id;
            x.amount = amount;
            x.date = date.toString();
            x.mode = mode;
            x.programId = programId;
            x.reference = ref;
            x.note = Text.line(Json.str(b, "note"), "Note", 300, false);
            x.recordedBy = me.username;
            x.recordedAt = store.now();
            store.donations.add(x);
            store.saveDonations();
            store.log(me.username, "recorded donation", Money.format(amount) + " from " + d.name + " (" + x.receiptNo + ")", "donation:" + x.id);
            return donationView(x);
        }
    }

    /** Next receipt number in the financial year of this date. */
    String nextReceipt(LocalDate date) {
        String fy = Money.financialYear(date);
        String prefix = "RCPT/" + fy + "/";
        int max = store.settings.lastReceipt.getOrDefault(fy, 0);
        for (Donation o : store.donations) {
            if (o.receiptNo == null || !o.receiptNo.startsWith(prefix)) continue;
            try { max = Math.max(max, Integer.parseInt(o.receiptNo.substring(prefix.length()))); } catch (NumberFormatException ignored) { /* skip */ }
        }
        store.settings.lastReceipt.put(fy, max + 1);
        store.saveSettings();
        return prefix + String.format("%04d", max + 1);
    }

    /** Everything needed to print a receipt. */
    public Map<String, Object> receipt(User me, String id) {
        AuthService.needStaff(me, "see receipts");
        synchronized (store) {
            Donation x = store.donation(id);
            if (x == null) throw ApiError.notFound("Donation not found");
            Map<String, Object> m = donationView(x);
            Donor d = store.donor(x.donorId);
            m.put("donor", d);
            m.put("amountText", Money.format(x.amount));
            m.put("amountWords", Money.inWords(x.amount));
            m.put("org", store.settings);
            m.put("financialYear", Money.financialYear(LocalDate.parse(x.date)));
            return m;
        }
    }

    /** Only the admin can remove a donation (for a wrong entry). The receipt number is not reused. */
    public void deleteDonation(User me, String id) {
        AuthService.needAdmin(me, "delete donations");
        synchronized (store) {
            Donation x = store.donation(id);
            if (x == null) throw ApiError.notFound("Donation not found");
            store.donations.remove(x);
            store.saveDonations();
            store.log(me.username, "deleted donation", x.receiptNo + " " + Money.format(x.amount), null);
        }
    }

    public String exportDonationsCsv(User me, Map<String, String> q) {
        AuthService.needFinance(me, "export donations");
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(Csv.row("Receipt", "Date", "Donor", "Donor type", "Amount", "Mode", "Reference", "For", "Note"));
        for (Map<String, Object> m : donations(me, q))
            sb.append(Csv.row(m.get("receiptNo"), m.get("date"), m.get("donorName"), m.get("donorType"), m.get("amount"),
                    m.get("modeLabel"), m.get("reference"), m.get("programName"), m.get("note")));
        return sb.toString();
    }

    // ================================================================== expenses

    private Map<String, Object> expenseView(Expense e) {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) e.toJson();
        Program p = store.program(e.programId);
        long spent = Calc.spent(store, e.programId, null, null);
        m.put("programName", p == null ? e.programId : p.name);
        m.put("categoryLabel", e.category.label);
        m.put("budget", p == null ? 0 : p.budget);
        m.put("budgetLeft", p == null ? 0 : p.budget - spent);
        m.put("overBudget", e.status == Review.PENDING && p != null && spent + e.amount > p.budget);
        return m;
    }

    /** Filters: status, program, category, from, to, mine=1. */
    public List<Map<String, Object>> expenses(User me, Map<String, String> q) {
        AuthService.needStaff(me, "see expenses");
        String status = q.getOrDefault("status", ""), program = q.getOrDefault("program", ""), cat = q.getOrDefault("category", "");
        LocalDate from = Text.date(q.get("from"), "From", false), to = Text.date(q.get("to"), "To", false);
        boolean mine = "1".equals(q.get("mine"));
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (store) {
            List<Expense> list = new ArrayList<>(store.expenses);
            list.sort(Comparator.comparing((Expense e) -> e.status != Review.PENDING).thenComparing((Expense e) -> e.date, Comparator.reverseOrder()));
            for (Expense e : list) {
                if (!status.isEmpty() && !e.status.name().equals(status)) continue;
                if (!program.isEmpty() && !e.programId.equals(program)) continue;
                if (!cat.isEmpty() && !e.category.name().equals(cat)) continue;
                if (mine && !me.username.equals(e.submittedBy)) continue;
                if (!Calc.inRange(e.date, from, to)) continue;
                out.add(expenseView(e));
            }
        }
        return out;
    }

    public Map<String, Object> submitExpense(User me, Map<String, Object> b) {
        AuthService.needStaff(me, "submit expenses");
        synchronized (store) {
            Program p = store.program(Json.str(b, "programId"));
            if (p == null) throw ApiError.bad("Choose a program");
            if (p.status == ProgramStatus.COMPLETED) throw ApiError.conflict("This program is completed and its accounts are closed");
            Expense e = new Expense();
            e.programId = p.id;
            e.category = Enums.parse(ExpenseCategory.class, Json.str(b, "category"), "Category");
            e.amount = Money.amount(Json.longOrNull(b, "amount"), "Amount");
            LocalDate d = Text.date(Json.str(b, "date"), "Date", true);
            if (d.isAfter(store.today())) throw ApiError.bad("Date cannot be in the future");
            if (d.isBefore(LocalDate.parse(p.startDate))) throw ApiError.bad("Date is before the program started");
            e.date = d.toString();
            e.vendor = Text.line(Json.str(b, "vendor"), "Paid to", 100, true);
            e.description = Text.line(Json.str(b, "description"), "What for", 300, true);
            e.id = store.newId("E", 4);
            e.submittedBy = me.username;
            e.submittedAt = store.now();
            store.expenses.add(e);
            store.saveExpenses();
            store.log(me.username, "submitted expense", Money.format(e.amount) + " - " + e.description, "program:" + p.id);
            return expenseView(e);
        }
    }

    public Map<String, Object> reviewExpense(User me, String id, Map<String, Object> b) {
        AuthService.needFinance(me, "approve expenses");
        boolean approve = Json.bool(b, "approve", false);
        synchronized (store) {
            Expense e = store.expense(id);
            if (e == null) throw ApiError.notFound("Expense not found");
            if (e.status != Review.PENDING) throw ApiError.conflict("This expense is already " + e.status.label.toLowerCase());
            if (me.username.equals(e.submittedBy)) throw ApiError.forbidden("You cannot approve or reject your own expense. Another person must check it.");
            String note = Text.line(Json.str(b, "note"), "Note", 300, false);
            Program p = store.program(e.programId);
            if (approve) {
                long spent = Calc.spent(store, e.programId, null, null);
                if (p != null && spent + e.amount > p.budget) {
                    if (me.role != Role.ADMIN)
                        throw ApiError.conflict("This would take " + p.name + " over its budget by " + Money.format(spent + e.amount - p.budget) + ". Only the admin can approve it.");
                    if (note.isEmpty()) throw ApiError.bad("Write a note saying why the over-budget spend is allowed");
                }
            } else if (note.isEmpty()) throw ApiError.bad("Write a short reason for rejecting");
            e.status = approve ? Review.APPROVED : Review.REJECTED;
            e.reviewedBy = me.username;
            e.reviewNote = note;
            store.saveExpenses();
            store.log(me.username, approve ? "approved expense" : "rejected expense", e.id + " " + Money.format(e.amount), "program:" + e.programId);
            return expenseView(e);
        }
    }

    /** The person who submitted it can take back an expense while it is still waiting. */
    public void deleteExpense(User me, String id) {
        synchronized (store) {
            Expense e = store.expense(id);
            if (e == null) throw ApiError.notFound("Expense not found");
            if (e.status != Review.PENDING) throw ApiError.conflict("Only waiting expenses can be withdrawn");
            if (!me.username.equals(e.submittedBy) && me.role != Role.ADMIN) throw ApiError.forbidden("Only the person who submitted it can withdraw it");
            store.expenses.remove(e);
            store.saveExpenses();
            store.log(me.username, "withdrew expense", e.id + " " + Money.format(e.amount), "program:" + e.programId);
        }
    }

    public String exportExpensesCsv(User me, Map<String, String> q) {
        AuthService.needStaff(me, "export expenses");
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(Csv.row("ID", "Date", "Program", "Category", "Amount", "Paid to", "What for", "Status", "Submitted by", "Reviewed by", "Note"));
        for (Map<String, Object> m : expenses(me, q))
            sb.append(Csv.row(m.get("id"), m.get("date"), m.get("programName"), m.get("categoryLabel"), m.get("amount"), m.get("vendor"),
                    m.get("description"), m.get("status"), m.get("submittedBy"), m.get("reviewedBy"), m.get("reviewNote")));
        return sb.toString();
    }

    // ================================================================== fund position

    /** Money in, money out and the balance, for the whole NGO and for each program. */
    public Map<String, Object> position(User me) {
        AuthService.needStaff(me, "see the fund position");
        synchronized (store) {
            long in = 0, general = 0, out = 0, waiting = 0;
            for (Donation d : store.donations) { in += d.amount; if (d.programId == null) general += d.amount; }
            for (Expense e : store.expenses) {
                if (e.status == Review.APPROVED) out += e.amount;
                if (e.status == Review.PENDING) waiting += e.amount;
            }
            List<Object> rows = new ArrayList<>();
            for (Program p : store.programs) {
                long raised = Calc.raised(store, p.id, null, null), spent = Calc.spent(store, p.id, null, null);
                rows.add(Json.map("id", p.id, "name", p.name, "budget", p.budget, "raised", raised, "spent", spent,
                        "used", Money.percent(spent, p.budget), "status", p.status));
            }
            Map<String, Object> byMode = new LinkedHashMap<>();
            for (PayMode m : PayMode.values()) {
                long t = 0;
                for (Donation d : store.donations) if (d.mode == m) t += d.amount;
                byMode.put(m.name(), t);
            }
            return Json.map("received", in, "general", general, "spent", out, "waiting", waiting, "balance", in - out,
                    "programs", rows, "byMode", byMode);
        }
    }
}
