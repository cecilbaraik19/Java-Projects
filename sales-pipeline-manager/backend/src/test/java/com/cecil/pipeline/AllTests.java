package com.cecil.pipeline;

import com.cecil.pipeline.insights.DealScorer;
import com.cecil.pipeline.model.Activity;
import com.cecil.pipeline.model.Contact;
import com.cecil.pipeline.model.Deal;
import com.cecil.pipeline.model.Enums.ActivityType;
import com.cecil.pipeline.model.Enums.Stage;
import com.cecil.pipeline.model.User;
import com.cecil.pipeline.service.AccountService;
import com.cecil.pipeline.service.ActivityService;
import com.cecil.pipeline.service.AnalyticsService;
import com.cecil.pipeline.service.AuthService;
import com.cecil.pipeline.service.DealService;
import com.cecil.pipeline.service.ServiceException;
import com.cecil.pipeline.store.DataStore;
import com.cecil.pipeline.util.Csv;
import com.cecil.pipeline.util.Json;
import com.cecil.pipeline.util.Passwords;
import com.cecil.pipeline.web.ApiServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * All backend tests in one runnable class (no JUnit download needed).
 * Run with run-tests.bat / run-tests.sh, or "mvn test", or right-click > Run in IntelliJ.
 */
public final class AllTests {

    private static int passed, failed;
    private static final List<String> failures = new ArrayList<>();

    interface Body { void run() throws Exception; }

    public static void main(String[] args) {
        System.out.println("Sales Pipeline Manager - backend tests\n");

        // ---- utilities
        test("JSON round trip keeps types", () -> {
            Map<String, Object> m = Json.obj("s", "a\"b\n<c>", "n", 42L, "d", 2.5, "b", true, "z", null, "l", List.of(1L, "x"));
            Map<String, Object> back = Json.parseObject(Json.write(m));
            eq("a\"b\n<c>", back.get("s"));
            eq(42L, back.get("n"));
            eq(2.5, back.get("d"));
            eq(true, back.get("b"));
            check(back.containsKey("z") && back.get("z") == null, "null kept");
            eq(List.of(1L, "x"), back.get("l"));
        });
        test("JSON escapes < > & so it is safe in HTML", () -> {
            String out = Json.write("<script>&");
            check(!out.contains("<") && !out.contains(">") && !out.contains("&"), out);
        });
        test("JSON rejects bad input", () -> {
            throwsBad(() -> Json.parse("{\"a\":}"));
            throwsBad(() -> Json.parse("[1,2"));
            throwsBad(() -> Json.parse("{} extra"));
            throwsBad(() -> Json.parse("\"\\uZZZZ\""));
            StringBuilder deep = new StringBuilder();
            for (int i = 0; i < 200; i++) deep.append('[');
            throwsBad(() -> Json.parse(deep.toString()));
        });
        test("Passwords hash and verify", () -> {
            String h = Passwords.hash("secret123");
            check(h.startsWith("pbkdf2$"), "format");
            check(Passwords.verify("secret123", h), "right password");
            check(!Passwords.verify("secret124", h), "wrong password");
            check(!Passwords.verify("x", "garbage"), "garbage hash");
            check(!h.equals(Passwords.hash("secret123")), "salted");
        });
        test("CSV quotes values and blocks formula injection", () -> {
            eq("\"a,b\"", Csv.cell("a,b"));
            eq("\"say \"\"hi\"\"\"", Csv.cell("say \"hi\""));
            eq("'=SUM(A1)", Csv.cell("=SUM(A1)"));
            eq("-5", Csv.cell(-5L));
            eq("", Csv.cell(null));
        });

        // ---- auth
        test("Login works and wrong passwords lock the account", () -> {
            Env e = new Env();
            String t = e.auth.login("neha", "manager123");
            eq("neha", e.auth.userFor(t).username);
            status(401, () -> e.auth.login("neha", "nope"));
            for (int i = 0; i < 5; i++) status(401, () -> e.auth.login("arjun", "bad"));
            status(429, () -> e.auth.login("arjun", "sales123")); // locked even with the right password
            e.auth.logout(t);
            check(e.auth.userFor(t) == null, "logged out");
        });
        test("Only admins manage users, and the last admin cannot be removed", () -> {
            Env e = new Env();
            status(403, () -> e.auth.createUser(e.neha, Json.obj("username", "new.rep", "password", "password1")));
            User u = e.auth.createUser(e.admin, Json.obj("username", "new.rep", "password", "password1", "displayName", "New Rep", "monthlyTarget", 500000L));
            eq(500000L, u.monthlyTarget);
            status(409, () -> e.auth.createUser(e.admin, Json.obj("username", "new.rep", "password", "password1")));
            status(400, () -> e.auth.createUser(e.admin, Json.obj("username", "x", "password", "password1")));
            status(400, () -> e.auth.createUser(e.admin, Json.obj("username", "short.pw", "password", "123")));
            status(400, () -> e.auth.updateUser(e.admin, "admin", Json.obj("role", "SALES_REP")));
            check(e.admin.isAdmin(), "admin kept");
            String t = e.auth.login("new.rep", "password1");
            e.auth.updateUser(e.admin, "new.rep", Json.obj("active", false));
            check(e.auth.userFor(t) == null, "turned-off user is signed out");
        });

        // ---- deals
        test("Create a deal with validation", () -> {
            Env e = new Env();
            String acc = e.store.accounts.get(0).id;
            status(400, () -> e.deals.create(e.arjun, Json.obj("title", "", "accountId", acc, "value", 100L)));
            status(400, () -> e.deals.create(e.arjun, Json.obj("title", "X", "accountId", "A-999", "value", 100L)));
            status(400, () -> e.deals.create(e.arjun, Json.obj("title", "X", "accountId", acc, "value", -1L)));
            status(400, () -> e.deals.create(e.arjun, Json.obj("title", "X", "accountId", acc, "value", 10L, "probability", 120L)));
            status(400, () -> e.deals.create(e.arjun, Json.obj("title", "X", "accountId", acc, "value", 10L, "expectedClose", "31/12/2026")));
            status(400, () -> e.deals.create(e.arjun, Json.obj("title", "X", "accountId", acc, "value", 10L, "stage", "LOST")));
            String otherAccContact = e.store.contacts.stream().filter(c -> !c.accountId.equals(acc)).findFirst().get().id;
            status(400, () -> e.deals.create(e.arjun, Json.obj("title", "X", "accountId", acc, "value", 10L, "contactId", otherAccContact)));
            Deal d = e.deals.create(e.arjun, Json.obj("title", "New CRM", "accountId", acc, "value", 400000L, "expectedClose", "2030-01-15"));
            eq(Stage.LEAD, d.stage);
            eq("arjun", d.owner);
            eq(10, d.effectiveProbability());
            eq(40000L, d.weightedValue());
            eq(1, d.history.size());
        });
        test("Moving stages records history, resets probability and closes deals", () -> {
            Env e = new Env();
            Deal d = e.newDeal(e.arjun, 1_000_000);
            e.deals.update(e.arjun, d.id, Json.obj("probability", 40L));
            eq(400000L, d.weightedValue());
            e.deals.moveStage(e.arjun, d.id, "PROPOSAL", null);
            eq(Stage.PROPOSAL, d.stage);
            check(d.probability == null, "probability reset to stage default");
            eq(500000L, d.weightedValue());
            status(400, () -> e.deals.moveStage(e.arjun, d.id, "LOST", " "));
            e.deals.moveStage(e.arjun, d.id, "LOST", "Price too high");
            eq("Price too high", d.lostReason);
            check(d.closedAt != null, "closed");
            eq(0L, d.weightedValue());
            e.deals.moveStage(e.arjun, d.id, "NEGOTIATION", null); // reopen
            check(d.closedAt == null && d.lostReason == null, "reopened");
            e.deals.moveStage(e.arjun, d.id, "WON", null);
            eq(100, d.effectiveProbability());
            eq(5, d.history.size());
            status(400, () -> e.deals.moveStage(e.arjun, d.id, "FLYING", null));
        });
        test("Sales reps can only change their own deals", () -> {
            Env e = new Env();
            Deal d = e.newDeal(e.priya, 50_000);
            status(403, () -> e.deals.update(e.arjun, d.id, Json.obj("value", 1L)));
            status(403, () -> e.deals.moveStage(e.arjun, d.id, "WON", null));
            status(403, () -> e.deals.delete(e.arjun, d.id));
            status(403, () -> e.deals.update(e.priya, d.id, Json.obj("owner", "arjun"))); // reps cannot hand deals over
            e.deals.update(e.neha, d.id, Json.obj("owner", "arjun"));               // managers can
            eq("arjun", d.owner);
            check(!DealService.canEdit(e.priya, d) && DealService.canEdit(e.arjun, d), "new owner can edit");
        });
        test("Deal list filters and sorting", () -> {
            Env e = new Env();
            DealService.Filter f = new DealService.Filter();
            f.status = "open";
            f.sort = "value";
            List<Map<String, Object>> open = e.deals.list(e.neha, f);
            check(!open.isEmpty(), "has open deals");
            for (Map<String, Object> m : open) check(!m.get("stage").equals(Stage.WON) && !m.get("stage").equals(Stage.LOST), "only open");
            for (int i = 1; i < open.size(); i++) check((Long) open.get(i - 1).get("value") >= (Long) open.get(i).get("value"), "sorted by value");
            f.owner = "me";
            for (Map<String, Object> m : e.deals.list(e.arjun, f)) eq("arjun", m.get("owner"));
            DealService.Filter q = new DealService.Filter();
            q.q = e.store.accounts.get(0).name.substring(0, 5);
            check(!e.deals.list(e.neha, q).isEmpty(), "search by company");
            String csv = e.deals.exportCsv(e.neha, new DealService.Filter());
            check(csv.contains("Weighted (INR)") && csv.split("\r\n").length == e.store.deals.size() + 1, "csv rows");
        });
        test("Notes are added and only the author or a manager can delete them", () -> {
            Env e = new Env();
            Deal d = e.newDeal(e.arjun, 10_000);
            Deal.Note n = e.deals.addNote(e.arjun, d.id, "They want a demo next week");
            status(400, () -> e.deals.addNote(e.arjun, d.id, "   "));
            status(403, () -> e.deals.deleteNote(e.priya, d.id, n.id));
            e.deals.deleteNote(e.neha, d.id, n.id);
            eq(0, d.notes.size());
        });

        // ---- activities
        test("Finishing an activity updates the deal's last activity", () -> {
            Env e = new Env();
            Deal d = e.newDeal(e.arjun, 10_000);
            d.lastActivityAt = "2020-01-01T00:00:00Z";
            Activity a = e.acts.create(e.arjun, Json.obj("dealId", d.id, "subject", "Call CFO", "type", "CALL", "dueDate", LocalDate.now().minusDays(1).toString()));
            check(a.isOverdue(LocalDate.now()), "overdue");
            eq(1, e.acts.list(e.arjun, "me", d.id, "overdue").size());
            e.acts.complete(e.arjun, a.id, true, "Agreed on budget");
            check(a.done && d.lastActivityAt.equals(a.doneAt), "deal touched");
            eq("Agreed on budget", a.outcome);
            status(403, () -> e.acts.delete(e.priya, a.id));
            status(400, () -> e.acts.create(e.arjun, Json.obj("subject", "")));
            status(400, () -> e.acts.create(e.arjun, Json.obj("subject", "x", "dealId", "D-9")));
        });

        // ---- accounts
        test("Accounts and contacts", () -> {
            Env e = new Env();
            var a = e.accounts.createAccount(e.arjun, Json.obj("name", "Test Co", "industry", "Retail", "website", "test.example"));
            status(409, () -> e.accounts.createAccount(e.arjun, Json.obj("name", "test co")));
            status(400, () -> e.accounts.createAccount(e.arjun, Json.obj("name", "Bad Web", "website", "not a site")));
            Contact c = e.accounts.createContact(e.arjun, Json.obj("accountId", a.id, "name", "Ravi", "email", "ravi@test.example", "decisionMaker", true));
            status(400, () -> e.accounts.createContact(e.arjun, Json.obj("accountId", a.id, "name", "X", "email", "bad")));
            Deal d = e.deals.create(e.arjun, Json.obj("title", "T", "accountId", a.id, "contactId", c.id, "value", 1000L));
            status(403, () -> e.accounts.deleteAccount(e.arjun, a.id));
            status(409, () -> e.accounts.deleteAccount(e.neha, a.id));
            e.accounts.deleteContact(e.arjun, c.id);
            check(d.contactId == null, "deal unlinked from removed contact");
            e.deals.delete(e.arjun, d.id);
            e.accounts.deleteAccount(e.neha, a.id);
            check(e.store.account(a.id) == null, "deleted");
        });

        // ---- insights
        test("Health score stays between 0 and 100 and explains itself", () -> {
            Env e = new Env();
            LocalDate today = LocalDate.now();
            for (Deal d : e.store.deals) {
                DealScorer.Result r = DealScorer.score(d, List.of(), null, today);
                check(r.score() >= 0 && r.score() <= 100, "range " + r.score());
                check(!r.factors().isEmpty(), "has reasons");
            }
            Deal won = new Deal(); won.stage = Stage.WON; won.createdAt = Instant.now().toString();
            eq(100, DealScorer.score(won, List.of(), null, today).score());
        });
        test("An active, late-stage deal scores higher than a neglected lead", () -> {
            LocalDate today = LocalDate.now();
            String now = Instant.now().toString();
            Contact boss = new Contact(); boss.name = "Boss"; boss.decisionMaker = true;

            Deal good = new Deal(); good.stage = Stage.NEGOTIATION; good.createdAt = now; good.lastActivityAt = now;
            good.expectedClose = today.plusDays(10);
            Activity meeting = new Activity(); meeting.type = ActivityType.MEETING; meeting.done = true; meeting.doneAt = now;
            Activity next = new Activity(); next.dueDate = today.plusDays(2);
            int g = DealScorer.score(good, List.of(meeting, next), boss, today).score();

            Deal bad = new Deal(); bad.stage = Stage.LEAD; bad.createdAt = "2020-01-01T00:00:00Z"; bad.lastActivityAt = bad.createdAt;
            bad.expectedClose = today.minusDays(20);
            int b = DealScorer.score(bad, List.of(), null, today).score();
            check(g >= DealScorer.HOT, "good deal is hot: " + g);
            eq(0, b);
            eq("HOT", DealScorer.label(g));
        });
        test("Funnel only counts stages a deal really reached", () -> {
            Deal d = new Deal();
            d.stage = Stage.LOST;
            Deal.StageChange a = new Deal.StageChange(); a.to = Stage.LEAD;
            Deal.StageChange b = new Deal.StageChange(); b.from = Stage.LEAD; b.to = Stage.QUALIFIED;
            Deal.StageChange c = new Deal.StageChange(); c.from = Stage.QUALIFIED; c.to = Stage.LOST;
            d.history.addAll(List.of(a, b, c));
            check(d.reached(Stage.LEAD) && d.reached(Stage.QUALIFIED), "reached early stages");
            check(!d.reached(Stage.PROPOSAL) && !d.reached(Stage.WON) && d.reached(Stage.LOST), "not later ones");
            Deal w = new Deal(); w.stage = Stage.WON;
            check(w.reached(Stage.NEGOTIATION), "won passed through all stages");
        });
        test("Dashboard and forecast numbers add up", () -> {
            Env e = new Env();
            Map<String, Object> dash = e.analytics.dashboard(e.neha, null);
            @SuppressWarnings("unchecked") Map<String, Object> k = (Map<String, Object>) dash.get("kpis");
            long open = 0, weighted = 0;
            for (Deal d : e.store.deals) if (d.isOpen()) { open += d.value; weighted += d.weightedValue(); }
            eq(open, k.get("openValue"));
            eq(weighted, k.get("weightedValue"));

            // forecast: every open deal with a close date within 6 months is counted once
            Map<String, Object> rep = e.analytics.reports(null, 6);
            long fPipe = 0;
            for (Object o : (List<?>) rep.get("forecast")) fPipe += (Long) ((Map<?, ?>) o).get("pipeline");
            LocalDate limit = LocalDate.now().withDayOfMonth(1).plusMonths(6);
            long expected = 0;
            for (Deal d : e.store.deals) if (d.isOpen() && d.expectedClose != null && d.expectedClose.isBefore(limit)) expected += d.value;
            eq(expected, fPipe);

            List<?> funnel = (List<?>) rep.get("funnel");
            eq((long) e.store.deals.size(), ((Map<?, ?>) funnel.get(0)).get("count"));
            long prev = Long.MAX_VALUE;
            for (Object o : funnel) { long n = (Long) ((Map<?, ?>) o).get("count"); check(n <= prev, "funnel narrows"); prev = n; }
        });
        test("Win rate is won / (won + lost)", () -> {
            Env e = new Env(false);
            String acc = e.accounts.createAccount(e.neha, Json.obj("name", "Solo")).id;
            for (int i = 0; i < 3; i++) e.deals.moveStage(e.neha, e.deals.create(e.neha, Json.obj("title", "W" + i, "accountId", acc, "value", 100L)).id, "WON", null);
            e.deals.moveStage(e.neha, e.deals.create(e.neha, Json.obj("title", "L", "accountId", acc, "value", 100L)).id, "LOST", "No budget");
            e.deals.create(e.neha, Json.obj("title", "Open", "accountId", acc, "value", 100L));
            @SuppressWarnings("unchecked") Map<String, Object> k = (Map<String, Object>) e.analytics.dashboard(e.neha, null).get("kpis");
            eq(75.0, k.get("winRate"));
            eq(300L, k.get("wonThisMonth"));
            eq(1L, k.get("openCount"));
        });

        // ---- storage
        test("Data is saved to disk and loaded back", () -> {
            Path dir = Files.createTempDirectory("spm-test");
            try {
                DataStore s1 = new DataStore(dir);
                s1.load(true);
                int n = s1.deals.size();
                User arjun = s1.user("arjun");
                Deal d = new DealService(s1).create(arjun, Json.obj("title", "Persist me", "accountId", s1.accounts.get(0).id, "value", 777L));
                DataStore s2 = new DataStore(dir);
                s2.load(true);
                eq(n + 1, s2.deals.size());
                eq(777L, s2.deal(d.id).value);
                String next = s2.nextId("D");
                check(s2.deal(next) == null, "ids continue after reload");
                check(Passwords.verify("sales123", s2.user("arjun").passwordHash), "password hash kept");
            } finally {
                try (var w = Files.walk(dir)) { w.sorted((a, b) -> b.compareTo(a)).forEach(p -> p.toFile().delete()); }
            }
        });

        // ---- HTTP
        test("HTTP API end to end", () -> {
            DataStore store = new DataStore(null);
            store.load(true);
            Path fe = Files.createTempDirectory("spm-fe");
            Files.writeString(fe.resolve("index.html"), "<h1>ok</h1>");
            ApiServer srv = new ApiServer(store, fe);
            srv.start("127.0.0.1", 0);
            try {
                String base = "http://127.0.0.1:" + srv.port();
                HttpClient http = HttpClient.newHttpClient();
                eq(200, send(http, "GET", base + "/", null, null).statusCode());
                eq(404, send(http, "GET", base + "/static/../../etc/passwd", null, null).statusCode());
                eq(401, send(http, "GET", base + "/api/deals", null, null).statusCode());
                HttpResponse<String> login = send(http, "POST", base + "/api/auth/login", null, "{\"username\":\"arjun\",\"password\":\"sales123\"}");
                eq(200, login.statusCode());
                check(login.headers().firstValue("Content-Security-Policy").isPresent(), "security headers");
                String token = (String) Json.parseObject(login.body()).get("token");
                eq(200, send(http, "GET", base + "/api/dashboard?owner=me", token, null).statusCode());
                eq(200, send(http, "GET", base + "/api/reports", token, null).statusCode());
                String acc = store.accounts.get(0).id;
                HttpResponse<String> created = send(http, "POST", base + "/api/deals", token,
                    "{\"title\":\"Via API\",\"accountId\":\"" + acc + "\",\"value\":250000}");
                eq(201, created.statusCode());
                String id = (String) Json.parseObject(created.body()).get("id");
                HttpResponse<String> moved = send(http, "POST", base + "/api/deals/" + id + "/stage", token, "{\"stage\":\"PROPOSAL\"}");
                eq("PROPOSAL", Json.parseObject(moved.body()).get("stage"));
                eq(400, send(http, "POST", base + "/api/deals", token, "{bad json").statusCode());
                eq(403, send(http, "POST", base + "/api/users", token, "{\"username\":\"zz\"}").statusCode());
                HttpResponse<String> csv = send(http, "GET", base + "/api/deals/export.csv", token, null);
                check(csv.headers().firstValue("Content-Type").orElse("").startsWith("text/csv"), "csv type");
                // cross-site POST is blocked
                HttpRequest xs = HttpRequest.newBuilder(URI.create(base + "/api/auth/logout"))
                    .header("Authorization", "Bearer " + token).header("Origin", "http://evil.example")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
                eq(403, http.send(xs, HttpResponse.BodyHandlers.ofString()).statusCode());
                eq(200, send(http, "POST", base + "/api/auth/logout", token, "{}").statusCode());
                eq(401, send(http, "GET", base + "/api/deals", token, null).statusCode());
            } finally {
                srv.stop();
            }
        });

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) {
            failures.forEach(f -> System.out.println("  FAILED: " + f));
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ test helpers

    /** A fresh in-memory store with demo data and all services. */
    static final class Env {
        final DataStore store = new DataStore(null);
        final AuthService auth;
        final AccountService accounts;
        final DealService deals;
        final ActivityService acts;
        final AnalyticsService analytics;
        final User admin, neha, arjun, priya;

        Env() { this(true); }

        Env(boolean demoDeals) {
            store.load(true);
            if (!demoDeals) { store.deals.clear(); store.activities.clear(); }
            auth = new AuthService(store);
            accounts = new AccountService(store);
            deals = new DealService(store);
            acts = new ActivityService(store);
            analytics = new AnalyticsService(store, deals);
            admin = store.user("admin");
            neha = store.user("neha");
            arjun = store.user("arjun");
            priya = store.user("priya");
        }

        Deal newDeal(User u, long value) {
            return deals.create(u, Json.obj("title", "Test deal", "accountId", store.accounts.get(0).id, "value", value));
        }
    }

    static HttpResponse<String> send(HttpClient http, String method, String url, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static void test(String name, Body body) {
        try {
            body.run();
            passed++;
            System.out.println("  PASS  " + name);
        } catch (Throwable t) {
            failed++;
            failures.add(name + " -> " + t);
            System.out.println("  FAIL  " + name + "\n        " + t);
        }
    }

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("Check failed: " + what);
    }

    static void eq(Object expected, Object actual) {
        boolean same = expected == null ? actual == null
            : expected instanceof Number en && actual instanceof Number an && !(expected instanceof Double) && !(actual instanceof Double)
                ? en.longValue() == an.longValue()
                : expected.equals(actual);
        if (!same) throw new AssertionError("Expected " + expected + " but got " + actual);
    }

    static void status(int code, Body body) throws Exception {
        try {
            body.run();
        } catch (ServiceException e) {
            if (e.status != code) throw new AssertionError("Expected status " + code + " but got " + e.status + " (" + e.getMessage() + ")");
            return;
        }
        throw new AssertionError("Expected an error with status " + code);
    }

    static void throwsBad(Body body) throws Exception {
        try {
            body.run();
        } catch (IllegalArgumentException e) {
            return;
        }
        throw new AssertionError("Expected IllegalArgumentException");
    }
}
