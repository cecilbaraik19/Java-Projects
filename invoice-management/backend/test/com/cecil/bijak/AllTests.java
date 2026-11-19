package com.cecil.bijak;

import com.cecil.bijak.core.BankMatch;
import com.cecil.bijak.core.Dunning;
import com.cecil.bijak.core.FinYear;
import com.cecil.bijak.core.Gstin;
import com.cecil.bijak.core.Schedule;
import com.cecil.bijak.core.Tax;
import com.cecil.bijak.model.Alloc;
import com.cecil.bijak.model.BankLine;
import com.cecil.bijak.model.Customer;
import com.cecil.bijak.model.Invoice;
import com.cecil.bijak.model.Item;
import com.cecil.bijak.model.Line;
import com.cecil.bijak.model.Mail;
import com.cecil.bijak.model.Payment;
import com.cecil.bijak.model.Recurring;
import com.cecil.bijak.model.Role;
import com.cecil.bijak.model.Status;
import com.cecil.bijak.model.User;
import com.cecil.bijak.service.Problem;
import com.cecil.bijak.store.DataStore;
import com.cecil.bijak.store.SeedData;
import com.cecil.bijak.util.Clock;
import com.cecil.bijak.util.Csv;
import com.cecil.bijak.util.Json;
import com.cecil.bijak.util.Money;
import com.cecil.bijak.util.Passwords;
import com.cecil.bijak.web.ApiServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * All the tests, with no JUnit download needed: run  run-tests.bat  or  ./run-tests.sh.
 * The clock is frozen at Monday 16 November 2026, 06:00 UTC (11:30 in India).
 */
public final class AllTests {
    private static int passed, failed;
    private static final List<String> failures = new ArrayList<>();
    static final Instant START = Instant.parse("2026-11-16T06:00:00Z");
    static final long H = 3_600_000L, D = 24 * H;

    interface Test { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        run("JSON reads and writes", AllTests::json);
        run("Money: paise, Indian commas, rounding", AllTests::money);
        run("Amount in words (lakh and crore)", AllTests::words);
        run("GSTIN: check character, state, PAN, typing mistakes", AllTests::gstin);
        run("GST: CGST+SGST in the state, IGST outside, discount, round off", AllTests::tax);
        run("Financial year and invoice numbers", AllTests::finYear);
        run("Repeating dates: 31st, leap year, quarterly, weekly", AllTests::schedule);
        run("Reminder ladder and interest", AllTests::dunning);
        run("Bank matching: invoice numbers, names, TDS, subset sum", AllTests::bankMatch);
        run("CSV: quotes, commas and the formula guard", AllTests::csv);
        run("Customers and items: GSTIN, duplicates, HSN/SAC", AllTests::customers);
        run("Drafts: checks on the form", AllTests::drafts);
        run("Issue: gap-free numbers, date order, locked content", AllTests::issue);
        run("Approval above the limit, four eyes, send back", AllTests::approval);
        run("Credit limit: Sales stopped, Accountant writes why", AllTests::creditLimit);
        run("Credit notes: per-line limit, applied, rest becomes advance", AllTests::creditNotes);
        run("Cancel only when nothing is paid or credited", AllTests::cancel);
        run("Payments: oldest first, chosen invoices, TDS, checks", AllTests::payments);
        run("Advance is used by the next invoice", AllTests::advance);
        run("Void payment opens the invoice again", AllTests::voidPayment);
        run("Recurring: catch up in date order, month-end, draft mode, end date", AllTests::recurring);
        run("Reminders: once per level, only the highest after a break, hold", AllTests::reminders);
        run("Bank statement: columns, debits, duplicates, accept, auto-match", AllTests::bankImport);
        run("Statement of account and ageing", AllTests::statement);
        run("GST report: B2B, B2C, credit notes taken off, HSN", AllTests::gstReport);
        run("Roles: Auditor reads only, Sales cannot touch money", AllTests::roles);
        run("Sign-in: wrong passwords lock the account", AllTests::lockout);
        run("16 threads issue at once: 16 different numbers, no gaps", AllTests::threads);
        run("Web server: CSRF, cookies, JSON only, no path tricks", AllTests::web);
        run("Demo company: built through the rules, every total adds up", AllTests::demo);
        Clock.fix(null);
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        for (String f : failures) System.out.println("  FAILED: " + f);
        if (failed > 0) System.exit(1);
    }

    static void run(String name, Test t) {
        Clock.fix(START);
        try {
            t.run();
            passed++;
            System.out.println("  ok   " + name);
        } catch (Throwable e) {
            failed++;
            failures.add(name + " - " + e);
            System.out.println("  FAIL " + name + " - " + e);
            e.printStackTrace(System.out);
        }
    }

    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    static void eq(Object want, Object got, String what) {
        if (want == null ? got != null : !want.equals(got)) throw new AssertionError(what + ": expected " + want + " but got " + got);
    }

    static Problem refused(Runnable r, int status, String what) {
        try {
            r.run();
        } catch (Problem p) {
            if (status != 0 && p.status != status) throw new AssertionError(what + ": status " + p.status + " (" + p.getMessage() + ")");
            return p;
        }
        throw new AssertionError(what + ": was allowed");
    }

    static LocalDate today() { return Clock.today(); }

    // ------------------------------------------------------------------ a small company for the tests

    static final class W {
        final DataStore store = new DataStore(null);
        final ApiServer.Parts p = App.parts(store);
        final User owner, acc, acc2, sales, audit;
        Customer local, outside, b2c, tdsCust;
        Item box, svc;

        W() {
            owner = user("owner", Role.OWNER);
            acc = user("acc", Role.ACCOUNTANT);
            acc2 = user("acc2", Role.ACCOUNTANT);
            sales = user("sales", Role.SALES);
            audit = user("audit", Role.AUDITOR);
            p.admin().saveSettings(owner, Json.obj("companyName", "Test Packers", "gstin", Gstin.make("20", "AAACT1234A", '1'),
                    "invoicePrefix", "TP", "creditPrefix", "TCN", "receiptPrefix", "TR", "approvalLimit", "100000",
                    "reminderDays", List.of(-3, 0, 7, 15, 30), "interestPct", 18, "autoApplyAdvance", true,
                    "bankAccount", "1234567890", "bankName", "Test Bank", "ifsc", "SBIN0000001"));
            local = cust("Jharkhand Buyer Pvt Ltd", Gstin.make("20", "AABCJ1111B", '1'), 30, "0", 0);
            outside = cust("Mumbai Buyer Pvt Ltd", Gstin.make("27", "AABCM2222C", '1'), 30, "0", 0);
            b2c = p.customers().save(sales, null, Json.obj("name", "Corner Shop", "state", "20", "termsDays", 0, "email", "shop@x.example"));
            tdsCust = cust("Service Taker Ltd", Gstin.make("20", "AABCS3333D", '1'), 15, "0", 2);
            box = p.customers().saveItem(owner, null, Json.obj("name", "Box", "hsn", "4819", "unit", "Nos", "rate", "100", "gstBp", 1800));
            svc = p.customers().saveItem(owner, null, Json.obj("name", "AMC", "hsn", "998719", "service", true, "unit", "Month", "rate", "10000", "gstBp", 1800));
        }

        User user(String name, Role r) {
            User u = new User();
            u.id = store.nextId("U", 1);
            u.username = name;
            u.name = name.substring(0, 1).toUpperCase() + name.substring(1) + " Test";
            u.role = r;
            u.passwordHash = Passwords.hash("secret123");
            store.users.put(u.id, u);
            return u;
        }

        Customer cust(String name, String gstin, int terms, String limit, double tds) {
            return p.customers().save(sales, null, Json.obj("name", name, "gstin", gstin, "termsDays", terms, "creditLimit", limit,
                    "tdsPct", tds, "email", "a@b.example"));
        }

        Map<String, Object> line(Item i, String qty) { return Json.obj("itemId", i.id, "qty", qty, "rate", Money.format(i.rate).replace(",", ""), "gstBp", i.gstBp); }

        Invoice draft(User by, Customer c, Map<String, Object> line) {
            return p.invoices().create(by, Json.obj("customerId", c.id, "lines", List.of(line)));
        }

        /** An issued invoice of `qty` boxes (Rs. 100 + 18 % = Rs. 118 each). */
        Invoice inv(Customer c, int qty) {
            Invoice d = draft(acc, c, line(box, String.valueOf(qty)));
            return p.invoices().issue(acc, d.id, null);
        }

        Payment pay(Customer c, String amount, Object allocations) {
            Map<String, Object> b = Json.obj("customerId", c.id, "amount", amount, "mode", "NEFT", "reference", "UTR" + store.payments.size() + "X" + System.nanoTime() % 100000);
            if (allocations != null) b.put("allocations", allocations);
            return p.payments().record(acc, b);
        }
    }

    // ------------------------------------------------------------------ small parts

    static void json() {
        Map<String, Object> m = Json.parseObject("{\"a\":1,\"b\":[true,null,\"x\\n\\u00e9\"],\"c\":{\"d\":-2.5}}");
        eq(1L, m.get("a"), "number");
        eq("x\né", ((List<?>) m.get("b")).get(2), "escapes");
        eq(-2.5, ((Map<?, ?>) m.get("c")).get("d"), "nested double");
        eq("{\"s\":\"\\u003cb\\u003e\"}", Json.write(Json.obj("s", "<b>")), "html-safe");
        refused(() -> { try { Json.parse("{\"a\":}"); } catch (IllegalArgumentException e) { throw Problem.bad(e.getMessage()); } }, 400, "bad JSON");
    }

    static void money() {
        eq("12,80,500.75", Money.format(128050075), "lakh grouping");
        eq("1,00,00,000.00", Money.format(1_00_00_000_00L), "crore");
        eq("999.00", Money.format(99900), "under a thousand");
        eq("-1,234.50", Money.format(-123450), "negative");
        eq(128050075L, Money.parse("12,80,500.75"), "parse");
        eq(150L, Money.parse("1.5"), "one decimal = 50 paise");
        eq(1200000L, Money.parse("₹ 12,000"), "rupee sign");
        refused(() -> { try { Money.parse("12.345"); } catch (IllegalArgumentException e) { throw Problem.bad(e.getMessage()); } }, 400, "3 decimals");
        refused(() -> { try { Money.parse("abc"); } catch (IllegalArgumentException e) { throw Problem.bad(e.getMessage()); } }, 400, "letters");
        eq(3L, Money.div(5, 2), "2.5 rounds up");
        eq(-3L, Money.div(-5, 2), "-2.5 rounds away from zero");
        eq(2L, Money.div(7, 3), "2.33 rounds down");
        eq(1800L, Money.pct(10000, 18), "18 % of Rs. 100");
        eq(250L, Money.pct(10000, 2.5), "2.5 %");
        eq("₹12,80,501", Money.rupees(128050075), "rounded rupees");
    }

    static void words() {
        eq("Rupees One Only", Money.words(100), "one");
        eq("Rupees Twelve Lakh Eighty Thousand Five Hundred and Paise Seventy Five Only", Money.words(128050075), "lakh");
        eq("Rupees Two Crore Five Lakh Only", Money.words(2_05_00_000_00L), "crore");
        eq("Rupees One Thousand and One Only", Money.words(100100), "thousand and one");
        eq("Rupees Ninety Nine Thousand Nine Hundred and Ninety Nine Only", Money.words(9999900), "99,999");
        eq("Rupees Zero and Paise Fifty Only", Money.words(50), "only paise");
        eq("One Hundred and Twenty Crore", Money.number(1_20_00_00_000L), "more than 99 crore");
    }

    static void gstin() {
        String g = Gstin.make("20", "AAHCK4417M", '1');
        eq(15, g.length(), "length");
        check(Gstin.valid(g), "made one is valid");
        check(Gstin.valid(" " + g.toLowerCase() + " "), "small letters and spaces are fine");
        eq("20", Gstin.stateOf(g), "state");
        eq("AAHCK4417M", Gstin.pan(g), "PAN");
        check(Gstin.valid("27AAPFU0939F1ZV"), "a well-known published example GSTIN");
        // every single wrong character is caught
        String chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        int caught = 0, tried = 0;
        for (int i = 0; i < 14; i++) for (char c : chars.toCharArray()) {
            if (c == g.charAt(i)) continue;
            String t = g.substring(0, i) + c + g.substring(i + 1);
            tried++;
            if (!Gstin.valid(t)) caught++;
        }
        eq(tried, caught, "every one-character typo is refused");
        String swapped = g.substring(0, 5) + g.charAt(6) + g.charAt(5) + g.substring(7);
        if (g.charAt(5) != g.charAt(6)) check(!Gstin.valid(swapped), "two neighbours swapped");
        check(Gstin.problem("99AAHCK4417M1Z5").contains("state code"), "unknown state");
        check(Gstin.problem("20AAHCK441").contains("15 characters"), "too short");
        check(Gstin.problem("201AHCK4417M1Z5").contains("PAN"), "PAN shape");
        check(Gstin.problem(Gstin.make("20", "AAHCK4417M", '1').substring(0, 13) + "X" + "1").contains("always Z"), "14th must be Z");
    }

    static void tax() {
        Invoice i = new Invoice();
        Line a = new Line(); a.qtyMilli = 2500; a.rate = 10000; a.discountBp = 1000; a.gstBp = 1800;   // 2.5 x 100, 10 % off
        Line b = new Line(); b.qtyMilli = 3000; b.rate = 3333; b.gstBp = 500;                       // 3 x 33.33 at 5 %
        i.lines = new ArrayList<>(List.of(a, b));
        i.placeOfSupply = "20";
        Tax.compute(i, "20");
        eq(25000L, a.gross, "gross");
        eq(2500L, a.discount, "discount");
        eq(22500L, a.taxable, "taxable");
        eq(2025L, a.cgst, "CGST 9 %");
        eq(2025L, a.sgst, "SGST 9 %");
        eq(0L, a.igst, "no IGST in the state");
        eq(9999L, b.taxable, "3 x 33.33");
        eq(250L, b.cgst, "2.5 % of 99.99 = 2.4998 -> 2.50");
        long exact = 22500 + 2025 + 2025 + 9999 + 250 + 250;
        eq(exact - exact % 100 + (exact % 100 >= 50 ? 100 : 0), i.total, "rounded to the rupee");
        eq(i.total - exact, i.roundOff, "round off");
        check(Math.abs(i.roundOff) <= 50, "round off at most 50 paise");
        i.placeOfSupply = "27";
        Tax.compute(i, "20");
        eq(4050L, a.igst, "IGST 18 % outside the state");
        eq(0L, a.cgst + a.sgst, "no CGST/SGST outside");
        Map<Integer, long[]> r = Tax.byRate(i.lines);
        eq(Set.of(1800, 500), r.keySet(), "two rates");
        eq(22500L, r.get(1800)[0], "taxable at 18 %");
    }

    static void finYear() {
        eq("26-27", FinYear.of(LocalDate.of(2026, 4, 1)), "1 April starts the year");
        eq("25-26", FinYear.of(LocalDate.of(2026, 3, 31)), "31 March ends it");
        eq("26-27", FinYear.of(LocalDate.of(2027, 2, 10)), "February is still the old year");
        eq("99-00", FinYear.of(LocalDate.of(1999, 6, 1)), "century");
        eq("KVP/26-27/0042", FinYear.number("KVP", LocalDate.of(2026, 11, 2), 42), "number");
        check(FinYear.number("ABCDE", LocalDate.of(2026, 11, 2), 9999).length() <= 16, "longest prefix fits 16");
        check(FinYear.prefixProblem("ABCDEF") != null, "6 letters is too long");
        check(FinYear.prefixProblem("kv/p") != null, "bad prefix");
        check(FinYear.prefixProblem("KVP") == null, "good prefix");
    }

    static void schedule() {
        Recurring.Every m = Recurring.Every.MONTHLY;
        LocalDate d = LocalDate.of(2027, 1, 31);
        List<String> seen = new ArrayList<>();
        for (int k = 0; k < 5; k++) { seen.add(d.toString()); d = Schedule.step(m, 31, d); }
        eq(List.of("2027-01-31", "2027-02-28", "2027-03-31", "2027-04-30", "2027-05-31"), seen, "31st stays the month end");
        eq(LocalDate.of(2028, 2, 29), Schedule.step(m, 30, LocalDate.of(2028, 1, 30)), "leap year");
        eq(LocalDate.of(2027, 2, 28), Schedule.step(Recurring.Every.QUARTERLY, 30, LocalDate.of(2026, 11, 30)), "quarterly to February");
        eq(LocalDate.of(2026, 11, 23), Schedule.first(Recurring.Every.WEEKLY, 1, LocalDate.of(2026, 11, 17)), "next Monday");
        eq(LocalDate.of(2026, 11, 16), Schedule.first(Recurring.Every.WEEKLY, 1, LocalDate.of(2026, 11, 16)), "today is Monday");
        eq(LocalDate.of(2026, 12, 5), Schedule.first(m, 5, LocalDate.of(2026, 11, 16)), "5th already passed this month");
        eq(LocalDate.of(2026, 11, 30), Schedule.first(m, 31, LocalDate.of(2026, 11, 16)), "31st in November = 30th");
        eq(LocalDate.of(2027, 11, 16), Schedule.step(Recurring.Every.YEARLY, 16, LocalDate.of(2026, 11, 16)), "yearly");
        check(Schedule.describe(m, 31).contains("last day"), "describe month end");
        eq("On the 22nd every month", Schedule.describe(m, 22), "ordinal");
        eq("11th", Schedule.ordinal(11), "11th not 11st");
        check(Schedule.anchorProblem(Recurring.Every.WEEKLY, 8) != null, "no 8th weekday");
    }

    static void dunning() {
        List<Integer> days = Dunning.DEFAULT_DAYS;
        LocalDate due = LocalDate.of(2026, 11, 20);
        eq(0, Dunning.levelDue(days, due, LocalDate.of(2026, 11, 16)), "4 days before: nothing");
        eq(1, Dunning.levelDue(days, due, LocalDate.of(2026, 11, 17)), "3 days before: friendly");
        eq(2, Dunning.levelDue(days, due, due), "due today");
        eq(3, Dunning.levelDue(days, due, due.plusDays(7)), "7 late");
        eq(5, Dunning.levelDue(days, due, due.plusDays(200)), "very late");
        eq(0, Dunning.toSend(days, due, due.plusDays(8), 3), "level 3 already sent");
        eq(4, Dunning.toSend(days, due, due.plusDays(20), 1), "after a break: only the highest");
        eq(888L, Dunning.interest(1_00_000L, 1800, 18), "18 % a year for 18 days on Rs. 1,000 = 887.67 paise -> Rs. 8.88");
        eq(0L, Dunning.interest(1_00_000L, 1800, 0), "not late = no interest");
        check(Dunning.daysProblem(List.of(0, 0, 7, 15, 30)) != null, "days must go up");
        check(Dunning.daysProblem(List.of(-3, 0, 7, 15, 30)) == null, "default is fine");
    }

    static void bankMatch() {
        List<BankMatch.Cust> cs = List.of(new BankMatch.Cust("C1", "Ganga Agro Mills", null, 0),
                new BankMatch.Cust("C2", "Adityapur Auto Components Pvt. Ltd.", Gstin.make("20", "AACCA7781K", '1'), 200),
                new BankMatch.Cust("C3", "Ganga Logistics", null, 0));
        List<BankMatch.Open> open = List.of(
                new BankMatch.Open("I1", "KVP/26-27/0042", "C1", "2026-10-01", 50_000_00, 42_372_88),
                new BankMatch.Open("I2", "KVP/26-27/0050", "C1", "2026-10-15", 30_000_00, 25_423_73),
                new BankMatch.Open("I3", "KVP/26-27/0061", "C1", "2026-11-01", 12_345_00, 10_461_86),
                new BankMatch.Open("I4", "KVP/26-27/0070", "C2", "2026-11-01", 49_560_00, 42_000_00),
                new BankMatch.Open("I5", "KVP/26-27/0071", "C3", "2026-11-01", 9_999_00, 8_473_73));
        eq(Set.of(42L), invoiceNumbers("NEFT CR KVP/26-27/0042 GANGA"), "full number");
        eq(Set.of(42L), invoiceNumbers("BY CLG KVP 2627 42"), "spaces");
        eq(Set.of(42L), invoiceNumbers("IMPS KVP26270042"), "no separators");
        eq(Set.of(42L, 50L), invoiceNumbers("KVP-42 AND KVP-0050"), "two numbers");
        check(invoiceNumbers("KVPX 12").isEmpty(), "other prefix");

        var s1 = BankMatch.suggest(50_000_00, "NEFT CR-PUNB0123-GANGA AGRO MILLS-KVP 0042", "", "KVP", cs, open);
        eq("C1", s1.get(0).customerId(), "number + name + amount");
        eq(100, s1.get(0).score(), "sure");
        eq(List.of("I1"), s1.get(0).invoiceIds(), "the named invoice");

        var s2 = BankMatch.suggest(42_345_00, "RTGS GANGA AGRO MILL", "", "KVP", cs, open);   // 30,000 + 12,345
        eq(List.of("I2", "I3"), s2.get(0).invoiceIds(), "subset sum finds two invoices");
        check(s2.get(0).score() >= 50, "name + subset = at least likely: " + s2.get(0).score());

        long tds = Money.pct(42_000_00, 2);
        var s3 = BankMatch.suggest(49_560_00 - tds, "NEFT ADITYAPUR AUTOCOMP", "", "KVP", cs, open);
        eq("C2", s3.get(0).customerId(), "TDS customer");
        eq(tds, s3.get(0).tds(), "TDS worked out");

        var s4 = BankMatch.suggest(1_842_00, "BY TRANSFER-INT CREDIT", "", "KVP", cs, open);
        check(s4.isEmpty(), "bank interest matches nobody");

        var s5 = BankMatch.suggest(20_000_00, "NEFT GANGA AGRO MILLS PART PAYMENT", "", "KVP", cs, open);
        eq("C1", s5.get(0).customerId(), "name only");
        eq(List.of("I1"), s5.get(0).invoiceIds(), "oldest first");
        eq(List.of(20_000_00L), s5.get(0).amounts(), "part of it");
        check(s5.get(0).score() < BankMatch.SURE, "not sure without amount");

        var s6 = BankMatch.suggest(1_00_00, "UPI " + Gstin.pan(Gstin.make("20", "AACCA7781K", '1')), "", "KVP", cs, open);
        eq("C2", s6.get(0).customerId(), "PAN in the narration");
        check(BankMatch.nameShare("Ganga Agro Mills", Set.of("GANGA", "AGRO", "MILL")) == 1.0, "fuzzy MILL = MILLS");
    }

    static Set<Long> invoiceNumbers(String text) { return BankMatch.invoiceNumbers(text, "KVP"); }

    static void csv() {
        String out = Csv.write(List.of(List.of("a,b", "say \"hi\"", "=SUM(A1)", "-12.50", "line\nbreak")));
        eq("\"a,b\",\"say \"\"hi\"\"\",'=SUM(A1),-12.50,\"line\nbreak\"\r\n", out, "write");
        List<List<String>> back = Csv.parse(out);
        eq("a,b", back.get(0).get(0), "comma back");
        eq("say \"hi\"", back.get(0).get(1), "quote back");
        eq("line\nbreak", back.get(0).get(4), "new line back");
    }

    static void customers() {
        W w = new W();
        eq("20", w.local.state, "state from GSTIN");
        eq("27", w.outside.state, "Maharashtra");
        check(!w.b2c.b2b(), "no GSTIN = B2C");
        refused(() -> w.cust("Copy", w.local.gstin, 30, "0", 0), 409, "same GSTIN twice");
        String bad = w.local.gstin.substring(0, 14) + (w.local.gstin.charAt(14) == 'A' ? 'B' : 'A');
        Problem p = refused(() -> w.cust("Typo Ltd", bad, 30, "0", 0), 400, "wrong check digit");
        check(p.getMessage().contains("check digit"), "says why: " + p.getMessage());
        refused(() -> w.p.customers().save(w.sales, null, Json.obj("name", "No State")), 400, "B2C needs a state");
        refused(() -> w.cust("Too Much TDS", null, 30, "0", 11), 400, "TDS over 10 %");
        refused(() -> w.p.customers().saveItem(w.owner, null, Json.obj("name", "X", "hsn", "123", "rate", "1", "gstBp", 1800)), 400, "HSN 3 digits");
        refused(() -> w.p.customers().saveItem(w.owner, null, Json.obj("name", "X", "hsn", "123456", "service", true, "rate", "1", "gstBp", 1800)), 400, "SAC must start 99");
        refused(() -> w.p.customers().saveItem(w.owner, null, Json.obj("name", "X", "hsn", "4819", "rate", "1", "gstBp", 1500)), 400, "15 % is no GST rate");
    }

    static void drafts() {
        W w = new W();
        refused(() -> w.p.invoices().create(w.sales, Json.obj("customerId", "C-999", "lines", List.of())), 400, "unknown customer");
        refused(() -> w.p.invoices().create(w.sales, Json.obj("customerId", w.local.id, "issueDate", today().plusDays(1).toString(),
                "lines", List.of(w.line(w.box, "1")))), 400, "future date");
        refused(() -> w.p.invoices().create(w.sales, Json.obj("customerId", w.local.id, "lines", List.of(w.line(w.box, "0")))), 400, "zero quantity");
        refused(() -> w.p.invoices().create(w.sales, Json.obj("customerId", w.local.id, "lines", List.of(w.line(w.box, "1.2345")))), 400, "4 decimals");
        Invoice d = w.draft(w.sales, w.local, w.line(w.box, "2.5"));
        eq(2500L, d.lines.get(0).qtyMilli, "2.5 = 2500 thousandths");
        eq(Status.DRAFT, d.status, "draft");
        eq(null, d.number, "a draft has no number");
        eq(today().plusDays(30).toString(), d.dueDate, "due after 30 days terms");
        refused(() -> w.p.invoices().issue(w.sales, w.draft(w.acc, w.local, w.line(w.box, "1")).id, null), 403, "Sales cannot issue the Accountant's draft");
        Invoice empty = w.p.invoices().create(w.sales, Json.obj("customerId", w.local.id, "lines", List.of(Json.obj("description", "", "qty", "", "rate", ""))));
        eq(0, empty.lines.size(), "empty rows are dropped");
        refused(() -> w.p.invoices().issue(w.sales, empty.id, null), 400, "nothing to issue");
    }

    static void issue() {
        W w = new W();
        Invoice a = w.inv(w.local, 10);
        eq("TP/26-27/0001", a.number, "first number");
        eq(118000L, a.total, "10 x 100 + 18 %");
        eq(9000L, a.cgst, "CGST");
        Invoice d = w.draft(w.acc, w.local, w.line(w.box, "1"));
        w.p.invoices().deleteDraft(w.acc, d.id);                       // a deleted draft never had a number
        Invoice b = w.inv(w.outside, 1);
        eq("TP/26-27/0002", b.number, "no gap after a deleted draft");
        eq(1800L, b.igst, "IGST for Maharashtra");
        check(InvoiceFp.ok(a), "fingerprint matches");
        refused(() -> w.p.invoices().update(w.acc, a.id, Json.obj("customerId", w.local.id, "lines", List.of(w.line(w.box, "99")))), 409, "issued = locked");
        // date order: a draft dated 5 days back, issued after an invoice of today, gets today's date
        Invoice old = w.p.invoices().create(w.acc, Json.obj("customerId", w.local.id, "issueDate", today().minusDays(5).toString(), "lines", List.of(w.line(w.box, "1"))));
        w.p.invoices().issue(w.acc, old.id, null);
        eq(today().toString(), old.issueDate, "moved to keep the series in date order");
        eq("TP/26-27/0003", old.number, "next number");
        eq(1L, w.store.mails.values().stream().filter(m -> a.id.equals(m.invoiceId) && m.kind.equals("INVOICE")).count(), "invoice e-mail in the outbox");
        a.lines.get(0).rate = 1;   // somebody edits the data file by hand
        check(!InvoiceFp.ok(a), "the change is found");
    }

    static final class InvoiceFp {
        static boolean ok(Invoice i) { return i.fingerprint.equals(com.cecil.bijak.service.InvoiceService.fingerprint(i)); }
    }

    static void approval() {
        W w = new W();
        Invoice d = w.draft(w.sales, w.local, w.line(w.box, "900"));     // 1,06,200 > 1,00,000
        w.p.invoices().issue(w.sales, d.id, null);
        eq(Status.PENDING_APPROVAL, d.status, "waits");
        eq(null, d.number, "no number while waiting");
        Invoice small = w.draft(w.sales, w.local, w.line(w.box, "10"));
        w.p.invoices().issue(w.sales, small.id, null);
        eq(Status.ISSUED, small.status, "small one goes straight out");
        refused(() -> w.p.invoices().approve(w.sales, d.id), 403, "Sales cannot approve");
        w.p.invoices().sendBack(w.acc, d.id, "Quantity should be 800");
        eq(Status.DRAFT, d.status, "back to draft");
        eq("Quantity should be 800", d.sentBackNote, "note kept");
        w.p.invoices().update(w.sales, d.id, Json.obj("customerId", w.local.id, "lines", List.of(w.line(w.box, "850"))));
        w.p.invoices().issue(w.sales, d.id, null);
        Invoice mine = w.draft(w.acc, w.local, w.line(w.box, "5"));
        mine.createdBy = w.acc.id;
        w.p.invoices().approve(w.acc, d.id);
        eq(Status.ISSUED, d.status, "approved");
        eq(w.acc.id, d.approvedBy, "who approved");
        eq("TP/26-27/0002", d.number, "number given at approval");
        // four eyes
        W w2 = new W();
        Invoice x = w2.draft(w2.acc, w2.local, w2.line(w2.box, "900"));
        x.status = Status.PENDING_APPROVAL;
        refused(() -> w2.p.invoices().approve(w2.acc, x.id), 403, "own invoice");
        w2.p.invoices().approve(w2.acc2, x.id);
        eq(Status.ISSUED, x.status, "the other accountant may");
    }

    static void creditLimit() {
        W w = new W();
        Customer c = w.cust("Limited Ltd", Gstin.make("20", "AABCL4444E", '1'), 30, "2000", 0);   // Rs. 2,000 limit
        w.inv(c, 10);                                                                           // owes 1,180
        Invoice d = w.draft(w.sales, c, w.line(w.box, "10"));                                    // would owe 2,360
        Problem p = refused(() -> w.p.invoices().issue(w.sales, d.id, null), 409, "Sales stopped");
        check(p.getMessage().contains("credit limit"), "says why");
        refused(() -> w.p.invoices().issue(w.acc, d.id, " "), 409, "Accountant must write why");
        w.p.invoices().issue(w.acc, d.id, "Paying on Friday, confirmed by phone");
        eq(Status.ISSUED, d.status, "issued with a reason");
        eq("Paying on Friday, confirmed by phone", d.overrideNote, "reason kept");
    }

    static void creditNotes() {
        W w = new W();
        Invoice a = w.p.invoices().issue(w.acc, w.p.invoices().create(w.acc, Json.obj("customerId", w.local.id,
                "lines", List.of(w.line(w.box, "10"), w.line(w.svc, "1")))).id, null);   // 1,180 + 11,800 = 12,980
        Invoice cn = w.p.invoices().creditNote(w.acc, a.id, Json.obj("reason", "3 boxes broken", "lines", List.of(Json.obj("src", 0, "qty", "3"))));
        eq("TCN/26-27/0001", cn.number, "own series");
        eq(35400L, cn.total, "3 x 118");
        eq(35400L, a.credited, "taken off the invoice");
        eq(Status.PART_PAID, a.status, "part settled");
        eq(Status.APPLIED, cn.status, "credit note fully used");
        Problem p = refused(() -> w.p.invoices().creditNote(w.acc, a.id, Json.obj("reason", "more", "lines", List.of(Json.obj("src", 0, "qty", "8")))), 400, "more than left on the line");
        check(p.getMessage().contains("700.00"), "says what is left: " + p.getMessage());
        refused(() -> w.p.invoices().creditNote(w.acc, a.id, Json.obj("reason", "rate up", "lines", List.of(Json.obj("src", 0, "qty", "1", "rate", "150")))), 400, "higher rate");
        refused(() -> w.p.invoices().creditNote(w.acc, a.id, Json.obj("reason", "", "lines", List.of(Json.obj("src", 0, "qty", "1")))), 400, "needs a reason");
        refused(() -> w.p.invoices().creditNote(w.sales, a.id, Json.obj("reason", "x", "lines", List.of(Json.obj("src", 0, "qty", "1")))), 403, "Sales cannot");
        // invoice already paid: the credit becomes an advance and is used by the next invoice
        Invoice b = w.inv(w.local, 10);
        w.pay(w.local, "1180", List.of(Json.obj("invoiceId", b.id, "amount", "1180")));
        w.pay(w.local, Money.format(a.balance()).replace(",", ""), List.of(Json.obj("invoiceId", a.id, "amount", Money.format(a.balance()).replace(",", ""))));
        eq(Status.PAID, b.status, "paid");
        Invoice cn2 = w.p.invoices().creditNote(w.acc, b.id, Json.obj("reason", "price cut", "lines", List.of(Json.obj("src", 0, "qty", "10", "rate", "10"))));
        eq(11800L, cn2.total, "10 x Rs 10 + 18 %");
        eq(11800L, cn2.balance(), "kept as credit");
        eq(11800L, w.store.advance(w.local.id), "customer has an advance");
        Invoice c = w.inv(w.local, 1);
        eq(Status.PAID, c.status, "the next invoice is paid from the credit");
        eq(11800L - 11800L, c.balance(), "nothing left to pay");
        eq(0L, cn2.balance() - (11800 - 11800 + cn2.balance()), "sanity");
        eq(0L, w.store.advance(w.local.id), "credit used up");
        refused(() -> w.p.invoices().creditNote(w.acc, cn.id, Json.obj("reason", "x", "lines", List.of())), 409, "no credit note on a credit note");
    }

    static void cancel() {
        W w = new W();
        Invoice a = w.inv(w.local, 5);
        refused(() -> w.p.invoices().cancel(w.acc, a.id, ""), 400, "needs a reason");
        refused(() -> w.p.invoices().cancel(w.sales, a.id, "x"), 403, "Sales cannot cancel");
        w.p.invoices().cancel(w.acc, a.id, "Wrong customer");
        eq(Status.CANCELLED, a.status, "cancelled");
        eq("TP/26-27/0001", a.number, "the number stays (no gap)");
        eq(0L, a.balance(), "nothing owed");
        Invoice b = w.inv(w.local, 5);
        w.pay(w.local, "100", List.of(Json.obj("invoiceId", b.id, "amount", "100")));
        refused(() -> w.p.invoices().cancel(w.acc, b.id, "x"), 409, "money already against it");
    }

    static void payments() {
        W w = new W();
        Invoice a = w.inv(w.local, 10), b = w.inv(w.local, 10);       // 1,180 each
        Payment p = w.pay(w.local, "1500", null);                      // oldest first
        eq("TR/26-27/0001", p.number, "receipt number");
        eq(Status.PAID, a.status, "first paid");
        eq(32000L, b.paid, "320 to the second");
        eq(Status.PART_PAID, b.status, "second part paid");
        eq(0L, p.unapplied(), "all used");
        refused(() -> w.pay(w.local, "100", List.of(Json.obj("invoiceId", b.id, "amount", "900"))), 400, "gives more than the payment");
        refused(() -> w.pay(w.local, "5000", List.of(Json.obj("invoiceId", b.id, "amount", "5000"))), 400, "more than the invoice needs");
        refused(() -> w.pay(w.outside, "10", List.of(Json.obj("invoiceId", b.id, "amount", "10"))), 400, "someone else's invoice");
        refused(() -> w.p.payments().record(w.acc, Json.obj("customerId", w.local.id, "amount", "10", "mode", "NEFT", "reference", p.reference)), 409, "same reference twice");
        refused(() -> w.p.payments().record(w.acc, Json.obj("customerId", w.local.id, "amount", "250000", "mode", "CASH")), 400, "cash 2 lakh (269ST)");
        refused(() -> w.p.payments().record(w.acc, Json.obj("customerId", w.local.id, "amount", "10", "mode", "NEFT")), 400, "UTR needed");
        refused(() -> w.p.payments().record(w.acc, Json.obj("customerId", w.local.id, "amount", "10", "mode", "BITCOIN", "reference", "x")), 400, "unknown mode");
        refused(() -> w.p.payments().record(w.sales, Json.obj("customerId", w.local.id, "amount", "10", "mode", "CASH")), 403, "Sales cannot record");
        // TDS: the customer keeps 2 % of the taxable value and pays the rest
        Invoice s = w.p.invoices().issue(w.acc, w.draft(w.acc, w.tdsCust, w.line(w.svc, "1")).id, null);   // 11,800 (taxable 10,000)
        Payment t = w.p.payments().record(w.acc, Json.obj("customerId", w.tdsCust.id, "amount", "11600", "tds", "200", "mode", "RTGS", "reference", "R1"));
        eq(Status.PAID, s.status, "cash + TDS settle it");
        eq(1180000L, t.value(), "value counts TDS");
        refused(() -> w.p.payments().record(w.acc, Json.obj("customerId", w.tdsCust.id, "amount", "100", "tds", "50", "mode", "RTGS", "reference", "R2")), 400, "TDS over 10 %");
        check(w.store.mails.values().stream().anyMatch(m -> m.kind.equals("RECEIPT") && m.body.contains("Form 16A")), "receipt asks for the TDS certificate");
    }

    static void advance() {
        W w = new W();
        Payment p = w.pay(w.b2c, "500", null);
        eq(50000L, p.unapplied(), "nothing to pay yet: advance");
        eq(50000L, w.store.advance(w.b2c.id), "customer advance");
        Invoice a = w.p.invoices().issue(w.sales, w.draft(w.sales, w.b2c, w.line(w.box, "3")).id, null);   // 354
        eq(Status.PAID, a.status, "paid from the advance at once");
        eq(14600L, w.store.advance(w.b2c.id), "146 left");
        Invoice b = w.p.invoices().issue(w.sales, w.draft(w.sales, w.b2c, w.line(w.box, "3")).id, null);
        eq(Status.PART_PAID, b.status, "the rest of the advance");
        eq(35400L - 14600L, b.balance(), "still to pay");
        refused(() -> w.p.invoices().useAdvance(w.acc, w.b2c.id), 409, "nothing left to use");
    }

    static void voidPayment() {
        W w = new W();
        Invoice a = w.inv(w.local, 10);
        Payment p = w.pay(w.local, "1180", null);
        eq(Status.PAID, a.status, "paid");
        refused(() -> w.p.payments().voidPayment(w.acc, p.id, ""), 400, "needs a reason");
        w.p.payments().voidPayment(w.acc, p.id, "Cheque bounced");
        eq(Status.ISSUED, a.status, "open again");
        eq(118000L, a.balance(), "owes it all again");
        check(p.voided, "marked void");
        eq(0L, p.value(), "a void payment counts nothing");
        refused(() -> w.p.payments().voidPayment(w.acc, p.id, "again"), 409, "twice");
    }

    static void recurring() {
        W w = new W();
        Clock.fix(Instant.parse("2027-01-29T05:00:00Z"));
        Recurring r = w.p.auto().save(w.acc, null, Json.obj("name", "AMC", "customerId", w.local.id, "every", "MONTHLY", "anchorDay", 31,
                "startDate", "2027-01-29", "lines", List.of(w.line(w.svc, "1"))));
        eq("2027-01-31", r.nextDate, "first run on the 31st");
        Recurring wk = w.p.auto().save(w.acc, null, Json.obj("name", "Weekly", "customerId", w.outside.id, "every", "WEEKLY", "anchorDay", 1,
                "startDate", "2027-01-29", "endDate", "2027-02-14", "lines", List.of(w.line(w.box, "5"))));
        Recurring chk = w.p.auto().save(w.acc, null, Json.obj("name", "Check first", "customerId", w.local.id, "every", "MONTHLY", "anchorDay", 1,
                "startDate", "2027-01-29", "autoIssue", false, "lines", List.of(w.line(w.box, "5"))));
        refused(() -> w.p.auto().save(w.acc, null, Json.obj("name", "Past", "customerId", w.local.id, "startDate", "2027-01-01", "lines", List.of(w.line(w.box, "1")))), 400, "no start in the past");
        refused(() -> w.p.auto().save(w.sales, null, Json.obj("name", "S", "customerId", w.local.id, "lines", List.of(w.line(w.box, "1")))), 403, "Sales cannot make schedules");
        // Bijak was off until 1 March: it catches up, oldest first, so the numbers stay in date order
        Clock.fix(Instant.parse("2027-03-01T05:00:00Z"));
        Map<String, Object> t = w.p.auto().tick();
        List<Invoice> made = new ArrayList<>();
        for (Invoice i : w.store.invoices.values()) if (i.recurringId != null) made.add(i);
        List<String> amc = new ArrayList<>(), weekly = new ArrayList<>();
        for (Invoice i : made) { if (i.recurringId.equals(r.id)) amc.add(i.issueDate); if (i.recurringId.equals(wk.id)) weekly.add(i.issueDate); }
        eq(List.of("2027-01-31", "2027-02-28"), amc, "31 Jan and 28 Feb");
        eq(List.of("2027-02-01", "2027-02-08"), weekly, "Mondays up to the end date");
        check(!wk.active, "weekly finished after its end date");
        List<Invoice> booked = new ArrayList<>(made);
        booked.removeIf(i -> i.number == null);
        booked.sort((a, b) -> a.number.compareTo(b.number));
        for (int k = 1; k < booked.size(); k++) check(booked.get(k).issueDate.compareTo(booked.get(k - 1).issueDate) >= 0, "numbers in date order");
        long drafts = made.stream().filter(i -> i.recurringId.equals(chk.id)).filter(i -> i.status == Status.DRAFT).count();
        eq(2L, drafts, "check-first schedule leaves drafts (1 Feb, 1 Mar)");
        check(made.stream().filter(i -> i.recurringId.equals(r.id)).allMatch(i -> i.notes.contains("AMC")), "period on the invoice");
        eq("2027-03-31", r.nextDate, "next 31 March");
        int before = w.store.invoices.size();
        w.p.auto().tick();
        eq(before, w.store.invoices.size(), "a second tick does nothing");
        check(((List<?>) t.get("invoices")).size() == 6, "6 made in the catch-up: " + t.get("invoices"));
    }

    static void reminders() {
        W w = new W();
        Invoice a = w.inv(w.local, 10);                       // due in 30 days
        Invoice held = w.inv(w.local, 1);
        w.p.invoices().hold(w.acc, held.id, true, "Disputed quantity");
        Customer quiet = w.cust("Quiet Govt Office", Gstin.make("20", "AAAGQ5555F", '1'), 30, "0", 0);
        w.p.customers().save(w.acc, quiet.id, Json.obj("name", quiet.name, "gstin", quiet.gstin, "termsDays", 30, "noReminders", true));
        Invoice q = w.inv(quiet, 1);
        w.p.auto().tick();
        eq(0, a.reminderLevel, "nothing on day 1");
        Clock.fix(START.plusMillis(27 * D));                 // 3 days before due
        w.p.auto().tick();
        eq(1, a.reminderLevel, "friendly reminder");
        w.p.auto().tick();
        eq(1L, mails(w, a, "REMINDER"), "only once");
        Clock.fix(START.plusMillis(60 * D));                 // switched off for a month: 30 days late
        w.p.auto().tick();
        eq(5, a.reminderLevel, "jumps to the final notice");
        eq(2L, mails(w, a, "REMINDER"), "only the highest is sent, not 4 at once");
        Mail fin = w.store.mails.values().stream().filter(m -> a.id.equals(m.invoiceId) && m.level == 5).findFirst().orElseThrow();
        check(fin.body.contains("interest"), "final notice mentions interest");
        check(fin.subject.contains("30 days overdue"), "subject: " + fin.subject);
        eq(0L, mails(w, held, "REMINDER"), "on hold: no reminders");
        eq(0L, mails(w, q, "REMINDER"), "customer with reminders off");
        refused(() -> w.p.auto().remindNow(w.sales, held.id), 409, "manual reminder on hold refused");
        w.p.invoices().hold(w.sales, held.id, false, null);
        w.p.auto().remindNow(w.sales, held.id);
        refused(() -> w.p.auto().remindNow(w.sales, held.id), 409, "not twice in 24 hours");
        w.pay(w.local, Money.format(a.balance()).replace(",", ""), List.of(Json.obj("invoiceId", a.id, "amount", Money.format(a.balance()).replace(",", ""))));
        Clock.fix(START.plusMillis(90 * D));
        w.p.auto().tick();
        eq(2L, mails(w, a, "REMINDER"), "paid: no more");
    }

    static long mails(W w, Invoice i, String kind) {
        return w.store.mails.values().stream().filter(m -> i.id.equals(m.invoiceId) && m.kind.equals(kind)).count();
    }

    static void bankImport() {
        W w = new W();
        Invoice a = w.inv(w.local, 10);    // 1,180
        Invoice b = w.inv(w.outside, 10);  // 1,180 (IGST)
        Invoice c = w.inv(w.outside, 20);  // 2,360
        String csv = "Statement of account\n"
                + "Txn Date,Description,Chq/Ref No,Withdrawal (Dr),Deposit (Cr),Balance\n"
                + "14-Nov-2026,NEFT CR-JHARKHAND BUYER PVT LTD-TP 0001,N123456789,,1180.00,10000\n"
                + "15/11/2026,RTGS MUMBAI BUYER,R998877665,,\"3,540.00\",13540\n"
                + "15/11/2026,TO ATM WDL,,500.00,,13040\n"
                + "16-11-2026,INT CREDIT,,,12.00,13052\n"
                + "Closing balance,,,,,13052\n";
        Map<String, Object> r = w.p.payments().importStatement(w.acc, csv);
        eq(3, r.get("added"), "3 money-in lines");
        eq(1, r.get("debits"), "ATM skipped");
        Map<String, Object> again = w.p.payments().importStatement(w.acc, csv);
        eq(0, again.get("added"), "same file twice: nothing new");
        eq(3, again.get("duplicates"), "duplicates found");
        refused(() -> w.p.payments().importStatement(w.acc, "a,b,c\n1,2,3\n"), 400, "no columns");
        BankLine first = w.store.bank.values().stream().filter(x -> x.amount == 118000).findFirst().orElseThrow();
        var s = w.p.payments().suggestions(first.id);
        eq(w.local.id, s.get(0).customerId(), "matched by name + number + amount");
        eq(List.of(a.id), s.get(0).invoiceIds(), "invoice 1");
        BankLine two = w.store.bank.values().stream().filter(x -> x.amount == 354000).findFirst().orElseThrow();
        var s2 = w.p.payments().suggestions(two.id);
        eq(List.of(b.id, c.id), s2.get(0).invoiceIds(), "both of Mumbai's invoices (everything owed)");
        Map<String, Object> auto = w.p.payments().acceptAllSure(w.acc);
        check(((Number) auto.get("matched")).intValue() >= 1, "auto matched the sure ones: " + auto);
        eq(Status.PAID, a.status, "invoice 1 paid from the bank line");
        eq(BankLine.State.MATCHED, first.state, "line done");
        Payment made = w.store.payments.get(first.paymentId);
        eq("NEFT", made.mode, "mode from the narration");
        eq("N123456789", made.reference, "reference from the file");
        BankLine interest = w.store.bank.values().stream().filter(x -> x.amount == 1200).findFirst().orElseThrow();
        eq(BankLine.State.NEW, interest.state, "interest is left for a person");
        w.p.payments().ignore(w.acc, interest.id, true);
        eq(BankLine.State.IGNORED, interest.state, "ignored");
        if (two.state == BankLine.State.NEW) {
            w.p.payments().accept(w.acc, two.id, Json.obj("customerId", w.outside.id));
        }
        eq(Status.PAID, b.status, "Mumbai 1 paid");
        eq(Status.PAID, c.status, "Mumbai 2 paid");
        w.p.payments().voidPayment(w.acc, first.paymentId, "matched wrongly");
        eq(BankLine.State.NEW, first.state, "voiding frees the bank line");
    }

    @SuppressWarnings("unchecked")
    static void statement() {
        W w = new W();
        Clock.fix(Instant.parse("2026-11-02T06:00:00Z"));
        Invoice a = w.inv(w.local, 10);                     // 1,180 on 2 Nov
        Clock.fix(Instant.parse("2026-11-10T06:00:00Z"));
        w.pay(w.local, "500", null);
        Invoice b = w.inv(w.local, 5);                      // 590 on 10 Nov
        Clock.fix(START.plusMillis(40 * D));                // 26 Dec: a is 24 days late
        Map<String, Object> st = (Map<String, Object>) w.p.views().customer(w.local.id, "2026-11-05", null).get("statement");
        eq(118000L, st.get("opening"), "opening = invoice before 5 Nov");
        List<Map<String, Object>> lines = (List<Map<String, Object>>) st.get("lines");
        eq(2, lines.size(), "payment and invoice in the period");
        eq("INV", lines.get(0).get("kind"), "on the same day the invoice comes first");
        eq(118000L + 59000L - 50000L, lines.get(1).get("balance"), "running balance after the payment");
        eq(118000L - 50000L + 59000L, st.get("closing"), "closing");
        eq(a.balance() + b.balance(), st.get("closing"), "closing = what is open");
        Map<String, Object> rep = w.p.views().reports(null);
        List<Long> tot = (List<Long>) rep.get("ageingTotal");
        eq(a.balance() + b.balance(), tot.stream().mapToLong(Long::longValue).sum(), "ageing adds up to what is owed");
        check(tot.get(1) > 0, "something in 1-30 days");
        Map<String, Object> c = w.p.views().customer(w.local.id, null, null);
        check(((Number) c.get("interest")).longValue() > 0, "interest on the late invoice");
        refused(() -> w.p.views().customer(w.local.id, "2026-12-01", "2026-11-01"), 400, "to before from");
    }

    @SuppressWarnings("unchecked")
    static void gstReport() {
        W w = new W();
        Invoice a = w.inv(w.local, 10);
        Invoice b = w.inv(w.outside, 10);
        Invoice c = w.p.invoices().issue(w.sales, w.draft(w.sales, w.b2c, w.line(w.box, "2")).id, null);
        w.p.invoices().creditNote(w.acc, a.id, Json.obj("reason", "return", "lines", List.of(Json.obj("src", 0, "qty", "1"))));
        Map<String, Object> g = w.p.views().gst(java.time.YearMonth.of(2026, 11));
        eq(2, ((List<?>) g.get("b2b")).size(), "two B2B");
        eq(1, ((List<?>) g.get("b2c")).size(), "one B2C row");
        eq(1, ((List<?>) g.get("creditNotes")).size(), "credit note listed");
        eq(a.taxable + b.taxable + c.taxable - 10000L, g.get("taxable"), "credit note taken off");
        eq(b.igst, g.get("igst"), "IGST only from Maharashtra");
        eq(a.cgst + c.cgst - 900L, g.get("cgst"), "CGST less the credit note");
        List<Map<String, Object>> hsn = (List<Map<String, Object>>) g.get("hsn");
        eq(1, hsn.size(), "one HSN");
        eq(21000L, hsn.get(0).get("qtyMilli"), "10 + 10 + 2 - 1 boxes");
        String csv = w.p.admin().gstCsv("2026-11");
        check(csv.contains("B2B invoices") && csv.contains("HSN summary") && csv.contains(w.local.gstin), "CSV for the CA");
    }

    static void roles() {
        W w = new W();
        Invoice a = w.inv(w.local, 1);
        refused(() -> w.p.invoices().create(w.audit, Json.obj("customerId", w.local.id, "lines", List.of(w.line(w.box, "1")))), 403, "Auditor cannot draft");
        refused(() -> w.p.customers().save(w.audit, null, Json.obj("name", "X", "state", "20")), 403, "Auditor cannot add customers");
        refused(() -> w.p.payments().voidPayment(w.sales, "R-1", "x"), 403, "Sales cannot void");
        refused(() -> w.p.admin().saveSettings(w.acc, Json.obj()), 403, "Accountant cannot change settings");
        refused(() -> w.p.admin().saveUser(w.acc, null, Json.obj()), 403, "Accountant cannot add people");
        refused(() -> w.p.views().activity(w.sales, 10), 403, "Sales does not see the activity log");
        check(!w.p.views().activity(w.audit, 10).isEmpty(), "Auditor does");
        refused(() -> w.p.admin().saveUser(w.owner, w.owner.id, Json.obj("name", "Me", "role", "SALES")), 400, "Owner cannot remove own Owner role");
        User n = w.p.admin().saveUser(w.owner, null, Json.obj("username", "newbie", "name", "New Person", "role", "SALES", "password", "goodpass1"));
        eq(Role.SALES, n.role, "added");
        refused(() -> w.p.admin().saveUser(w.owner, null, Json.obj("username", "weak", "name", "W", "role", "SALES", "password", "short")), 400, "weak password");
        refused(() -> w.p.admin().saveSettings(w.owner, Json.obj("gstin", w.local.gstin, "invoicePrefix", "A", "creditPrefix", "A", "receiptPrefix", "B")), 400, "same prefix twice");
        check(a.number != null, "sanity");
    }

    static void lockout() {
        W w = new W();
        for (int k = 0; k < 4; k++) refused(() -> w.p.auth().login("sales", "wrong"), 401, "wrong password");
        Problem p = refused(() -> w.p.auth().login("nobody", "x"), 401, "unknown user");
        eq("Wrong username or password.", p.getMessage(), "same message for both");
        refused(() -> w.p.auth().login("sales", "wrong"), 401, "5th wrong locks");
        refused(() -> w.p.auth().login("sales", "secret123"), 429, "locked even with the right password");
        Clock.fix(START.plusMillis(6 * 60_000));
        String token = w.p.auth().login("sales", "secret123");
        eq(w.sales, w.p.auth().userFor(token), "after 5 minutes");
        Clock.fix(START.plusMillis(6 * 60_000 + 13 * H));
        eq(null, w.p.auth().userFor(token), "session ends after 12 idle hours");
    }

    static void threads() throws Exception {
        W w = new W();
        List<Invoice> drafts = new ArrayList<>();
        for (int k = 0; k < 16; k++) drafts.add(w.draft(w.acc, w.local, w.line(w.box, "1")));
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        List<Thread> ts = new ArrayList<>();
        for (Invoice d : drafts) {
            Thread t = new Thread(() -> {
                try { go.await(); w.p.invoices().issue(w.acc, d.id, null); } catch (Exception e) { errors.incrementAndGet(); }
            });
            t.start();
            ts.add(t);
        }
        go.countDown();
        for (Thread t : ts) t.join();
        eq(0, errors.get(), "no errors");
        Set<String> numbers = new HashSet<>();
        for (Invoice d : drafts) numbers.add(d.number);
        eq(16, numbers.size(), "16 different numbers");
        for (int k = 1; k <= 16; k++) check(numbers.contains(String.format("TP/26-27/%04d", k)), "number " + k + " used");
    }

    static void web() throws Exception {
        Path dir = Files.createTempDirectory("bijak-web");
        Files.writeString(dir.resolve("index.html"), "<!doctype html><title>t</title>");
        Files.writeString(dir.resolve("app.js"), "1");
        W w = new W();
        ApiServer server = new ApiServer(dir, w.p);
        server.start(0);
        try {
            String base = "http://127.0.0.1:" + server.port();
            HttpClient http = HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null)).build();
            var r = http.send(HttpRequest.newBuilder(URI.create(base + "/")).build(), HttpResponse.BodyHandlers.ofString());
            eq(200, r.statusCode(), "home page");
            check(r.headers().firstValue("Content-Security-Policy").isPresent(), "CSP header");
            eq(404, http.send(HttpRequest.newBuilder(URI.create(base + "/static/..%2F..%2Fetc%2Fpasswd")).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "no path tricks");
            eq(401, http.send(HttpRequest.newBuilder(URI.create(base + "/api/invoices")).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "needs sign-in");
            String login = "{\"username\":\"acc\",\"password\":\"secret123\"}";
            var noHeader = http.send(HttpRequest.newBuilder(URI.create(base + "/api/login")).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(login)).build(), HttpResponse.BodyHandlers.ofString());
            eq(403, noHeader.statusCode(), "CSRF header needed");
            var form = http.send(HttpRequest.newBuilder(URI.create(base + "/api/login")).header("X-Bijak", "1").header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString(login)).build(), HttpResponse.BodyHandlers.ofString());
            eq(415, form.statusCode(), "JSON only");
            var ok = http.send(HttpRequest.newBuilder(URI.create(base + "/api/login")).header("X-Bijak", "1").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(login)).build(), HttpResponse.BodyHandlers.ofString());
            eq(200, ok.statusCode(), "signed in");
            String cookie = ok.headers().firstValue("Set-Cookie").orElse("");
            check(cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict"), "safe cookie: " + cookie);
            String c = cookie.split(";")[0];
            var list = http.send(HttpRequest.newBuilder(URI.create(base + "/api/dashboard")).header("Cookie", c).build(), HttpResponse.BodyHandlers.ofString());
            eq(200, list.statusCode(), "dashboard");
            var other = http.send(HttpRequest.newBuilder(URI.create(base + "/api/invoices")).header("Cookie", c).header("X-Bijak", "1")
                    .header("Origin", "http://evil.example").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            eq(403, other.statusCode(), "another website is blocked");
            var bad = http.send(HttpRequest.newBuilder(URI.create(base + "/api/invoices")).header("Cookie", c).header("X-Bijak", "1")
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"customerId\":\"nope\"}")).build(), HttpResponse.BodyHandlers.ofString());
            eq(400, bad.statusCode(), "friendly error");
            check(bad.body().contains("Pick a customer"), "message: " + bad.body());
            var csv = http.send(HttpRequest.newBuilder(URI.create(base + "/api/export/invoices.csv")).header("Cookie", c).build(), HttpResponse.BodyHandlers.ofString());
            check(csv.headers().firstValue("Content-Disposition").orElse("").contains("attachment"), "CSV download");
            var gst = http.send(HttpRequest.newBuilder(URI.create(base + "/api/gstin")).header("Cookie", c).header("X-Bijak", "1").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"gstin\":\"" + w.local.gstin + "\"}")).build(), HttpResponse.BodyHandlers.ofString());
            check(gst.body().contains("\"ok\":true") && gst.body().contains("Jharkhand"), "GSTIN check: " + gst.body());
        } finally {
            server.stop();
        }
    }

    @SuppressWarnings("unchecked")
    static void demo() {
        Clock.fix(START);
        DataStore store = new DataStore(null);
        var p = App.parts(store);
        SeedData.load(p);
        eq(START, Clock.now(), "the clock is put back");
        check(store.invoices.size() > 120, "plenty of invoices: " + store.invoices.size());
        check(store.payments.size() > 80, "plenty of payments");
        // every series is gap-free and in date order
        Map<String, List<Invoice>> series = new TreeMap<>();
        for (Invoice i : store.invoices.values()) if (i.number != null) series.computeIfAbsent(i.number.substring(0, i.number.lastIndexOf('/')), k -> new ArrayList<>()).add(i);
        for (var e : series.entrySet()) {
            List<Invoice> l = e.getValue();
            l.sort((a, b) -> a.number.compareTo(b.number));
            for (int k = 0; k < l.size(); k++) {
                eq(e.getKey() + "/" + String.format("%04d", k + 1), l.get(k).number, "no gap");
                if (k > 0) check(l.get(k).issueDate.compareTo(l.get(k - 1).issueDate) >= 0, "date order in " + e.getKey());
                check(l.get(k).fingerprint.equals(com.cecil.bijak.service.InvoiceService.fingerprint(l.get(k))), "fingerprint " + l.get(k).number);
            }
        }
        // every booked invoice adds up; no payment gives away more than it has
        Map<String, Long> fromPayments = new HashMap<>();
        for (Payment pay : store.payments.values()) {
            check(pay.unapplied() >= 0, "payment over-used " + pay.number);
            if (!pay.voided) for (Alloc a : pay.allocations) fromPayments.merge(a.invoiceId, a.amount, Long::sum);
        }
        long owes = 0;
        for (Invoice i : store.invoices.values()) {
            if (i.creditNote() || !i.status.booked()) continue;
            eq(fromPayments.getOrDefault(i.id, 0L), i.paid, "paid of " + i.number);
            check(i.balance() >= 0, "never over-paid " + i.number);
            eq(i.total, i.paid + i.credited + i.balance(), "total = paid + credited + balance for " + i.number);
            if (i.status.open()) owes += i.balance();
            if (i.status == Status.PAID) eq(0L, i.balance(), "paid means 0");
            long tax = 0, taxable = 0;
            for (Line l : i.lines) { tax += l.tax(); taxable += l.taxable; }
            eq(i.total, taxable + tax + i.roundOff, "lines add up on " + i.number);
        }
        Map<String, Object> dash = p.views().dashboard(store.users.values().iterator().next());
        eq(owes, dash.get("owes"), "dashboard = sum of balances");
        List<Long> b = (List<Long>) dash.get("buckets");
        eq(owes, b.stream().mapToLong(Long::longValue).sum(), "ageing buckets add up");
        check(store.invoices.values().stream().anyMatch(i -> i.status == Status.PENDING_APPROVAL), "an approval waits");
        check(store.invoices.values().stream().anyMatch(i -> i.creditNote()), "credit notes");
        check(store.invoices.values().stream().anyMatch(i -> i.status == Status.CANCELLED), "a cancelled invoice");
        check(store.invoices.values().stream().anyMatch(i -> i.onHold), "an invoice on hold");
        check(store.payments.values().stream().anyMatch(x -> x.voided), "a bounced cheque");
        check(store.payments.values().stream().anyMatch(x -> x.tds > 0), "TDS payments");
        check(store.bank.values().stream().anyMatch(x -> x.state == BankLine.State.NEW), "bank lines to match");
        check(store.mails.values().stream().anyMatch(m -> m.level == 5), "a final notice went out");
        for (Recurring r : store.recurring.values()) check(r.generated > 0, "schedule ran: " + r.name);
        // nothing is dated after today
        for (Invoice i : store.invoices.values()) check(i.issueDate.compareTo(Clock.today().toString()) <= 0, "future invoice " + i.id);
        for (Payment x : store.payments.values()) check(x.date.compareTo(Clock.today().toString()) <= 0, "future payment " + x.number);
    }
}
