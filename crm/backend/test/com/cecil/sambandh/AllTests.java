package com.cecil.sambandh;

import com.cecil.sambandh.model.Customer;
import com.cecil.sambandh.model.FollowUp;
import com.cecil.sambandh.model.Order;
import com.cecil.sambandh.model.Role;
import com.cecil.sambandh.model.Stage;
import com.cecil.sambandh.model.Ticket;
import com.cecil.sambandh.model.User;
import com.cecil.sambandh.service.ActivityService;
import com.cecil.sambandh.service.AuthService;
import com.cecil.sambandh.service.CustomerService;
import com.cecil.sambandh.service.DashboardService;
import com.cecil.sambandh.service.Duplicates;
import com.cecil.sambandh.service.ImportService;
import com.cecil.sambandh.service.Insights;
import com.cecil.sambandh.service.Problem;
import com.cecil.sambandh.store.DataStore;
import com.cecil.sambandh.store.SeedData;
import com.cecil.sambandh.util.Clock;
import com.cecil.sambandh.util.Csv;
import com.cecil.sambandh.util.Json;
import com.cecil.sambandh.util.Money;
import com.cecil.sambandh.util.Passwords;
import com.cecil.sambandh.util.Text;
import com.cecil.sambandh.web.ApiServer;

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
import java.util.List;
import java.util.Map;

/**
 * All tests for Sambandh CRM, without JUnit (so nothing has to be downloaded).
 * Run with run-tests.bat / run-tests.sh. The clock is frozen at 7 Nov 2026, 10:30 IST, so results never change.
 */
public final class AllTests {
    private static int passed, failed;
    private static final List<String> failures = new ArrayList<>();

    interface Body { void run() throws Exception; }

    static void test(String name, Body b) {
        try {
            b.run();
            passed++;
            System.out.println("  ok    " + name);
        } catch (Throwable t) {
            failed++;
            failures.add(name + " -> " + t);
            System.out.println("  FAIL  " + name + "  (" + t + ")");
        }
    }

    static void check(boolean cond, String msg) { if (!cond) throw new AssertionError(msg); }
    static void eq(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
    }
    static int problem(Body b) {
        try { b.run(); } catch (Problem p) { return p.status; } catch (Exception e) { throw new AssertionError("wrong exception " + e); }
        throw new AssertionError("expected a Problem");
    }

    // a fresh world for each group of tests
    static DataStore store;
    static Insights insights;
    static AuthService auth;
    static CustomerService customers;
    static ActivityService activity;
    static Duplicates dupes;
    static ImportService importer;
    static DashboardService dashboard;
    static User admin, manager, arjun, kavya;

    static void fresh() {
        store = new DataStore(null);
        SeedData.load(store);
        insights = new Insights(store);
        auth = new AuthService(store);
        customers = new CustomerService(store, insights);
        dupes = new Duplicates(store, customers);
        customers.setDuplicates(dupes);
        activity = new ActivityService(store, customers);
        importer = new ImportService(store, customers);
        dashboard = new DashboardService(store, insights);
        admin = store.userByName("admin");
        manager = store.userByName("ritu");
        arjun = store.userByName("arjun");
        kavya = store.userByName("kavya");
    }

    static Map<String, Object> form(Object... kv) { return Json.obj(kv); }

    static Customer newCustomer(User by, String name, String phone, String email) {
        return customers.create(by, form("name", name, "phone", phone, "email", email, "city", "Ranchi"));
    }

    public static void main(String[] args) throws Exception {
        Clock.fix(Instant.parse("2026-11-07T05:00:00Z"));
        System.out.println("Sambandh CRM - tests (clock fixed at " + Clock.now() + ")\n");

        System.out.println("Utilities");
        test("JSON: write and read back, with escaping", () -> {
            Map<String, Object> m = Json.obj("a", "x\"<y>\n", "n", 12L, "d", 1.5, "l", List.of(true, false), "z", null);
            String s = Json.write(m);
            check(!s.contains("<"), "angle brackets are escaped");
            eq(m, Json.parse(s));
        });
        test("JSON: bad input is refused, deep nesting is refused", () -> {
            for (String bad : new String[]{"{", "{\"a\":}", "[1,]", "tru", "\"x", "{} x"}) {
                try { Json.parse(bad); throw new AssertionError("accepted " + bad); } catch (IllegalArgumentException ok) { }
            }
            try { Json.parse("[".repeat(100) + "]".repeat(100)); throw new AssertionError("too deep accepted"); } catch (IllegalArgumentException ok) { }
        });
        test("CSV: quotes, commas and new lines inside cells", () -> {
            List<List<String>> r = Csv.parse("a,b\r\n\"x, y\",\"say \"\"hi\"\"\"\n\"two\nlines\",z\n");
            eq(3, r.size());
            eq("x, y", r.get(1).get(0));
            eq("say \"hi\"", r.get(1).get(1));
            eq("two\nlines", r.get(2).get(0));
        });
        test("CSV: formula injection is defused on export", () -> {
            eq("'=SUM(A1),'+919835123456,ok\r\n", Csv.line(List.of("=SUM(A1)", "+919835123456", "ok")));
        });
        test("Money: Indian grouping and parsing", () -> {
            eq("₹12,50,000", Money.format(1_25_000_000L));
            eq("₹999.50", Money.format(99_950L));
            eq(250_050L, Money.parseRupees("2,500.50"));
            eq(-1L, Money.parseRupees("12abc"));
            eq(-1L, Money.parseRupees("-5"));
        });
        test("Passwords: PBKDF2 hash verifies, wrong password fails, rules", () -> {
            String h = Passwords.hash("secret123");
            check(h.startsWith("pbkdf2$"), "format");
            check(Passwords.verify("secret123", h), "right password");
            check(!Passwords.verify("secret124", h), "wrong password");
            check(!h.equals(Passwords.hash("secret123")), "salted");
            check(Passwords.problem("short1") != null && Passwords.problem("longpassword") != null && Passwords.problem("goodpass1") == null, "rules");
        });
        test("Phone numbers: every Indian format becomes +91XXXXXXXXXX", () -> {
            for (String p : new String[]{"98351 23456", "+91-9835123456", "09835123456", "919835123456", "(983) 512-3456"})
                eq("+919835123456", Text.normalizePhone(p));
            eq(null, Text.normalizePhone("12345"));
            eq(null, Text.normalizePhone("0123456789"));
        });
        test("E-mail check", () -> {
            check(Text.validEmail("a.b+c@mail.example.in"), "valid");
            check(!Text.validEmail("not-an-email") && !Text.validEmail("a@b") && !Text.validEmail("a b@c.in"), "invalid");
        });
        test("Names: Jaro-Winkler, word order, titles", () -> {
            check(Text.jaroWinkler("martha", "marhta") > 0.95, "classic example 0.961");
            eq(1.0, Text.nameSimilarity("Kumar Rahul", "Rahul Kumar"));
            eq(1.0, Text.nameSimilarity("Dr. Rahul Kumar", "rahul  kumar"));
            check(Text.nameSimilarity("Rani Hussain", "Rani Hussai") >= 0.9, "typo matches");
            check(Text.nameSimilarity("Sneha Sinha", "Sneha Jha") < 0.9, "different surname does not match");
            eq(3, Text.levenshtein("kitten", "sitting"));
            eq("hazaribagh steel", Text.companyKey("Hazaribagh Steel Pvt. Ltd."));
        });

        System.out.println("\nSign-in and accounts");
        fresh();
        test("Sign in works and gives a session", () -> {
            String token = auth.login("ritu", "manager123");
            eq(manager.id, auth.userFor(token).id);
            auth.logout(token);
            eq(null, auth.userFor(token));
        });
        test("Same message for unknown user and wrong password", () -> {
            String a = null, b = null;
            try { auth.login("nobody", "x"); } catch (Problem p) { a = p.getMessage(); }
            try { auth.login("ritu", "wrong"); } catch (Problem p) { b = p.getMessage(); }
            eq(a, b);
        });
        test("5 wrong passwords lock the account for 5 minutes", () -> {
            for (int i = 0; i < 5; i++) problem(() -> auth.login("kavya", "bad"));
            eq(429, problem(() -> auth.login("kavya", "agent123")));
            Clock.advanceMillis(5 * 60_000 + 1000);
            check(auth.login("kavya", "agent123") != null, "works after 5 minutes");
        });
        test("Session ends after 12 idle hours", () -> {
            String t = auth.login("arjun", "agent123");
            Clock.advanceMillis(AuthService.IDLE_MS + 1000);
            eq(null, auth.userFor(t));
        });
        test("Admin creates staff; agents and managers cannot", () -> {
            User u = auth.createUser(admin, form("username", "neel", "name", "Neel Kumar", "role", "AGENT", "password", "welcome12"));
            eq(Role.AGENT, u.role);
            eq(403, problem(() -> auth.createUser(manager, form("username", "x1", "name", "X", "role", "AGENT", "password", "welcome12"))));
            eq(409, problem(() -> auth.createUser(admin, form("username", "NEEL", "name", "Y", "role", "AGENT", "password", "welcome12"))));
            eq(400, problem(() -> auth.createUser(admin, form("username", "ok_name", "name", "Y", "role", "AGENT", "password", "weak"))));
        });
        test("Cannot switch off someone who still owns customers", () -> {
            eq(400, problem(() -> auth.updateUser(admin, arjun.id, form("active", false))));
            eq(400, problem(() -> auth.updateUser(admin, admin.id, form("role", "AGENT"))));
        });
        test("Password reset signs the person out everywhere", () -> {
            String t = auth.login("sameer", "agent123");
            auth.updateUser(admin, store.userByName("sameer").id, form("password", "newpass99"));
            eq(null, auth.userFor(t));
            check(auth.login("sameer", "newpass99") != null, "new password works");
        });

        System.out.println("\nCustomers and access");
        fresh();
        test("Create: phone cleaned, e-mail lower case, agent becomes owner", () -> {
            Customer c = customers.create(arjun, form("name", "  Tanvi   Oraon ", "phone", "098350 44120", "email", "Tanvi@Example.IN",
                    "ownerId", manager.id, "tags", "Solar, VIP"));
            eq("Tanvi Oraon", c.name);
            eq("+919835044120", c.phone);
            eq("tanvi@example.in", c.email);
            eq(arjun.id, c.ownerId);
            eq(List.of("solar", "vip"), c.tags);
            eq(Stage.LEAD, c.stage);
        });
        test("Create: needs a name and a phone or e-mail; business needs a company", () -> {
            eq(400, problem(() -> customers.create(arjun, form("name", "No Contact"))));
            eq(400, problem(() -> customers.create(arjun, form("phone", "9835044121"))));
            eq(400, problem(() -> customers.create(arjun, form("name", "Biz", "phone", "9835044122", "type", "BUSINESS"))));
            eq(400, problem(() -> customers.create(arjun, form("name", "Bad", "phone", "123"))));
            eq(400, problem(() -> customers.create(arjun, form("name", "Bad tag", "phone", "9835044123", "tags", "<script>"))));
        });
        test("Same e-mail or phone cannot be saved twice (other agent: no details leak)", () -> {
            Customer mine = newCustomer(kavya, "Asha Devi", "9431100001", "asha@example.in");
            Problem p1 = null;
            try { newCustomer(kavya, "Asha D", "+91 94311 00001", null); } catch (Problem p) { p1 = p; }
            check(p1 != null && p1.status == 409 && p1.getMessage().contains(mine.id), "own duplicate names the customer");
            Problem p2 = null;
            try { newCustomer(arjun, "Someone", null, "ASHA@example.in"); } catch (Problem p) { p2 = p; }
            check(p2 != null && p2.status == 409 && !p2.getMessage().contains(mine.id) && !p2.getMessage().contains("Asha"), "no leak: " + (p2 == null ? "" : p2.getMessage()));
        });
        test("Agents see only their own customers (others are 'not found')", () -> {
            Customer theirs = store.customers.values().stream().filter(c -> c.ownerId.equals(kavya.id)).findFirst().orElseThrow();
            eq(404, problem(() -> customers.detail(arjun, theirs.id)));
            check(customers.detail(manager, theirs.id) != null, "manager sees it");
            Map<String, Object> list = customers.list(arjun, new HashMap<>());
            for (Object o : (List<?>) list.get("items")) eq(arjun.id, ((Map<?, ?>) o).get("ownerId"));
        });
        test("Only managers change the owner or bulk re-assign", () -> {
            Customer c = newCustomer(arjun, "Owner Test", "9431100002", null);
            eq(400, problem(() -> customers.update(arjun, c.id, form("name", c.name, "phone", c.phone, "ownerId", kavya.id))));
            eq(403, problem(() -> customers.bulkAssign(arjun, List.of(c.id), kavya.id)));
            eq(1, customers.bulkAssign(manager, List.of(c.id), kavya.id));
            eq(kavya.id, store.customers.get(c.id).ownerId);
        });
        test("Search by name, by phone digits, by tag; filter by stage", () -> {
            Customer c = customers.create(manager, form("name", "Ishaan Bara", "phone", "7004412345", "tags", "rooftop"));
            Map<String, String> q = new HashMap<>();
            q.put("q", "ishaan");
            eq(1, ((Number) customers.list(manager, q).get("total")).intValue());
            q.put("q", "4412345");
            eq(1, ((Number) customers.list(manager, q).get("total")).intValue());
            q.put("q", "rooftop");
            eq(1, ((Number) customers.list(manager, q).get("total")).intValue());
            q.clear();
            q.put("stage", "CHURNED");
            for (Object o : (List<?>) customers.list(manager, q).get("items")) eq(Stage.CHURNED, ((Map<?, ?>) o).get("stage"));
            check(c.id.startsWith("C-"), "id");
        });
        test("Export CSV: header + one row per visible customer", () -> {
            String csv = customers.exportCsv(arjun, new HashMap<>());
            long arjunCount = store.customers.values().stream().filter(c -> c.visible() && c.ownerId.equals(arjun.id)).count();
            eq(arjunCount + 1, (long) Csv.parse(csv).size());
            check(csv.startsWith("id,name,company"), "header");
        });

        System.out.println("\nConversations, consent, follow-ups, orders, tickets");
        fresh();
        test("Logging a call; a good first conversation turns a lead into a prospect", () -> {
            Customer c = newCustomer(arjun, "Lead Person", "9431100003", null);
            activity.logInteraction(arjun, c.id, form("type", "CALL", "direction", "OUT", "summary", "Interested in 3 kW", "outcome", "POSITIVE", "minutes", 6));
            eq(Stage.PROSPECT, c.stage);
        });
        test("Do-Not-Call: outgoing call is blocked, incoming is fine", () -> {
            Customer c = customers.create(arjun, form("name", "Quiet Person", "phone", "9431100004", "doNotCall", true));
            eq(409, problem(() -> activity.logInteraction(arjun, c.id, form("type", "CALL", "direction", "OUT", "summary", "x"))));
            activity.logInteraction(arjun, c.id, form("type", "CALL", "direction", "IN", "summary", "They called us"));
        });
        test("No e-mail consent: outgoing e-mail is blocked", () -> {
            Customer c = newCustomer(arjun, "Mail Person", null, "mail.person@example.in");
            eq(409, problem(() -> activity.logInteraction(arjun, c.id, form("type", "EMAIL", "direction", "OUT", "summary", "promo"))));
            customers.update(arjun, c.id, form("name", c.name, "email", c.email, "consentEmail", true));
            activity.logInteraction(arjun, c.id, form("type", "EMAIL", "direction", "OUT", "summary", "quote sent"));
        });
        test("Conversation input checks: type, summary, future time", () -> {
            Customer c = newCustomer(arjun, "Check Person", "9431100005", null);
            eq(400, problem(() -> activity.logInteraction(arjun, c.id, form("type", "FAX", "direction", "OUT", "summary", "x"))));
            eq(400, problem(() -> activity.logInteraction(arjun, c.id, form("type", "CALL", "direction", "OUT", "summary", " "))));
            eq(400, problem(() -> activity.logInteraction(arjun, c.id, form("type", "CALL", "direction", "OUT", "summary", "x", "at", "2030-01-01T10:00"))));
        });
        test("Follow-ups: no past dates; agents cannot assign to others; done/undo", () -> {
            Customer c = newCustomer(arjun, "Follow Person", "9431100006", null);
            eq(400, problem(() -> activity.addFollowUp(arjun, c.id, form("title", "x", "due", "2026-11-01"))));
            eq(403, problem(() -> activity.addFollowUp(arjun, c.id, form("title", "x", "due", "2026-11-09", "assigneeId", kavya.id))));
            FollowUp f = activity.addFollowUp(arjun, c.id, form("title", "Send quote", "due", "2026-11-07", "priority", "HIGH"));
            check(((List<?>) activity.followUpQueue(arjun, "mine").get("today")).stream().anyMatch(x -> f.id.equals(((Map<?, ?>) x).get("id"))), "in today's queue");
            activity.setFollowUpDone(arjun, f.id, true);
            check(store.followUps.get(f.id).done, "done");
            eq(404, problem(() -> activity.setFollowUpDone(kavya, f.id, false)));
        });
        test("Orders: first purchase makes a Customer, 4 in a year make Loyal; refunds do not count", () -> {
            Customer c = newCustomer(arjun, "Buyer Person", "9431100007", null);
            Order o = activity.addOrder(arjun, c.id, form("item", "Inverter", "amount", "12,000", "date", "2026-10-01"));
            eq(Stage.CUSTOMER, c.stage);
            eq(1_200_000L, o.amountPaise);
            for (int i = 0; i < 3; i++) activity.addOrder(arjun, c.id, form("item", "Cleaning", "amount", "900", "date", "2026-09-0" + (i + 1)));
            eq(Stage.LOYAL, c.stage);
            activity.setOrderStatus(arjun, o.id, "REFUNDED");
            eq(270_000L, insights.snapshot().lifetimeValue(c.id));
            eq(409, problem(() -> activity.setOrderStatus(arjun, o.id, "PAID")));
            eq(400, problem(() -> activity.addOrder(arjun, c.id, form("item", "x", "amount", "0"))));
            eq(400, problem(() -> activity.addOrder(arjun, c.id, form("item", "x", "amount", "100", "date", "2026-12-01"))));
        });
        test("Tickets: deadline by priority; late tickets are 'breached'", () -> {
            Customer c = newCustomer(arjun, "Ticket Person", "9431100008", null);
            Ticket t = activity.openTicket(arjun, c.id, form("subject", "Inverter error", "priority", "HIGH"));
            eq(t.createdAt + 24 * 3_600_000L, t.deadline());
            check(!t.breached(Clock.millis()), "not yet late");
            check(t.breached(Clock.millis() + 25 * 3_600_000L), "late after 25 h");
            activity.setTicketStatus(arjun, t.id, "RESOLVED");
            check(!t.breached(Clock.millis() + 99 * 3_600_000L), "solved tickets are never late");
        });

        System.out.println("\nInsights: RFM, health, next best action");
        fresh();
        test("RFM quintiles: 1..5, ties share a score", () -> {
            eq(1, Insights.class.getDeclaredMethod("quintile", int.class, int.class).invoke(null, 0, 10));
            eq(5, Insights.class.getDeclaredMethod("quintile", int.class, int.class).invoke(null, 9, 10));
            eq(3, Insights.class.getDeclaredMethod("quintile", int.class, int.class).invoke(null, 5, 10));
        });
        test("RFM segment table covers every box", () -> {
            var m = Insights.class.getDeclaredMethod("segment", int.class, int.class);
            m.setAccessible(true);
            eq("Champions", m.invoke(null, 5, 5));
            eq("Lost", m.invoke(null, 1, 1));
            eq("Can't lose them", m.invoke(null, 1, 5));
            eq("Promising", m.invoke(null, 5, 1));
            for (int r = 1; r <= 5; r++) for (int f = 1; f <= 5; f++) check(Insights.SEGMENTS.contains((String) m.invoke(null, r, f)), r + "," + f);
        });
        test("Every seeded buyer has an RFM score; non-buyers have none", () -> {
            Insights.Snapshot s = insights.snapshot();
            for (Customer c : store.customers.values()) {
                boolean buyer = s.ordersOf(c.id).stream().anyMatch(Order::counts);
                eq(buyer, s.rfm.containsKey(c.id));
            }
        });
        test("Health score: 5 parts add up, 0..100, with a reason each", () -> {
            Insights.Snapshot s = insights.snapshot();
            for (Customer c : store.customers.values()) {
                Insights.Health h = insights.health(c, s);
                check(h.score() >= 0 && h.score() <= 100, "range");
                eq(5, h.parts().size());
                eq(h.score(), h.parts().stream().mapToInt(Insights.Part::points).sum());
                eq(100, h.parts().stream().mapToInt(Insights.Part::max).sum());
                h.parts().forEach(p -> check(p.note() != null && !p.note().isBlank(), "reason"));
            }
        });
        test("A healthy new contact vs. a neglected customer", () -> {
            Customer c = newCustomer(arjun, "Fresh Person", "9431100009", null);
            activity.addOrder(arjun, c.id, form("item", "Solar", "amount", "150000", "date", "2026-11-06"));
            activity.logInteraction(arjun, c.id, form("type", "MEETING", "direction", "OUT", "summary", "Installed", "outcome", "POSITIVE"));
            Insights.Snapshot s = insights.snapshot();
            check(insights.health(c, s).score() >= Insights.HEALTHY, "healthy");
            boolean anyAtRisk = store.customers.values().stream().anyMatch(x -> Insights.atRisk(x, insights.health(x, s)));
            check(anyAtRisk, "seed data has slipping customers");
        });
        test("Next best action: overdue work first, never 'call' someone on Do-Not-Call", () -> {
            Customer c = customers.create(arjun, form("name", "Dnd Buyer", "phone", "9431100010", "email", "dnd@example.in",
                    "consentEmail", true, "doNotCall", true));
            activity.addOrder(arjun, c.id, form("item", "Solar", "amount", "150000", "date", "2026-06-01"));
            Insights.Snapshot s = insights.snapshot();
            List<Map<String, Object>> acts = insights.nextActions(c, s, insights.health(c, s));
            String all = acts.toString();
            check(all.contains("Check in"), "suggests a check-in: " + all);
            check(all.contains("e-mail") && !all.contains("Check in with a call"), "uses e-mail: " + all);
        });
        test("Dashboard numbers agree with the records", () -> {
            Map<String, Object> d = dashboard.build(manager);
            long visible = store.customers.values().stream().filter(c -> c.visible() && !c.erased).count();
            eq(visible, ((Number) d.get("customers")).longValue());
            long stagesSum = ((Map<?, ?>) d.get("stages")).values().stream().mapToLong(v -> ((Number) v).longValue()).sum();
            eq(visible, stagesSum);
            Map<String, Object> mine = dashboard.build(arjun);
            check(((Number) mine.get("customers")).longValue() < visible, "agent sees fewer");
            eq(403, problem(() -> dashboard.audit(arjun, null, 10)));
        });

        System.out.println("\nDuplicates and merging");
        fresh();
        test("Seed duplicates are found: same e-mail, same phone, typo in the name", () -> {
            List<Map<String, Object>> pairs = dupes.findAll(manager);
            check(pairs.size() >= 3, "found " + pairs.size());
            eq(100, ((Number) pairs.get(0).get("score")).intValue());
            check(pairs.stream().anyMatch(p -> ((List<?>) p.get("reasons")).contains("Same phone number")), "phone pair");
        });
        test("Live check while typing finds near-matches", () -> {
            Customer c = store.customers.values().iterator().next();
            List<Map<String, Object>> m = dupes.check(manager, form("name", c.name.toUpperCase(), "city", c.city));
            check(m.stream().anyMatch(x -> c.id.equals(x.get("id"))), "found " + c.id);
        });
        test("Merge moves every record, keeps safer consent, hides the other", () -> {
            Customer a = customers.create(manager, form("name", "Merge Keep", "phone", "9431100011", "email", "keep@example.in", "consentEmail", true));
            Customer b = customers.create(manager, form("name", "Merge Other", "phone", "9431100012", "doNotCall", true, "tags", "vip"));
            activity.logInteraction(manager, b.id, form("type", "CALL", "direction", "IN", "summary", "hi"));
            activity.addOrder(manager, b.id, form("item", "Solar", "amount", "100000", "date", "2026-10-10"));
            Customer kept = dupes.merge(manager, a.id, b.id);
            check(kept.doNotCall, "DND carried over");
            check(kept.tags.contains("vip"), "tags combined");
            eq(Stage.CUSTOMER, kept.stage);
            eq(b.id.equals(store.customers.get(b.id).mergedInto) ? null : a.id, store.customers.get(b.id).mergedInto);
            check(store.orders.values().stream().noneMatch(o -> b.id.equals(o.customerId)), "orders moved");
            eq(404, problem(() -> customers.detail(manager, b.id)));
            eq(403, problem(() -> dupes.merge(arjun, a.id, a.id)));
        });

        System.out.println("\nCSV import");
        fresh();
        test("Preview checks every row and saves nothing", () -> {
            int before = store.customers.size();
            Map<String, Object> p = importer.preview(manager, ImportService.sampleCsv());
            eq(4, ((Number) p.get("total")).intValue());
            eq(1, ((Number) p.get("errors")).intValue());
            eq(before, store.customers.size());
        });
        test("Import adds good rows, skips bad rows and duplicates in the file", () -> {
            String csv = "Full Name,Mobile,E-mail,Stage,Owner\nAa One,9431100101,,Lead,kavya\nBb Two,94311 00101,,Lead,\nCc Three,,cc@example.in,Prospect,nobody\n";
            Map<String, Object> r = importer.commit(manager, csv, true);
            eq(2, ((Number) r.get("added")).intValue());
            eq(1, ((Number) r.get("skipped")).intValue());
            Customer one = store.customers.values().stream().filter(c -> "Aa One".equals(c.name)).findFirst().orElseThrow();
            eq(kavya.id, one.ownerId);
        });
        test("Import is for managers; a file without a name column is refused", () -> {
            eq(403, problem(() -> importer.preview(arjun, "name\nX\n")));
            eq(400, problem(() -> importer.preview(manager, "phone\n9431100102\n")));
        });

        System.out.println("\nPrivacy (DPDP Act 2023)");
        fresh();
        test("Download of personal data has all records", () -> {
            Customer c = newCustomer(arjun, "Data Person", "9431100013", null);
            activity.logInteraction(arjun, c.id, form("type", "NOTE", "summary", "likes evening calls"));
            Map<String, Object> d = customers.privacyExport(arjun, c.id);
            eq(1, ((List<?>) d.get("conversations")).size());
            eq("Data Person", ((Map<?, ?>) d.get("customer")).get("name"));
        });
        test("Erase: admin only, needs the ID typed, keeps order amounts", () -> {
            Customer c = newCustomer(arjun, "Erase Person", "9431100014", "erase@example.in");
            activity.addOrder(arjun, c.id, form("item", "Solar", "amount", "100000", "date", "2026-10-10"));
            activity.logInteraction(arjun, c.id, form("type", "CALL", "direction", "IN", "summary", "private text"));
            eq(403, problem(() -> customers.erase(manager, c.id, c.id)));
            eq(400, problem(() -> customers.erase(admin, c.id, "C-0")));
            customers.erase(admin, c.id, c.id);
            check(c.erased && c.phone == null && c.email == null && c.name.startsWith("Erased"), "personal data gone");
            check(store.interactions.values().stream().filter(i -> c.id.equals(i.customerId)).allMatch(i -> "[erased]".equals(i.summary)), "texts gone");
            eq(10_000_000L, insights.snapshot().lifetimeValue(c.id));
            eq(409, problem(() -> activity.logInteraction(admin, c.id, form("type", "NOTE", "summary", "x"))));
        });

        System.out.println("\nSaving and loading");
        test("Everything saved to JSON files loads back the same", () -> {
            Path dir = Files.createTempDirectory("sambandh-test");
            DataStore a = new DataStore(dir);
            check(a.load(), "first start");
            SeedData.load(a);
            a.saveAll();
            DataStore b = new DataStore(dir);
            check(!b.load(), "not first start");
            eq(a.customers.size(), b.customers.size());
            eq(a.interactions.size(), b.interactions.size());
            eq(a.orders.size(), b.orders.size());
            eq(Json.write(a.customers.values()), Json.write(b.customers.values()));
            eq(a.nextId("C", 1001), b.nextId("C", 1001));
        });

        System.out.println("\nWeb server (real HTTP)");
        fresh();
        Path front = Files.createTempDirectory("sambandh-front");
        Files.writeString(front.resolve("index.html"), "<!doctype html><title>t</title>");
        Files.writeString(front.resolve("app.js"), "console.log(1)");
        ApiServer server = new ApiServer(front, store);
        server.start(0);
        String base = "http://127.0.0.1:" + server.port();
        HttpClient http = HttpClient.newHttpClient();
        try {
            test("Home page has a strict Content-Security-Policy and no-sniff", () -> {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/")).build(), HttpResponse.BodyHandlers.ofString());
                eq(200, r.statusCode());
                String csp = r.headers().firstValue("Content-Security-Policy").orElse("");
                check(csp.contains("script-src 'self'") && !csp.contains("unsafe-inline"), csp);
                eq("nosniff", r.headers().firstValue("X-Content-Type-Options").orElse(""));
            });
            test("Path tricks do not escape the frontend folder", () -> {
                for (String p : new String[]{"/static/../backend/pom.xml", "/static/%2e%2e/secret", "/static/.hidden", "/etc/passwd"}) {
                    HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + p.replace("%2e", "%2e"))).build(), HttpResponse.BodyHandlers.ofString());
                    eq(404, r.statusCode());
                }
            });
            test("API needs sign-in", () -> {
                eq(401, http.send(HttpRequest.newBuilder(URI.create(base + "/api/customers")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            });
            test("CSRF: changes without the app header or from another site are blocked", () -> {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/api/login")).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"ritu\",\"password\":\"manager123\"}")).build(), HttpResponse.BodyHandlers.ofString());
                eq(403, r.statusCode());
                HttpResponse<String> r2 = http.send(HttpRequest.newBuilder(URI.create(base + "/api/login")).header("Content-Type", "application/json")
                        .header("X-Sambandh", "1").header("Origin", "http://evil.example")
                        .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
                eq(403, r2.statusCode());
            });
            test("Sign in over HTTP: HttpOnly, SameSite=Strict cookie; then roles apply", () -> {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/api/login")).header("Content-Type", "application/json")
                        .header("X-Sambandh", "1").POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"arjun\",\"password\":\"agent123\"}")).build(),
                        HttpResponse.BodyHandlers.ofString());
                eq(200, r.statusCode());
                String cookie = r.headers().firstValue("Set-Cookie").orElse("");
                check(cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict"), cookie);
                String c = cookie.split(";")[0];
                eq(200, http.send(HttpRequest.newBuilder(URI.create(base + "/api/dashboard")).header("Cookie", c).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                eq(403, http.send(HttpRequest.newBuilder(URI.create(base + "/api/duplicates")).header("Cookie", c).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                HttpResponse<String> bad = http.send(HttpRequest.newBuilder(URI.create(base + "/api/customers")).header("Cookie", c)
                        .header("Content-Type", "application/json").header("X-Sambandh", "1")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"\"}")).build(), HttpResponse.BodyHandlers.ofString());
                eq(400, bad.statusCode());
                check(bad.body().contains("Name is needed"), bad.body());
                HttpResponse<String> csv = http.send(HttpRequest.newBuilder(URI.create(base + "/api/customers/export.csv")).header("Cookie", c).build(), HttpResponse.BodyHandlers.ofString());
                eq(200, csv.statusCode());
                check(csv.headers().firstValue("Content-Disposition").orElse("").contains("attachment"), "download");
            });
            test("Sign-in rate limit: 10 a minute per computer", () -> {
                int last = 0;
                for (int i = 0; i < 12; i++) {
                    last = http.send(HttpRequest.newBuilder(URI.create(base + "/api/login")).header("Content-Type", "application/json")
                            .header("X-Sambandh", "1").POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"x\",\"password\":\"y\"}")).build(),
                            HttpResponse.BodyHandlers.ofString()).statusCode();
                }
                eq(429, last);
            });
        } finally {
            server.stop();
        }

        System.out.println("\n" + passed + " passed, " + failed + " failed.");
        if (failed > 0) {
            failures.forEach(f -> System.out.println("  - " + f));
            System.exit(1);
        }
    }
}
