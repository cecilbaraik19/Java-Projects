package com.cecil.bijak.store;

import com.cecil.bijak.core.Gstin;
import com.cecil.bijak.model.Customer;
import com.cecil.bijak.model.Invoice;
import com.cecil.bijak.model.Item;
import com.cecil.bijak.model.Payment;
import com.cecil.bijak.model.Role;
import com.cecil.bijak.model.Status;
import com.cecil.bijak.model.User;
import com.cecil.bijak.service.Problem;
import com.cecil.bijak.util.Clock;
import com.cecil.bijak.util.Json;
import com.cecil.bijak.util.Money;
import com.cecil.bijak.util.Passwords;
import com.cecil.bijak.web.ApiServer;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The demo company: Koel Valley Packaging Pvt. Ltd., a made-up maker of corrugated boxes in Adityapur (Jamshedpur).
 * 5 staff, 16 customers in 7 states, 14 items, 6 repeating invoices.
 *
 * The history is NOT typed in. It is PLAYED THROUGH THE REAL SERVICES with the clock moved back about 5 1/2 months:
 * every day the automation runs (recurring invoices, reminders), Sales raise invoices, the Accountant approves the
 * big ones and records the payments when each customer "pays" (every customer has a habit: early, on time, late,
 * very late, pays in lumps, takes TDS off). So every number in the reports got there by the normal rules.
 * The last few days of money are left in a BANK STATEMENT to be matched, so the matching page has work to do.
 * Everything is dated relative to now. All companies and people are made up.
 */
public final class SeedData {
    private SeedData() { }

    static final int DAYS = 170;

    /** How a customer pays: days after the due date (mean, spread), chance of paying only half first, lump payer. */
    record Habit(int meanLate, int spread, double partial, boolean lump) { }

    public static void load(ApiServer.Parts p) {
        new Builder(p).run();
    }

    static final class Builder {
        final ApiServer.Parts p;
        final DataStore s;
        final Random rnd = new Random(20261119L);
        final Instant fixedBefore = Clock.fixed();
        final LocalDate end;
        final LocalDate start;
        User meera, arjun, salma, rohit, vikram;
        final Map<String, Customer> cust = new LinkedHashMap<>();
        final Map<String, Item> item = new LinkedHashMap<>();
        final Map<String, Habit> habit = new HashMap<>();
        final Map<String, LocalDate> payOn = new HashMap<>();      // invoice -> the day the customer will pay
        final Map<String, Double> payShare = new HashMap<>();      // invoice -> share paid on that day (1.0 = all)
        final java.util.Set<String> planned = new java.util.HashSet<>();   // invoices whose payment day is already chosen
        final List<String> approveTomorrow = new ArrayList<>();
        final List<String[]> bankRows = new ArrayList<>();        // money of the last days, left for bank matching
        int utr = 4100;
        final java.util.Set<String> done = new java.util.HashSet<>();

        /** True the first time it is asked for this event (scripted events happen on the first working day they can). */
        boolean once(String event) { return done.add(event); }

        Builder(ApiServer.Parts p) {
            this.p = p;
            this.s = p.store();
            this.end = Clock.today();
            this.start = end.minusDays(DAYS);
        }

        /** Moves the clock to that day and time (never past the real "now", so nothing is dated in the future). */
        void at(LocalDate d, int hourUtc, int min) {
            Instant t = d.atTime(hourUtc, min).toInstant(ZoneOffset.UTC);
            Instant real = fixedBefore != null ? fixedBefore : Instant.now();
            if (t.isAfter(real)) t = real.minusSeconds(60);
            Clock.fix(t);
        }

        void run() {
            s.batch = true;
            try {
                at(start, 4, 0);
                people();
                company();
                items();
                customers();
                schedules();
                for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) day(d);
                finish();
            } finally {
                Clock.fix(fixedBefore);
                s.saveAll();
            }
        }

        // ------------------------------------------------------------------ the set-up

        User user(String username, String name, Role role, String password, String email) {
            User u = new User();
            u.id = s.nextId("U", 1);
            u.username = username;
            u.name = name;
            u.role = role;
            u.email = email;
            u.passwordHash = Passwords.hash(password);
            u.createdAt = Clock.millis();
            s.users.put(u.id, u);
            return u;
        }

        void people() {
            meera = user("meera", "Meera Hansdah", Role.OWNER, "owner123", "meera@koelvalley.example");
            arjun = user("arjun", "Arjun Mahato", Role.ACCOUNTANT, "accounts123", "accounts@koelvalley.example");
            salma = user("salma", "Salma Parveen", Role.SALES, "sales123", "salma@koelvalley.example");
            rohit = user("rohit", "Rohit Gope", Role.SALES, "sales123", "rohit@koelvalley.example");
            vikram = user("vikram", "Vikram Agarwal (CA)", Role.AUDITOR, "audit123", "vikram.ca@example.com");
        }

        void company() {
            p.admin().saveSettings(meera, Json.obj(
                    "companyName", "Koel Valley Packaging Pvt. Ltd.",
                    "gstin", Gstin.make("20", "AAHCK4417M", '1'),
                    "address", "Plot 14, Phase IV, Adityapur Industrial Area\nJamshedpur, Jharkhand 832109",
                    "phone", "+91 657 240 1188", "email", "accounts@koelvalley.example",
                    "bankName", "State Bank of India, Adityapur", "bankAccount", "39871204556", "ifsc", "SBIN0001771",
                    "upiId", "koelvalley@sbi",
                    "invoicePrefix", "KVP", "creditPrefix", "KVCN", "receiptPrefix", "KVR",
                    "approvalLimit", "200000", "reminderDays", List.of(-3, 0, 7, 15, 30), "remindersOn", true,
                    "interestPct", 18, "autoApplyAdvance", true,
                    "terms", "1. Goods once sold will not be taken back.\n2. Interest @18% p.a. is charged on payments after the due date.\n3. Subject to Jamshedpur jurisdiction."));
        }

        void it(String key, String name, String hsn, String unit, String rate, int gst, boolean service) {
            Item i = p.customers().saveItem(meera, null, Json.obj("name", name, "hsn", hsn, "unit", unit, "rate", rate, "gstBp", gst, "service", service));
            item.put(key, i);
        }

        void items() {
            it("box3", "3-ply corrugated box 12x10x8 in", "48191010", "Nos", "18.50", 1800, false);
            it("box5", "5-ply corrugated box 18x12x12 in", "48191010", "Nos", "46.00", 1800, false);
            it("box7", "7-ply heavy-duty export box", "48191010", "Nos", "128.00", 1800, false);
            it("sheet", "Corrugated board sheet 1.2 m (roll)", "48081000", "Roll", "2450.00", 1200, false);
            it("kraft", "Kraft paper 120 GSM", "48042900", "Kg", "42.00", 1200, false);
            it("tray", "Moulded pulp egg tray (30)", "48237030", "Nos", "6.20", 1800, false);
            it("honey", "Paper honeycomb pallet 1200x1000", "48239090", "Nos", "1850.00", 1200, false);
            it("tape", "BOPP tape 48 mm x 65 m", "39191000", "Roll", "38.00", 1800, false);
            it("film", "Stretch film 23 micron", "39201012", "Kg", "180.00", 1800, false);
            it("pallet", "Wooden pallet, heat treated (ISPM-15)", "44152000", "Nos", "1450.00", 1200, false);
            it("print", "Flexo printing, 2 colour (per box)", "998912", "Nos", "3.20", 1800, true);
            it("die", "Design and die-making charges", "998391", "Job", "4500.00", 1800, true);
            it("amc", "Packaging line AMC (monthly)", "998719", "Month", "42000.00", 1800, true);
            it("store", "Packing material storage (per pallet-month)", "996729", "Pallet", "530.00", 1800, true);
        }

        Customer c(String key, String name, String state, String pan, String contact, String city, int terms, long limitRs,
                   double tds, Habit h, boolean noRem) {
            Map<String, Object> b = Json.obj("name", name, "contact", contact, "email", key + "@" + name.split(" ")[0].toLowerCase() + ".example",
                    "phone", "+91 9" + (431000000 + rnd.nextInt(99999999)), "billingAddress", city, "termsDays", terms,
                    "creditLimit", String.valueOf(limitRs), "tdsPct", tds, "noReminders", noRem);
            if (pan != null) b.put("gstin", Gstin.make(state, pan, '1'));
            else b.put("state", state);
            Customer x = p.customers().save(key.startsWith("r") ? rohit : salma, null, b);
            cust.put(key, x);
            habit.put(x.id, h);
            return x;
        }

        void customers() {
            c("aac", "Adityapur Auto Components Pvt. Ltd.", "20", "AACCA7781K", "Sunil Bhengra (Stores)", "Plot 3, Small Industries Area, Adityapur 831013", 45, 1500000, 2, new Habit(-2, 4, 0, false), false);
            c("sub", "Subarnarekha Foods LLP", "20", "ABMFS2204E", "Nisha Tirkey", "Ghatsila Road, Jamshedpur 832110", 30, 600000, 0, new Habit(3, 6, 0.1, false), false);
            c("dal", "Dalma Pharma Ltd.", "20", "AABCD6612Q", "Ravi Singh (Purchase)", "Gamharia, Saraikela-Kharsawan 832108", 30, 800000, 0, new Habit(-3, 3, 0, false), false);
            c("hoo", "Hooghly Spice Exports Pvt. Ltd.", "19", "AADCH3390L", "Debashis Sen", "12 Strand Road, Kolkata 700001", 45, 1200000, 0, new Habit(6, 8, 0.2, false), false);
            c("kal", "Kalinga Steel Fabricators", "21", "AAKFK5521C", "Prakash Mohanty", "Kalunga Industrial Estate, Rourkela 770031", 30, 500000, 0, new Habit(12, 10, 0.1, false), false);
            c("gan", "Ganga Agro Mills", "10", "AAGFG8830P", "Manoj Yadav", "Fatuha Industrial Area, Patna 803201", 30, 400000, 0, new Habit(48, 14, 0, true), false);
            c("rai", "Raipur Electricals Pvt. Ltd.", "22", "AABCR1145H", "Anita Sahu", "Urla Industrial Complex, Raipur 493221", 30, 700000, 0, new Habit(8, 6, 0, false), false);
            c("dec", "Deccan Home Appliances Pvt. Ltd.", "29", "AAECD9902M", "Kiran Rao", "Peenya 2nd Stage, Bengaluru 560058", 60, 2000000, 0, new Habit(4, 5, 0, false), false);
            c("sah", "Sahyadri Fulfilment Services Pvt. Ltd.", "27", "AAJCS4471B", "Omkar Patil (Ops)", "Bhiwandi Logistics Park, Thane 421302", 15, 2500000, 0, new Habit(-1, 2, 0, false), false);
            c("rsw", "Ranchi Sweets & Bakers", "20", null, "Pappu Sahu", "Main Road, Ranchi 834001", 0, 0, 0, new Habit(0, 1, 0, false), false);
            c("bok", "Bokaro Ceramics Pvt. Ltd.", "20", "AACCB2290F", "S. K. Jha", "Sector 12, Bokaro Steel City 827012", 30, 500000, 0, new Habit(15, 8, 0, false), false);
            c("jps", "Jamshedpur Public School", "20", null, "The Bursar", "Bistupur, Jamshedpur 831001", 30, 0, 0, new Habit(20, 6, 0, false), true);
            c("cho", "Chotanagpur Honey Co-operative", "20", "AAAAC7761R", "Fr. Anand Lakra", "Khunti 835210", 15, 150000, 0, new Habit(-2, 3, 0, false), false);
            c("sil", "Siliguri Hardware Traders", "19", "ABCFS6650N", "Rajesh Agarwal", "Sevoke Road, Siliguri 734001", 30, 300000, 0, new Habit(52, 14, 0.15, false), false);
            c("pat", "Patna Medicos Distributors", "10", "AAPFP3318G", "Dr. Alok Sinha", "Govind Mitra Road, Patna 800004", 30, 350000, 0, new Habit(5, 5, 0, false), false);
            c("rdh", "Dhanbad Mining Spares Pvt. Ltd.", "20", "AADCD5540J", "Ajay Mahto", "Bank More, Dhanbad 826001", 30, 600000, 2, new Habit(9, 4, 0, false), false);
        }

        Map<String, Object> line(String key, String qty) { return line(key, qty, null, 0); }

        Map<String, Object> line(String key, String qty, String rate, double disc) {
            Item i = item.get(key);
            return Json.obj("itemId", i.id, "qty", qty, "rate", rate == null ? Money.format(i.rate).replace(",", "") : rate, "discount", disc, "gstBp", i.gstBp);
        }

        void schedules() {
            LocalDate firstMonday = start.with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
            p.auto().save(arjun, null, Json.obj("name", "Packaging line AMC", "customerId", cust.get("aac").id, "every", "MONTHLY", "anchorDay", 1,
                    "startDate", start.plusDays(1).toString(), "autoIssue", true, "poNumber", "AAC/AMC/2026-27", "lines", List.of(line("amc", "1"))));
            p.auto().save(arjun, null, Json.obj("name", "Packing material storage", "customerId", cust.get("rdh").id, "every", "MONTHLY", "anchorDay", 31,
                    "startDate", start.toString(), "autoIssue", true, "lines", List.of(line("store", "50"))));
            p.auto().save(arjun, null, Json.obj("name", "Weekly carton supply", "customerId", cust.get("sah").id, "every", "WEEKLY", "anchorDay", 1,
                    "startDate", firstMonday.toString(), "autoIssue", true, "poNumber", "SFS-BLANKET-114",
                    "lines", List.of(line("box5", "1800", "44.50", 0), line("box3", "1200"), line("tape", "120"))));
            p.auto().save(arjun, null, Json.obj("name", "Egg trays - monthly", "customerId", cust.get("sub").id, "every", "MONTHLY", "anchorDay", 15,
                    "startDate", start.toString(), "autoIssue", true, "lines", List.of(line("tray", "16000"))));
            p.auto().save(arjun, null, Json.obj("name", "Quarterly export cartons", "customerId", cust.get("dec").id, "every", "QUARTERLY", "anchorDay", 10,
                    "startDate", start.plusDays(5).toString(), "autoIssue", true,
                    "lines", List.of(line("box7", "1600"), line("print", "1600"), line("pallet", "40"))));
            p.auto().save(arjun, null, Json.obj("name", "Monthly cartons (check before issue)", "customerId", cust.get("rai").id, "every", "MONTHLY", "anchorDay", 5,
                    "startDate", start.toString(), "autoIssue", false, "lines", List.of(line("box5", "900"), line("film", "40"))));
            p.auto().save(arjun, null, Json.obj("name", "Festive gift boxes (Puja season)", "customerId", cust.get("cho").id, "every", "MONTHLY", "anchorDay", 20,
                    "startDate", start.plusDays(60).toString(), "endDate", start.plusDays(130).toString(), "autoIssue", true,
                    "lines", List.of(line("box3", "1500", "21.00", 0), line("print", "1500"))));
        }

        // ------------------------------------------------------------------ one day

        /** What each customer usually buys: item, smallest and largest quantity. */
        static final Object[][] MIX = {
                { "aac", "box5", 400, 1500, "film", 20, 80 },
                { "dal", "box3", 2000, 6000, "print", 2000, 6000 },
                { "hoo", "box7", 300, 1200, "pallet", 10, 30 },
                { "kal", "sheet", 10, 40, "kraft", 300, 1200 },
                { "gan", "box5", 500, 1800, "kraft", 200, 800 },
                { "rai", "box3", 800, 2500, "tape", 50, 200 },
                { "dec", "box5", 1500, 4000, "honey", 10, 40 },
                { "bok", "box7", 200, 700, "honey", 5, 25 },
                { "jps", "box3", 300, 900, "tape", 20, 60 },
                { "sil", "box5", 300, 1200, "tape", 50, 150 },
                { "pat", "box3", 1500, 4000, "film", 10, 40 },
                { "rsw", "box3", 200, 800, "print", 200, 800 },
                { "cho", "box3", 300, 900, "kraft", 50, 150 },
        };

        void day(LocalDate d) {
            boolean workday = d.getDayOfWeek() != DayOfWeek.SUNDAY;
            int left = (int) java.time.temporal.ChronoUnit.DAYS.between(d, end);

            // 10:00 IST: the automation (yesterday's left-overs + today's recurring invoices and reminders)
            at(d, 4, 30);
            p.auto().tick();
            for (Invoice i : s.invoices.values()) if (i.recurringId != null && i.status.booked() && !planned.contains(i.id)) plan(i);

            if (!workday) return;

            // 11:00 IST: approvals from yesterday
            at(d, 5, 30);
            for (String id : new ArrayList<>(approveTomorrow)) {
                Invoice i = s.invoices.get(id);
                if (i != null && i.status == Status.PENDING_APPROVAL) { p.invoices().approve(arjun, id); plan(i); }
            }
            approveTomorrow.clear();
            // the recurring drafts that wait for a check (Raipur): the Accountant issues them the next working day
            for (Invoice i : new ArrayList<>(s.invoices.values()))
                if (i.status == Status.DRAFT && "system".equals(i.createdBy) && left > 3 && Clock.millis() - i.createdAt > 20 * 3_600_000L) {
                    try {
                        p.invoices().issue(arjun, i.id, null);
                    } catch (Problem pr) {
                        if (pr.status != 409) throw pr;
                        p.invoices().issue(arjun, i.id, "Monthly order confirmed by Anita Sahu on phone");
                    }
                    plan(i);
                }

            // 12:00-17:00 IST: Sales raise invoices
            at(d, 6, 30 + rnd.nextInt(20));
            int n = rnd.nextDouble() < 0.62 ? 1 + (rnd.nextDouble() < 0.3 ? 1 : 0) : 0;
            for (int k = 0; k < n; k++) {
                Object[] mix = MIX[rnd.nextInt(MIX.length)];
                manual(mix, rnd.nextBoolean() ? salma : rohit, left);
            }

            scripted(d, left);

            // 16:00 IST: money comes in
            at(d, 10, 30);
            collect(d, left);
        }

        void manual(Object[] mix, User by, int left) {
            Customer c = cust.get((String) mix[0]);
            List<Object> lines = new ArrayList<>();
            lines.add(line((String) mix[1], qty((int) mix[2], (int) mix[3])));
            if (rnd.nextDouble() < 0.7) lines.add(line((String) mix[4], qty((int) mix[5], (int) mix[6])));
            if (rnd.nextDouble() < 0.08) lines.add(line("die", "1"));
            Map<String, Object> body = Json.obj("customerId", c.id, "lines", lines, "poNumber", rnd.nextDouble() < 0.6 ? "PO-" + (2600 + rnd.nextInt(900)) : "");
            Invoice inv = p.invoices().create(by, body);
            try {
                p.invoices().issue(by, inv.id, null);
            } catch (Problem pr) {
                if (pr.status == 409) {                         // over the credit limit: the Accountant decides
                    if (s.outstanding(c.id) > c.creditLimit * 1.15) { p.invoices().deleteDraft(arjun, inv.id); return; }
                    p.invoices().issue(arjun, inv.id, "Regular customer, payment promised this week");
                } else throw pr;
            }
            if (inv.status == Status.PENDING_APPROVAL) approveTomorrow.add(inv.id);
            else plan(inv);
        }

        /** Sales issue; if the customer is over the credit limit, the Accountant issues it with a reason. */
        void issue(User by, Invoice inv) {
            try {
                p.invoices().issue(by, inv.id, null);
            } catch (Problem pr) {
                if (pr.status != 409) throw pr;
                p.invoices().issue(arjun, inv.id, "Old customer - payment of the last bills promised this week");
            }
        }

        String qty(int lo, int hi) {
            int q = lo + rnd.nextInt(Math.max(1, hi - lo));
            int step = q > 1000 ? 100 : q > 100 ? 50 : q > 20 ? 5 : 1;
            return String.valueOf(Math.max(step, q / step * step));
        }

        /** When will the customer pay this invoice? (from their habit) */
        void plan(Invoice i) {
            if (i.creditNote() || i.number == null || !planned.add(i.id)) return;
            Habit h = habit.get(i.customerId);
            int late = (int) Math.round(h.meanLate() + rnd.nextGaussian() * h.spread());
            LocalDate due = LocalDate.parse(i.dueDate);
            LocalDate when = due.plusDays(late);
            if (when.isBefore(LocalDate.parse(i.issueDate))) when = LocalDate.parse(i.issueDate).plusDays(1);
            payOn.put(i.id, when);
            payShare.put(i.id, rnd.nextDouble() < h.partial() ? 0.5 : 1.0);
        }

        void collect(LocalDate d, int left) {
            Map<String, List<Invoice>> due = new LinkedHashMap<>();
            for (Invoice i : s.invoices.values()) {
                LocalDate when = payOn.get(i.id);
                if (when == null || when.isAfter(d) || !i.status.open()) continue;
                Habit h = habit.get(i.customerId);
                if (h.lump() && d.getDayOfMonth() != 6 && d.getDayOfMonth() != 21) continue;   // pays twice a month, everything due
                due.computeIfAbsent(i.customerId, k -> new ArrayList<>()).add(i);
            }
            for (var e : due.entrySet()) {
                Customer c = s.customers.get(e.getKey());
                List<Invoice> list = e.getValue();
                if (habit.get(c.id).lump() && list.size() > 1) { pay(c, list, d, left); continue; }
                for (Invoice i : list) pay(c, List.of(i), d, left);
            }
        }

        void pay(Customer c, List<Invoice> list, LocalDate d, int left) {
            long cash = 0, tds = 0;
            List<Object> alloc = new ArrayList<>();
            for (Invoice i : list) {
                double share = payShare.getOrDefault(i.id, 1.0);
                long give = share < 1 ? Money.div(i.balance() / 2, 100) * 100 : i.balance();
                long t = 0;
                if (c.tdsBp > 0) t = Money.pct(Money.div(i.taxable * give, i.total), c.tdsBp / 100.0);
                cash += give - t;
                tds += t;
                alloc.add(Json.obj("invoiceId", i.id, "amount", Money.format(give)));
                if (share < 1) { payShare.put(i.id, 1.0); payOn.put(i.id, d.plusDays(10 + rnd.nextInt(15))); }
                else payOn.remove(i.id);
            }
            if (left <= 6) {   // the last week: the money is in the bank statement, not yet in the books
                bankRows.add(new String[] { d.toString(), narration(c, list), Money.format(cash).replace(",", "") });
                for (Invoice i : list) { payOn.remove(i.id); payShare.remove(i.id); }
                return;
            }
            String mode = c.tdsBp > 0 || cash > 2_00_000_00L ? "RTGS" : rnd.nextDouble() < 0.15 ? "CHEQUE" : rnd.nextDouble() < 0.2 ? "UPI" : "NEFT";
            p.payments().record(arjun, Json.obj("customerId", c.id, "date", d.toString(), "amount", Money.format(cash),
                    "tds", tds > 0 ? Money.format(tds) : "", "mode", mode, "reference", ref(mode), "allocations", alloc));
        }

        String ref(String mode) {
            utr++;
            return switch (mode) {
                case "CHEQUE" -> "CHQ " + (100000 + utr);
                case "UPI" -> "UPI/6" + (31000000L + utr * 37L);
                case "RTGS" -> "SBINR52026" + (110000 + utr);
                default -> "N3" + (26100000L + utr * 13L);
            };
        }

        /** Bank narrations as banks write them - some with the invoice number, some only with the name. */
        String narration(Customer c, List<Invoice> list) {
            utr++;
            String name = c.name.toUpperCase().replace(".", "").replace("&", "AND");
            if (name.length() > 26) name = name.substring(0, 26);
            String num = list.get(0).number;
            String shortNum = "KVP " + num.substring(num.lastIndexOf('/') + 1);
            return switch (utr % 4) {
                case 0 -> "NEFT CR-HDFC0000" + (240 + utr % 90) + "-" + name + "-" + shortNum;
                case 1 -> "RTGS-UTIBR52026" + (31000 + utr) + "-" + name;
                case 2 -> "IMPS/P2A/" + (630000000 + utr) + "/" + name.split(" ")[0] + "/" + num.replace("/", "");
                default -> "NEFT-" + "ICIC" + (700000 + utr) + "-" + name + " INV " + num;
            };
        }

        // ------------------------------------------------------------------ events with a story

        void scripted(LocalDate d, int left) {
            int day = DAYS - left;
            if (day >= 22 && once("cancel")) {   // wrong customer -> cancelled and raised again
                Invoice wrong = p.invoices().create(rohit, Json.obj("customerId", cust.get("pat").id, "lines", List.of(line("box3", "2500"), line("film", "30"))));
                issue(rohit, wrong);
                at(d, 8, 10);
                p.invoices().cancel(arjun, wrong.id, "Raised on the wrong customer - goods went to Patna Medicos' sister firm. Raised again on the right GSTIN.");
            }
            if (day >= 41 && !done.contains("return")) {   // damaged boxes -> credit note (on the first Hooghly invoice after day 41)
                Invoice inv = lastOf("hoo");
                if (inv != null) {
                    done.add("return");
                    long q = Math.max(1, inv.lines.get(0).qtyMilli / 1000 / 8);
                    p.invoices().creditNote(arjun, inv.id, Json.obj("reason", q + " boxes crushed in transit (LR 55213) - returned",
                            "lines", List.of(Json.obj("src", 0, "qty", String.valueOf(q)))));
                }
            }
            if (day >= 73 && once("bounce")) {   // a cheque bounces
                Invoice inv = s.invoices.values().stream().filter(i -> i.customerId.equals(cust.get("gan").id) && i.status.open()).findFirst().orElse(null);
                if (inv != null) {
                    Payment pay = p.payments().record(arjun, Json.obj("customerId", cust.get("gan").id, "date", d.toString(), "amount", Money.format(inv.balance()),
                            "mode", "CHEQUE", "reference", "CHQ 004417", "allocations", List.of(Json.obj("invoiceId", inv.id, "amount", Money.format(inv.balance())))));
                    at(d.plusDays(0), 11, 40);
                    p.payments().voidPayment(arjun, pay.id, "Cheque 004417 returned unpaid - funds insufficient (bank charge Rs. 590)");
                }
            }
            if (left <= 20 && once("advance")) {  // Ranchi Sweets pays first (advance), the invoice comes later and uses it
                p.payments().record(arjun, Json.obj("customerId", cust.get("rsw").id, "date", d.toString(), "amount", "10000", "mode", "UPI",
                        "reference", "UPI/631887702145", "notes", "Advance for Diwali sweet boxes"));
            }
            if (left <= 18 && once("advanceInvoice")) {
                Invoice inv = p.invoices().create(salma, Json.obj("customerId", cust.get("rsw").id, "notes", "Diwali sweet boxes with print",
                        "lines", List.of(line("box3", "400", "18.50", 0), line("print", "400"))));
                issue(salma, inv);
                plan(inv);
            }
            if (left <= 12 && once("hold")) {  // a dispute: on hold, no reminders
                Invoice inv = p.invoices().create(rohit, Json.obj("customerId", cust.get("bok").id, "poNumber", "BCPL/PUR/0912",
                        "lines", List.of(line("box7", "600"), line("honey", "20"))));
                issue(rohit, inv);
                payOn.remove(inv.id);
                at(d.plusDays(0), 9, 0);
                p.invoices().hold(arjun, inv.id, true, "Customer says 120 boxes were short in the delivery - checking the gate pass with the transporter.");
            }
            if (left <= 3 && once("priceCut")) {   // a price correction (rate on the invoice was higher than the agreed rate)
                Invoice inv = lastOf("dal");
                if (inv != null && inv.status.open()) {
                    Map<String, Object> l = Json.obj("src", 0, "qty", String.valueOf(inv.lines.get(0).qtyMilli / 1000), "rate", "1.50");
                    p.invoices().creditNote(arjun, inv.id, Json.obj("reason", "Rate difference: agreed Rs. 17.00 per box (PO), billed Rs. 18.50", "lines", List.of(l)));
                }
            }
        }

        Invoice lastOf(String key) {
            Invoice last = null;
            for (Invoice i : s.invoices.values())
                if (i.customerId.equals(cust.get(key).id) && !i.creditNote() && i.status.booked()) last = i;
            return last;
        }

        // ------------------------------------------------------------------ today

        void finish() {
            at(end, 5, 50);
            // Salma: one invoice waiting for approval, one sent back to her, one draft she is still typing
            Invoice big = p.invoices().create(salma, Json.obj("customerId", cust.get("hoo").id, "poNumber", "HSE/EXP/2611",
                    "notes", "Export consignment - container 2 of 3", "lines", List.of(line("box7", "1800"), line("pallet", "24"), line("print", "1800"))));
            issue(salma, big);
            at(end.minusDays(1), 7, 15);
            Invoice back = p.invoices().create(salma, Json.obj("customerId", cust.get("kal").id, "poNumber", "KSF-778",
                    "lines", List.of(line("sheet", "60"), line("kraft", "1500"))));
            issue(salma, back);
            at(end.minusDays(1), 9, 40);
            if (back.status == Status.PENDING_APPROVAL)
                p.invoices().sendBack(arjun, back.id, "Kalinga's PO KSF-778 says 50 rolls, not 60 - please check and change line 1.");
            at(end, 6, 5);
            p.invoices().create(salma, Json.obj("customerId", cust.get("sil").id, "lines", List.of(line("box5", "600"), line("tape", "80"))));

            // the bank statement of the last week (with noise: bank interest, a refund, a debit, a line already imported)
            StringBuilder csv = new StringBuilder("State Bank of India - Account 39871204556 - Koel Valley Packaging Pvt Ltd\n");
            csv.append("Txn Date,Value Date,Description,Ref No./Cheque No.,Debit,Credit,Balance\n");
            long bal = 18_42_315_00L;
            List<String[]> rows = new ArrayList<>(bankRows);
            rows.add(new String[] { end.minusDays(4).toString(), "BY TRANSFER-INT CREDIT SB-SWEEP 39871204556", "1842.00" });
            // one customer pays two invoices together and writes no number: Bijak finds the pair (subset sum)
            for (String key : List.of("kal", "pat", "rai", "gan", "hoo")) {
                List<Invoice> open = new ArrayList<>();
                for (Invoice i : s.invoices.values()) if (i.customerId.equals(cust.get(key).id) && !i.creditNote() && i.status.open()) open.add(i);
                if (open.size() < 2) continue;
                open.sort((a, b) -> a.dueDate.compareTo(b.dueDate));
                long two = open.get(0).balance() + open.get(1).balance();
                String nm = cust.get(key).name.toUpperCase().replace(".", "");
                rows.add(new String[] { end.minusDays(1).toString(), "RTGS CR-PUNB0" + (10000 + utr) + "-" + (nm.length() > 24 ? nm.substring(0, 24) : nm) + "-PYMT",
                        Money.format(two).replace(",", "") });
                break;
            }
            rows.add(new String[] { end.minusDays(2).toString(), "NEFT CR-SBIN0004431-JHARKHAND GAS AGENCY-REFUND SECURITY DEP", "12500.00" });
            rows.sort((a, b) -> a[0].compareTo(b[0]));
            for (String[] r : rows) {
                long amt = Money.parse(r[2]);
                bal += amt;
                csv.append(bankDate(r[0])).append(',').append(bankDate(r[0])).append(',').append(r[1]).append(",,,").append(r[2]).append(',').append(Money.format(bal).replace(",", "")).append('\n');
            }
            csv.append(bankDate(end.minusDays(1).toString())).append(',').append(bankDate(end.minusDays(1).toString()))
               .append(",TO NEFT-JSEB ELECTRICITY BILL NOV,,87455.00,,").append(Money.format(bal - 87455_00L).replace(",", "")).append('\n');
            at(end, 6, 40);
            p.payments().importStatement(arjun, csv.toString());
            if (!rows.isEmpty()) {   // the same file uploaded twice by mistake: Bijak skips the lines it already has
                p.payments().importStatement(arjun, csv.toString());
            }
            at(end, 7, 0);
            p.auto().tick();
        }

        static String bankDate(String iso) {
            LocalDate d = LocalDate.parse(iso);
            String[] m = { "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec" };
            return String.format("%02d-%s-%d", d.getDayOfMonth(), m[d.getMonthValue() - 1], d.getYear());
        }
    }
}
