package com.cecil.ngo;

import com.cecil.ngo.model.Activity;
import com.cecil.ngo.model.Donation;
import com.cecil.ngo.model.Enums.ActivityStatus;
import com.cecil.ngo.model.Enums.Review;
import com.cecil.ngo.model.Expense;
import com.cecil.ngo.model.HoursLog;
import com.cecil.ngo.model.Program;
import com.cecil.ngo.model.User;
import com.cecil.ngo.model.Volunteer;
import com.cecil.ngo.service.ApiError;
import com.cecil.ngo.service.AuthService;
import com.cecil.ngo.service.Calc;
import com.cecil.ngo.store.DataStore;
import com.cecil.ngo.util.Csv;
import com.cecil.ngo.util.Json;
import com.cecil.ngo.util.Money;
import com.cecil.ngo.util.Passwords;
import com.cecil.ngo.web.ApiServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * All backend tests. A tiny test runner (no JUnit download needed):
 * each test is a method; it passes if it does not throw.
 * The clock is fixed at 30 Oct 2026, 15:30 India time, so every run gives the same dates.
 * Run with run-tests.bat / run-tests.sh, or `mvn test`.
 */
public class AllTests {

    interface Test { void run() throws Exception; }

    private static int passed, failed;
    private static final List<String> failures = new ArrayList<>();
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-30T10:00:00Z"), ZoneId.of("Asia/Kolkata"));

    public static void main(String[] args) throws Exception {
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true"); // lets the test send an Origin header
        test("JSON read and write", AllTests::json);
        test("Passwords are hashed and checked", AllTests::passwords);
        test("Money: Indian commas, amount in words, financial year", AllTests::money);
        test("CSV quoting and formula guard", AllTests::csv);
        test("Demo data follows every rule", AllTests::seedValid);
        test("Sign-up: seats, past, twice, inactive", AllTests::signUpRules);
        test("Sign-up: no two activities on the same day", AllTests::sameDayClash);
        test("Volunteers can only act for themselves", AllTests::volunteerLimits);
        test("Marking done: not before the day, people reached", AllTests::completeRules);
        test("Hours: done activity, signed up, 0.5-12, once", AllTests::hoursRules);
        test("Hours: volunteer entries wait, manager approves", AllTests::hoursApproval);
        test("Donations: receipt numbers per financial year", AllTests::receipts);
        test("Donations: cash limit, reference, future date", AllTests::donationChecks);
        test("Expenses: nobody approves their own", AllTests::ownExpense);
        test("Expenses: over budget needs the admin and a note", AllTests::overBudget);
        test("Programs: budget, completing, deleting", AllTests::programRules);
        test("Activities: dates inside the program", AllTests::activityDates);
        test("Beneficiaries: duplicate warning and privacy", AllTests::beneficiaries);
        test("Roles: who may do what", AllTests::roles);
        test("Logins: last admin, volunteer link", AllTests::userRules);
        test("Sign-in lock after 5 wrong passwords", AllTests::lockout);
        test("Report totals match the money records", AllTests::reportTotals);
        test("Dashboard to-do list", AllTests::dashboardTodo);
        test("Data is saved to disk and loaded back", AllTests::persistence);
        test("HTTP API end to end", AllTests::http);

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        for (String f : failures) System.out.println("  FAILED: " + f);
        if (failed > 0) System.exit(1);
    }

    private static void test(String name, Test t) {
        try {
            t.run();
            passed++;
            System.out.println("  ok    " + name);
        } catch (Throwable e) {
            failed++;
            failures.add(name + " -> " + e);
            System.out.println("  FAIL  " + name + " -> " + e);
        }
    }

    // ------------------------------------------------------------------ helpers

    static void check(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }
    static void eq(Object want, Object got, String msg) { if (!String.valueOf(want).equals(String.valueOf(got))) throw new AssertionError(msg + ": want " + want + ", got " + got); }

    interface Risky { void run() throws Exception; }

    /** The code must fail with this HTTP status (400, 403, 409...). Returns the message. */
    static String fails(int status, Risky r) {
        try { r.run(); } catch (ApiError e) {
            if (e.status != status) throw new AssertionError("expected " + status + " but got " + e.status + " (" + e.getMessage() + ")");
            return e.getMessage();
        } catch (IllegalArgumentException e) {
            if (status != 400) throw new AssertionError("expected " + status + " but got 400 (" + e.getMessage() + ")");
            return e.getMessage();
        } catch (Exception e) { throw new AssertionError("unexpected " + e); }
        throw new AssertionError("expected an error " + status + " but it worked");
    }

    /** A fresh copy of the demo data in a temporary folder, with all the services. */
    static final class World {
        final Path dir;
        final DataStore s;
        final ApiServer api;
        World() throws IOException {
            dir = Files.createTempDirectory("ngo-test");
            s = new DataStore(dir, CLOCK);
            s.load();
            api = new ApiServer(s, Path.of("."));
        }
        User u(String name) { return s.user(name); }
        LocalDate today() { return s.today(); }
    }

    static Map<String, Object> m(Object... kv) { return Json.map(kv); }

    /** A new planned activity in program P-01 on the given day. */
    static String newActivity(World w, int daysFromToday, int seats) {
        Map<String, Object> a = w.api.programs.createActivity(w.u("kavita"), m("programId", "P-01", "title", "Test class",
                "kind", "CLASS", "date", w.today().plusDays(daysFromToday).toString(), "place", "Hall", "volunteersNeeded", seats));
        return (String) a.get("id");
    }

    // ------------------------------------------------------------------ tests

    static void json() {
        Map<String, Object> back = Json.parseObject(Json.write(m("a", 1, "b", "x\"<y>\n", "c", List.of(true, 2.5), "d", null)));
        eq(1L, back.get("a"), "number"); eq("x\"<y>\n", back.get("b"), "string"); eq("[true, 2.5]", back.get("c"), "list");
        check(back.containsKey("d") && back.get("d") == null, "null");
        check(!Json.write("</script>").contains("<"), "< is escaped for HTML safety");
        fails(400, () -> Json.parse("{\"a\":}"));
        fails(400, () -> Json.parse("[1,2"));
    }

    static void passwords() {
        String h = Passwords.hash("secret123");
        check(h.startsWith("pbkdf2$"), "format");
        check(Passwords.verify("secret123", h), "right password");
        check(!Passwords.verify("secret124", h), "wrong password");
        check(!h.equals(Passwords.hash("secret123")), "random salt");
        fails(400, () -> Passwords.checkStrength("short1"));
        fails(400, () -> Passwords.checkStrength("lettersonly"));
    }

    static void money() {
        eq("₹0", Money.format(0), "zero"); eq("₹999", Money.format(999), "hundreds");
        eq("₹1,000", Money.format(1000), "thousand"); eq("₹1,23,456", Money.format(123456), "lakh");
        eq("₹1,00,00,000", Money.format(10_000_000), "crore"); eq("-₹5,000", Money.format(-5000), "minus");
        eq("One Lakh Fifty Thousand Rupees Only", Money.inWords(150000), "words 1");
        eq("Two Thousand Rupees Only", Money.inWords(2000), "words 2");
        eq("Twelve Lakh Thirty Four Thousand Five Hundred Sixty Seven Rupees Only", Money.inWords(1234567), "words 3");
        eq("One Crore Five Rupees Only", Money.inWords(10_000_005), "words 4");
        eq("Nine Hundred Ninety Nine Rupees Only", Money.inWords(999), "words 5");
        eq("2026-27", Money.financialYear(LocalDate.of(2026, 4, 1)), "FY start");
        eq("2025-26", Money.financialYear(LocalDate.of(2026, 3, 31)), "FY end");
        eq("2099-00", Money.financialYear(LocalDate.of(2099, 12, 1)), "FY century");
        eq(50, Money.percent(1, 2), "percent"); eq(0, Money.percent(5, 0), "percent of zero");
        fails(400, () -> Money.amount(0L, "Amount"));
        fails(400, () -> Money.amount(Money.MAX_AMOUNT + 1, "Amount"));
    }

    static void csv() {
        eq("\"a,b\"", Csv.cell("a,b"), "comma");
        eq("\"say \"\"hi\"\"\"", Csv.cell("say \"hi\""), "quote");
        eq("'=SUM(A1)", Csv.cell("=SUM(A1)"), "formula guard");
        List<List<String>> rows = Csv.parse("x,\"y,z\"\r\n\"a\"\"b\",c\n");
        eq(2, rows.size(), "rows"); eq("y,z", rows.get(0).get(1), "quoted comma"); eq("a\"b", rows.get(1).get(0), "escaped quote");
    }

    static void seedValid() throws Exception {
        World w = new World();
        DataStore s = w.s;
        check(s.programs.size() == 6 && s.volunteers.size() == 16 && s.activities.size() > 40, "sizes");
        // no volunteer on two planned/done activities on the same day
        Map<String, Set<String>> perDay = new HashMap<>();
        for (Activity a : s.activities) {
            if (a.status == ActivityStatus.CANCELLED) continue;
            for (String v : a.volunteerIds) check(perDay.computeIfAbsent(a.date, k -> new HashSet<>()).add(v), "double booking " + v + " on " + a.date);
            check(a.volunteerIds.size() <= a.volunteersNeeded, "too many on " + a.id);
            Program p = s.program(a.programId);
            check(!LocalDate.parse(a.date).isBefore(LocalDate.parse(p.startDate)), "activity before program " + a.id);
            if (a.status == ActivityStatus.DONE) check(!LocalDate.parse(a.date).isAfter(w.today()), "done in future " + a.id);
        }
        // approved spending stays inside every budget
        for (Program p : s.programs) check(Calc.spent(s, p.id, null, null) <= p.budget, "over budget " + p.id);
        // hours only for done activities the volunteer was on
        for (HoursLog h : s.hours) {
            Activity a = s.activity(h.activityId);
            check(a.status == ActivityStatus.DONE && a.volunteerIds.contains(h.volunteerId), "bad hours " + h.id);
            check(h.hours >= 0.5 && h.hours <= 12, "hours range " + h.id);
        }
        // receipt numbers unique and continuous within each financial year
        Map<String, Integer> last = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (Donation d : s.donations) {
            check(seen.add(d.receiptNo), "duplicate receipt " + d.receiptNo);
            String fy = Money.financialYear(LocalDate.parse(d.date));
            check(d.receiptNo.startsWith("RCPT/" + fy + "/"), "receipt year " + d.receiptNo);
            int n = Integer.parseInt(d.receiptNo.substring(d.receiptNo.lastIndexOf('/') + 1));
            eq(last.getOrDefault(fy, 0) + 1, n, "receipt sequence");
            last.put(fy, n);
        }
        // nobody approved their own expense
        for (Expense e : s.expenses) if (e.status != Review.PENDING) check(!e.submittedBy.equals(e.reviewedBy), "self approval " + e.id);
        // volunteer logins point at real volunteers
        for (User u : s.users) if (u.volunteerId != null) check(s.volunteer(u.volunteerId) != null, "link " + u.username);
    }

    static void signUpRules() throws Exception {
        World w = new World();
        String a = newActivity(w, 30, 2);
        User k = w.u("kavita");
        w.api.programs.signUp(k, a, m("volunteerId", "V-003"));
        fails(409, () -> w.api.programs.signUp(k, a, m("volunteerId", "V-003"))); // twice
        w.api.programs.signUp(k, a, m("volunteerId", "V-004"));
        fails(409, () -> w.api.programs.signUp(k, a, m("volunteerId", "V-005"))); // full
        w.api.programs.withdraw(k, a, m("volunteerId", "V-004"));
        eq(1, w.s.activity(a).seatsLeft(), "seat freed");
        fails(409, () -> w.api.programs.signUp(k, a, m("volunteerId", "V-016"))); // V-016 is not active
        // a past activity cannot take sign-ups
        Activity past = w.s.activities.stream().filter(x -> x.status == ActivityStatus.PLANNED && LocalDate.parse(x.date).isBefore(w.today())).findFirst().orElseThrow();
        fails(409, () -> w.api.programs.signUp(k, past.id, m("volunteerId", "V-010")));
        // a done activity neither
        Activity done = w.s.activities.stream().filter(x -> x.status == ActivityStatus.DONE).findFirst().orElseThrow();
        fails(409, () -> w.api.programs.signUp(k, done.id, m("volunteerId", "V-010")));
    }

    static void sameDayClash() throws Exception {
        World w = new World();
        String a = newActivity(w, 40, 3), b = newActivity(w, 40, 3), c = newActivity(w, 41, 3);
        User k = w.u("kavita");
        w.api.programs.signUp(k, a, m("volunteerId", "V-007"));
        String msg = fails(409, () -> w.api.programs.signUp(k, b, m("volunteerId", "V-007")));
        check(msg.contains("that day"), "message names the clash: " + msg);
        w.api.programs.signUp(k, c, m("volunteerId", "V-007")); // next day is fine
        w.api.programs.cancel(k, a, m("reason", "rain"));
        w.api.programs.signUp(k, b, m("volunteerId", "V-007")); // the clash is gone once a is cancelled
    }

    static void volunteerLimits() throws Exception {
        World w = new World();
        User aman = w.u("aman");
        String a = newActivity(w, 30, 3);
        fails(403, () -> w.api.programs.signUp(aman, a, m("volunteerId", "V-005")));  // not himself
        w.api.programs.signUp(aman, a, m());
        check(w.s.activity(a).volunteerIds.contains(aman.volunteerId), "signed himself up");
        Map<String, Object> view = w.api.programs.activity(aman, a);
        check(!view.containsKey("volunteers") && Boolean.TRUE.equals(view.get("iAmIn")), "volunteer sees no names, only himself");
        fails(403, () -> w.api.funds.donations(aman, Map.of()));
        fails(403, () -> w.api.funds.expenses(aman, Map.of()));
        fails(403, () -> w.api.beneficiaries.list(aman, Map.of()));
        fails(403, () -> w.api.volunteers.list(aman, Map.of()));
        fails(403, () -> w.api.volunteers.get(aman, "V-002"));
        w.api.volunteers.get(aman, aman.volunteerId);
        // he may edit his own phone but not his name
        w.api.volunteers.update(aman, aman.volunteerId, new java.util.LinkedHashMap<>(m("name", "Hacker", "phone", "9876543210")));
        eq("Aman Kujur", w.s.volunteer(aman.volunteerId).name, "name unchanged");
        eq("9876543210", w.s.volunteer(aman.volunteerId).phone, "phone changed");
        // he only sees his own hours
        for (Map<String, Object> h : w.api.volunteers.hours(aman, Map.of())) eq(aman.volunteerId, h.get("volunteerId"), "own hours only");
    }

    static void completeRules() throws Exception {
        World w = new World();
        User k = w.u("kavita");
        String future = newActivity(w, 3, 2);
        fails(409, () -> w.api.programs.complete(k, future, m("reached", 10)));
        Activity past = w.s.activities.stream().filter(x -> x.status == ActivityStatus.PLANNED && LocalDate.parse(x.date).isBefore(w.today())).findFirst().orElseThrow();
        fails(400, () -> w.api.programs.complete(k, past.id, m("reached", -1)));
        fails(400, () -> w.api.programs.complete(k, past.id, m()));
        w.api.programs.complete(k, past.id, m("reached", 35, "outcome", "Good"));
        eq(ActivityStatus.DONE, past.status, "done");
        fails(409, () -> w.api.programs.complete(k, past.id, m("reached", 1)));
        fails(409, () -> w.api.programs.cancel(k, past.id, m("reason", "x")));
        fails(409, () -> w.api.programs.deleteActivity(k, past.id));
        fails(400, () -> w.api.programs.cancel(k, future, m("reason", "")));
    }

    static void hoursRules() throws Exception {
        World w = new World();
        User k = w.u("kavita");
        Activity past = w.s.activities.stream().filter(x -> x.status == ActivityStatus.PLANNED && LocalDate.parse(x.date).isBefore(w.today())).findFirst().orElseThrow();
        String vid = past.volunteerIds.get(0);
        fails(409, () -> w.api.volunteers.logHours(k, m("volunteerId", vid, "activityId", past.id, "hours", 3))); // not done yet
        w.api.programs.complete(k, past.id, m("reached", 20));
        fails(409, () -> w.api.volunteers.logHours(k, m("volunteerId", "V-013", "activityId", past.id, "hours", 3))); // not signed up
        fails(400, () -> w.api.volunteers.logHours(k, m("volunteerId", vid, "activityId", past.id, "hours", 0.2)));
        fails(400, () -> w.api.volunteers.logHours(k, m("volunteerId", vid, "activityId", past.id, "hours", 13)));
        Map<String, Object> h = w.api.volunteers.logHours(k, m("volunteerId", vid, "activityId", past.id, "hours", 2.7));
        eq(2.5, h.get("hours"), "rounded to half hours");
        eq(Review.APPROVED, h.get("status"), "manager entry approved at once");
        fails(409, () -> w.api.volunteers.logHours(k, m("volunteerId", vid, "activityId", past.id, "hours", 3))); // only once
    }

    static void hoursApproval() throws Exception {
        World w = new World();
        User aman = w.u("aman");
        List<Map<String, Object>> todo = w.api.volunteers.unlogged(aman, null);
        check(!todo.isEmpty(), "aman has an activity to log");
        Map<String, Object> h = w.api.volunteers.logHours(aman, m("activityId", todo.get(0).get("id"), "hours", 4));
        eq(Review.PENDING, h.get("status"), "waits");
        String id = (String) h.get("id");
        fails(403, () -> w.api.volunteers.review(aman, id, true));
        fails(403, () -> w.api.volunteers.review(w.u("rahul"), id, true)); // finance cannot approve hours
        w.api.volunteers.review(w.u("kavita"), id, true);
        eq(Review.APPROVED, w.s.hoursLog(id).status, "approved");
        fails(409, () -> w.api.volunteers.review(w.u("kavita"), id, false));
        check(w.api.volunteers.unlogged(aman, null).size() == todo.size() - 1, "no longer in the to-log list");
    }

    static void receipts() throws Exception {
        World w = new World();
        User r = w.u("rahul");
        Map<String, Object> a = w.api.funds.recordDonation(r, m("donorId", "D-005", "amount", 1000, "date", "2026-10-29", "mode", "UPI", "reference", "UTRTEST1"));
        Map<String, Object> b = w.api.funds.recordDonation(r, m("donorId", "D-005", "amount", 2000, "date", "2026-10-30", "mode", "UPI", "reference", "UTRTEST2"));
        int na = Integer.parseInt(((String) a.get("receiptNo")).substring(13)), nb = Integer.parseInt(((String) b.get("receiptNo")).substring(13));
        check(((String) a.get("receiptNo")).startsWith("RCPT/2026-27/"), "this year's series");
        eq(na + 1, nb, "next number");
        // a gift dated in the old financial year continues that year's series
        Map<String, Object> old = w.api.funds.recordDonation(r, m("donorId", "D-005", "amount", 500, "date", "2026-03-15", "mode", "CASH"));
        check(((String) old.get("receiptNo")).startsWith("RCPT/2025-26/"), "old year series: " + old.get("receiptNo"));
        Map<String, Object> rc = w.api.funds.receipt(r, (String) b.get("id"));
        eq("Two Thousand Rupees Only", rc.get("amountWords"), "words on receipt");
        eq("₹2,000", rc.get("amountText"), "amount on receipt");
        // deleting does not free the number
        fails(403, () -> w.api.funds.deleteDonation(r, (String) b.get("id")));
        w.api.funds.deleteDonation(w.u("admin"), (String) b.get("id"));
        Map<String, Object> c = w.api.funds.recordDonation(r, m("donorId", "D-005", "amount", 300, "date", "2026-10-30", "mode", "CASH"));
        int nc = Integer.parseInt(((String) c.get("receiptNo")).substring(13));
        check(nc > nb, "deleted number " + nb + " is not reused (got " + nc + ")");
    }

    static void donationChecks() throws Exception {
        World w = new World();
        User r = w.u("rahul");
        fails(400, () -> w.api.funds.recordDonation(r, m("donorId", "D-005", "amount", 5000, "date", "2026-10-30", "mode", "CASH")));
        fails(400, () -> w.api.funds.recordDonation(r, m("donorId", "D-005", "amount", 5000, "date", "2026-10-30", "mode", "UPI")));
        fails(400, () -> w.api.funds.recordDonation(r, m("donorId", "D-005", "amount", 5000, "date", "2026-11-02", "mode", "UPI", "reference", "X1")));
        fails(400, () -> w.api.funds.recordDonation(r, m("donorId", "D-005", "amount", -5, "date", "2026-10-30", "mode", "UPI", "reference", "X2")));
        fails(404, () -> w.api.funds.recordDonation(r, m("donorId", "D-999", "amount", 5, "date", "2026-10-30", "mode", "UPI", "reference", "X3")));
        fails(409, () -> w.api.funds.recordDonation(r, m("donorId", "D-005", "amount", 500, "date", "2026-10-30", "mode", "UPI", "reference", "X4", "programId", "P-05"))); // completed program
        w.api.funds.recordDonation(r, m("donorId", "D-005", "amount", 500, "date", "2026-10-30", "mode", "UPI", "reference", "SAME-REF"));
        fails(409, () -> w.api.funds.recordDonation(r, m("donorId", "D-006", "amount", 700, "date", "2026-10-30", "mode", "UPI", "reference", "same-ref")));
        fails(403, () -> w.api.funds.recordDonation(w.u("kavita"), m("donorId", "D-005", "amount", 500, "date", "2026-10-30", "mode", "CASH")));
        fails(409, () -> w.api.funds.deleteDonor(r, "D-005")); // has gifts
    }

    static void ownExpense() throws Exception {
        World w = new World();
        Map<String, Object> e = w.api.funds.submitExpense(w.u("rahul"), m("programId", "P-04", "category", "TRAVEL", "amount", 900,
                "date", "2026-10-29", "vendor", "Auto", "description", "Travel"));
        String id = (String) e.get("id");
        fails(403, () -> w.api.funds.reviewExpense(w.u("rahul"), id, m("approve", true)));
        fails(403, () -> w.api.funds.reviewExpense(w.u("kavita"), id, m("approve", true))); // managers cannot approve
        fails(400, () -> w.api.funds.reviewExpense(w.u("admin"), id, m("approve", false)));  // reject needs a reason
        w.api.funds.reviewExpense(w.u("admin"), id, m("approve", true));
        eq(Review.APPROVED, w.s.expense(id).status, "approved by someone else");
        fails(409, () -> w.api.funds.deleteExpense(w.u("rahul"), id)); // no longer waiting
        // checks on the form
        fails(400, () -> w.api.funds.submitExpense(w.u("kavita"), m("programId", "P-01", "category", "FOOD", "amount", 100, "date", "2026-11-05", "vendor", "a", "description", "b")));
        fails(409, () -> w.api.funds.submitExpense(w.u("kavita"), m("programId", "P-05", "category", "FOOD", "amount", 100, "date", "2026-10-01", "vendor", "a", "description", "b")));
        fails(403, () -> w.api.funds.submitExpense(w.u("aman"), m("programId", "P-01", "category", "FOOD", "amount", 100, "date", "2026-10-01", "vendor", "a", "description", "b")));
    }

    static void overBudget() throws Exception {
        World w = new World();
        Expense pipes = w.s.expenses.stream().filter(x -> x.status == Review.PENDING && x.programId.equals("P-03")).findFirst().orElseThrow();
        Map<String, Object> view = w.api.funds.expenses(w.u("rahul"), Map.of("status", "PENDING")).stream()
                .filter(x -> x.get("id").equals(pipes.id)).findFirst().orElseThrow();
        eq(true, view.get("overBudget"), "flagged as over budget");
        String msg = fails(409, () -> w.api.funds.reviewExpense(w.u("rahul"), pipes.id, m("approve", true)));
        check(msg.contains("admin"), "finance is told only the admin can: " + msg);
        fails(400, () -> w.api.funds.reviewExpense(w.u("admin"), pipes.id, m("approve", true))); // needs a note
        w.api.funds.reviewExpense(w.u("admin"), pipes.id, m("approve", true, "note", "Monsoon damage - board agreed"));
        check(Calc.spent(w.s, "P-03", null, null) > w.s.program("P-03").budget, "now over budget");
        // finance can still reject it without the admin
        Expense small = w.s.expenses.stream().filter(x -> x.status == Review.PENDING && x.programId.equals("P-01")).findFirst().orElseThrow();
        w.api.funds.reviewExpense(w.u("rahul"), small.id, m("approve", false, "note", "Need the bill"));
        eq(Review.REJECTED, small.status, "rejected");
    }

    static void programRules() throws Exception {
        World w = new World();
        User k = w.u("kavita");
        long spent = Calc.spent(w.s, "P-01", null, null);
        fails(400, () -> w.api.programs.update(k, "P-01", m("budget", spent - 1)));
        w.api.programs.update(k, "P-01", m("budget", spent));
        String msg = fails(409, () -> w.api.programs.update(k, "P-01", m("status", "COMPLETED")));
        check(msg.contains("planned"), "explains why: " + msg);
        fails(409, () -> w.api.programs.create(k, m("name", "shiksha evening learning centres", "cause", "EDUCATION", "location", "x",
                "startDate", "2026-10-01", "budget", 0, "targetBeneficiaries", 0, "lead", "kavita")));
        fails(400, () -> w.api.programs.create(k, m("name", "New one", "cause", "EDUCATION", "location", "x",
                "startDate", "2026-10-01", "endDate", "2026-09-01", "budget", 0, "targetBeneficiaries", 0, "lead", "kavita")));
        fails(400, () -> w.api.programs.create(k, m("name", "New one", "cause", "EDUCATION", "location", "x",
                "startDate", "2026-10-01", "budget", 0, "targetBeneficiaries", 0, "lead", "rahul"))); // lead must be a manager
        Map<String, Object> p = w.api.programs.create(k, m("name", "New one", "cause", "HEALTH", "location", "x",
                "startDate", "2026-10-01", "budget", 1000, "targetBeneficiaries", 10, "lead", "deepak"));
        fails(403, () -> w.api.programs.delete(k, (String) p.get("id")));
        fails(409, () -> w.api.programs.delete(w.u("admin"), "P-01"));
        w.api.programs.delete(w.u("admin"), (String) p.get("id"));
        check(w.s.program((String) p.get("id")) == null, "deleted");
    }

    static void activityDates() throws Exception {
        World w = new World();
        User k = w.u("kavita");
        fails(400, () -> w.api.programs.createActivity(k, m("programId", "P-03", "title", "t", "kind", "DRIVE", "date", "2027-02-01", "place", "p", "volunteersNeeded", 1)));
        fails(400, () -> w.api.programs.createActivity(k, m("programId", "P-01", "title", "t", "kind", "DRIVE", "date", "2026-10-29", "place", "p", "volunteersNeeded", 1)));
        fails(409, () -> w.api.programs.createActivity(k, m("programId", "P-05", "title", "t", "kind", "DRIVE", "date", "2026-11-02", "place", "p", "volunteersNeeded", 1)));
        fails(400, () -> w.api.programs.createActivity(k, m("programId", "P-01", "title", "t", "kind", "DRIVE", "date", "2026-11-02", "time", "25:00", "place", "p", "volunteersNeeded", 1)));
        String a = newActivity(w, 10, 3);
        w.api.programs.signUp(k, a, m("volunteerId", "V-002"));
        w.api.programs.signUp(k, a, m("volunteerId", "V-003"));
        fails(409, () -> w.api.programs.updateActivity(k, a, m("volunteersNeeded", 1)));
        w.api.programs.updateActivity(k, a, m("volunteersNeeded", 2, "title", "Renamed"));
        eq("Renamed", w.s.activity(a).title, "edited");
    }

    static void beneficiaries() throws Exception {
        World w = new World();
        User k = w.u("kavita");
        Map<String, Object> body = m("name", "Test Person", "village", "Ormanjhi", "gender", "FEMALE", "ageGroup", "CHILD", "programIds", List.of("P-01"));
        w.api.beneficiaries.save(k, null, body);
        Map<String, Object> again = m("name", "test  person", "village", "ormanjhi", "gender", "FEMALE", "ageGroup", "YOUTH", "programIds", List.of("P-01"));
        String msg = fails(409, () -> w.api.beneficiaries.save(k, null, again));
        check(msg.contains("already registered"), msg);
        again.put("allowDuplicate", true);
        w.api.beneficiaries.save(k, null, again);
        fails(400, () -> w.api.beneficiaries.save(k, null, m("name", "X", "village", "Y", "gender", "MALE", "ageGroup", "ADULT", "programIds", List.of())));
        fails(400, () -> w.api.beneficiaries.save(k, null, m("name", "X", "village", "Y", "gender", "MALE", "ageGroup", "ADULT", "programIds", List.of("P-99"))));
        fails(403, () -> w.api.beneficiaries.list(w.u("rahul"), Map.of())); // finance cannot see personal data
        Map<String, Object> l = w.api.beneficiaries.list(k, Map.of("program", "P-01", "q", "test person"));
        eq(2, l.get("total"), "both saved");
    }

    static void roles() {
        World w;
        try { w = new World(); } catch (IOException e) { throw new RuntimeException(e); }
        World x = w;
        fails(403, () -> x.api.programs.create(x.u("rahul"), m()));
        fails(403, () -> x.api.programs.createActivity(x.u("rahul"), m()));
        fails(403, () -> x.api.volunteers.create(x.u("rahul"), m()));
        fails(403, () -> x.api.funds.saveDonor(x.u("kavita"), null, m()));
        fails(403, () -> x.api.funds.reviewExpense(x.u("deepak"), "E-0001", m("approve", true)));
        fails(403, () -> x.api.dash.saveSettings(x.u("kavita"), m("orgName", "x")));
        fails(403, () -> x.api.users.create(x.u("kavita"), m()));
        fails(403, () -> x.api.dash.report(x.u("aman"), Map.of()));
        check(AuthService.canFinance(x.u("admin")) && AuthService.canPrograms(x.u("admin")), "admin can do both");
    }

    static void userRules() throws Exception {
        World w = new World();
        User admin = w.u("admin");
        fails(409, () -> w.api.users.update(admin, "admin", m("role", "MANAGER")));
        fails(409, () -> w.api.users.update(admin, "admin", m("active", false)));
        fails(400, () -> w.api.users.create(admin, m("username", "Bad Name", "displayName", "x", "role", "MANAGER", "password", "abcd1234")));
        fails(409, () -> w.api.users.create(admin, m("username", "kavita", "displayName", "x", "role", "MANAGER", "password", "abcd1234")));
        fails(400, () -> w.api.users.create(admin, m("username", "neha", "displayName", "Neha", "role", "VOLUNTEER", "password", "abcd1234"))); // no link
        fails(409, () -> w.api.users.create(admin, m("username", "neha", "displayName", "Neha", "role", "VOLUNTEER", "password", "abcd1234", "volunteerId", "V-001"))); // already linked to aman
        w.api.users.create(admin, m("username", "neha", "displayName", "Neha Ekka", "role", "VOLUNTEER", "password", "abcd1234", "volunteerId", "V-004"));
        eq("V-004", w.u("neha").volunteerId, "linked");
        w.api.users.create(admin, m("username", "boss2", "displayName", "Second admin", "role", "ADMIN", "password", "abcd1234"));
        w.api.users.update(w.u("boss2"), "admin", m("active", false)); // fine now: another admin exists
        check(!w.u("admin").active, "turned off");
    }

    static void lockout() throws Exception {
        World w = new World();
        for (int i = 0; i < AuthService.MAX_FAILS; i++) fails(401, () -> w.api.auth.login("rahul", "wrong"));
        fails(429, () -> w.api.auth.login("rahul", "finance123"));
        AuthService.Session s = w.api.auth.login("kavita", "manager123");
        eq("kavita", w.api.auth.require(s.token).username, "token works");
        w.api.auth.logout(s.token);
        fails(401, () -> w.api.auth.require(s.token));
    }

    @SuppressWarnings("unchecked")
    static void reportTotals() throws Exception {
        World w = new World();
        Map<String, Object> r = w.api.dash.report(w.u("admin"), Map.of("from", "2000-01-01", "to", "2026-10-30"));
        Map<String, Object> f = w.api.funds.position(w.u("admin"));
        Map<String, Object> income = (Map<String, Object>) r.get("income"), spending = (Map<String, Object>) r.get("spending");
        eq(f.get("received"), income.get("total"), "income = all donations");
        eq(f.get("spent"), spending.get("total"), "spending = approved expenses");
        long byType = 0;
        for (Object v : ((Map<String, Object>) income.get("byType")).values()) byType += ((Number) v).longValue();
        eq(income.get("total"), byType, "donor types add up");
        Map<String, Object> totals = (Map<String, Object>) r.get("totals");
        eq(spending.get("total"), totals.get("spent"), "programs add up to spending");
        fails(400, () -> w.api.dash.report(w.u("admin"), Map.of("from", "2026-10-30", "to", "2026-01-01")));
    }

    @SuppressWarnings("unchecked")
    static void dashboardTodo() throws Exception {
        World w = new World();
        List<Object> rahulTodo = (List<Object>) w.api.dash.dashboard(w.u("rahul")).get("todo");
        Map<String, Object> exp = (Map<String, Object>) rahulTodo.stream().filter(t -> "expenses".equals(((Map<String, Object>) t).get("kind"))).findFirst().orElseThrow();
        eq(3L, exp.get("count"), "rahul sees 3 (not his own)");
        List<Object> kTodo = (List<Object>) w.api.dash.dashboard(w.u("kavita")).get("todo");
        check(kTodo.stream().anyMatch(t -> "close".equals(((Map<String, Object>) t).get("kind"))), "past activity to close");
        check(kTodo.stream().anyMatch(t -> "budget".equals(((Map<String, Object>) t).get("kind"))), "budget warning");
        Map<String, Object> vd = w.api.dash.dashboard(w.u("aman"));
        check(vd.containsKey("toLog") && !vd.containsKey("kpis"), "volunteer gets the volunteer home");
    }

    static void persistence() throws Exception {
        World w = new World();
        String a = newActivity(w, 20, 4);
        w.api.programs.signUp(w.u("kavita"), a, m("volunteerId", "V-009"));
        DataStore again = new DataStore(w.dir, CLOCK);
        check(!again.load(), "loads existing files (no new demo data)");
        eq(w.s.activities.size(), again.activities.size(), "same activities");
        check(again.activity(a).volunteerIds.contains("V-009"), "sign-up saved");
        String next = again.newId("A", 3);
        check(next.compareTo(a) > 0, "ids keep counting up: " + next + " after " + a);
        try (var files = Files.list(w.dir)) { check(files.noneMatch(p -> p.toString().endsWith(".tmp")), "no temp files left"); }
    }

    // ---- HTTP

    record Res(int status, String body, Map<String, List<String>> headers) {}

    static Res call(int port, String method, String path, String token, String body, String origin) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + path).toURL().openConnection();
        c.setRequestMethod(method);
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        if (origin != null) c.setRequestProperty("Origin", origin);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        }
        int st = c.getResponseCode();
        InputStream in = st >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        return new Res(st, text, c.getHeaderFields());
    }

    static String header(Res r, String name) {
        for (Map.Entry<String, List<String>> e : r.headers.entrySet())
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue().get(0);
        throw new AssertionError("missing header " + name);
    }

    @SuppressWarnings("unchecked")
    static void http() throws Exception {
        Path dir = Files.createTempDirectory("ngo-http");
        Path front = dir.resolve("frontend");
        Files.createDirectories(front);
        Files.writeString(front.resolve("index.html"), "<html>hi</html>");
        DataStore s = new DataStore(dir.resolve("data"), CLOCK);
        s.load();
        ApiServer api = new ApiServer(s, front);
        api.start("127.0.0.1", 0);
        int port = api.port();
        try {
            Res home = call(port, "GET", "/", null, null, null);
            eq(200, home.status, "home page");
            check(header(home, "Content-Security-Policy").contains("script-src 'self'"), "CSP header");
            eq(404, call(port, "GET", "/static/../data/users.json", null, null, null).status, "no path tricks");
            eq(401, call(port, "GET", "/api/programs", null, null, null).status, "needs sign-in");
            eq(401, call(port, "POST", "/api/auth/login", null, "{\"username\":\"admin\",\"password\":\"nope\"}", null).status, "wrong password");
            Res login = call(port, "POST", "/api/auth/login", null, "{\"username\":\"admin\",\"password\":\"admin123\"}", null);
            eq(200, login.status, "login");
            String token = (String) Json.parseObject(login.body).get("token");
            check(!login.body.contains("passwordHash"), "hash never sent");
            Res progs = call(port, "GET", "/api/programs", token, null, null);
            eq(6, ((List<Object>) Json.parse(progs.body)).size(), "programs");
            eq(403, call(port, "POST", "/api/donors", token, "{\"name\":\"X\",\"type\":\"INDIVIDUAL\"}", "http://evil.example").status, "other websites blocked");
            Res d = call(port, "POST", "/api/donors", token, "{\"name\":\"New Donor\",\"type\":\"INDIVIDUAL\",\"city\":\"Ranchi\"}", "http://127.0.0.1:" + port);
            eq(200, d.status, "same-site post works");
            eq(400, call(port, "POST", "/api/donors", token, "{bad json", null).status, "bad JSON");
            eq(405, call(port, "DELETE", "/api/dashboard", token, null, null).status, "wrong method");
            Res csv = call(port, "GET", "/api/donations/export.csv", token, null, null);
            eq(200, csv.status, "csv");
            check(csv.body.contains("RCPT/") && header(csv, "Content-Disposition").contains("donations.csv"), "csv content");
            Res vLogin = call(port, "POST", "/api/auth/login", null, "{\"username\":\"aman\",\"password\":\"volunteer123\"}", null);
            String vt = (String) Json.parseObject(vLogin.body).get("token");
            eq(403, call(port, "GET", "/api/donations", vt, null, null).status, "volunteer blocked from money");
            eq(200, call(port, "GET", "/api/activities?open=1", vt, null, null).status, "volunteer sees activities");
        } finally {
            api.stop();
        }
    }
}
