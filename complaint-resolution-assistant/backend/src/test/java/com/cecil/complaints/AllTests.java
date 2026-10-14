package com.cecil.complaints;

import com.cecil.complaints.ai.AiEngine;
import com.cecil.complaints.ai.ComplaintClassifier;
import com.cecil.complaints.ai.LlmClient;
import com.cecil.complaints.ai.PriorityEngine;
import com.cecil.complaints.ai.SentimentAnalyzer;
import com.cecil.complaints.ai.TextProcessor;
import com.cecil.complaints.model.Complaint;
import com.cecil.complaints.model.Enums.Category;
import com.cecil.complaints.model.Enums.Channel;
import com.cecil.complaints.model.Enums.CustomerTier;
import com.cecil.complaints.model.Enums.Priority;
import com.cecil.complaints.model.Enums.Sentiment;
import com.cecil.complaints.model.Enums.Status;
import com.cecil.complaints.model.User;
import com.cecil.complaints.service.AnalyticsService;
import com.cecil.complaints.service.AuthService;
import com.cecil.complaints.service.ComplaintService;
import com.cecil.complaints.service.KnowledgeBaseService;
import com.cecil.complaints.service.ServiceException;
import com.cecil.complaints.store.DataStore;
import com.cecil.complaints.store.SeedData;
import com.cecil.complaints.util.Json;
import com.cecil.complaints.web.ApiServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test suite with a tiny built-in runner, so tests run with plain `java` (no JUnit download needed).
 * Run: run-tests.bat (Windows) or ./run-tests.sh
 */
public final class AllTests {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    interface Test {
        void run() throws Exception;
    }

    public static void main(String[] args) {
        System.out.println("Running tests...\n");

        // ---- utilities
        test("JSON round trip keeps types and escapes text", () -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", "Ravi \"R\" <b>\n");
            m.put("n", 42L);
            m.put("d", 3.5);
            m.put("ok", true);
            m.put("list", List.of(1L, "two"));
            m.put("none", null);
            Map<String, Object> back = Json.parseObject(Json.stringify(m));
            eq("Ravi \"R\" <b>\n", back.get("name"));
            eq(42L, back.get("n"));
            eq(3.5, back.get("d"));
            eq(true, back.get("ok"));
            eq(List.of(1L, "two"), back.get("list"));
            check(back.containsKey("none") && back.get("none") == null, "null kept");
            check(!Json.stringify(m).contains("<b>"), "HTML characters are escaped");
        });
        test("JSON rejects broken input", () -> {
            expectThrows(() -> Json.parse("{\"a\": }"));
            expectThrows(() -> Json.parse("[1, 2"));
            expectThrows(() -> Json.parse("{} extra"));
        });
        test("Stemmer maps word forms together", () -> {
            eq(TextProcessor.stem("refunded"), TextProcessor.stem("refunds"));
            eq(TextProcessor.stem("charged"), TextProcessor.stem("charge"));
            eq(TextProcessor.stem("delivery"), TextProcessor.stem("delivered"));
            check(!TextProcessor.terms("I was not happy with the order").contains("the"), "stop words removed");
        });

        // ---- AI components
        SentimentAnalyzer sa = new SentimentAnalyzer();
        test("Sentiment: angry text is very negative", () ->
                eq(Sentiment.VERY_NEGATIVE, sa.analyze("This is the WORST service ever, absolutely pathetic and unacceptable!!!").label()));
        test("Sentiment: happy text is positive", () ->
                eq(Sentiment.POSITIVE, sa.analyze("Great service, the agent was very helpful and friendly. Thanks!").label()));
        test("Sentiment: negation flips meaning", () -> {
            double plain = sa.analyze("The support was helpful").score();
            double negated = sa.analyze("The support was not helpful").score();
            check(plain > 0 && negated < 0, "not helpful should be negative: " + plain + " / " + negated);
        });
        test("Sentiment: detects emotions", () -> {
            List<String> e = sa.analyze("I am so worried, my account was hacked and someone used my card").emotions();
            check(e.contains("anxiety"), "expected anxiety in " + e);
        });

        ComplaintClassifier clf = new ComplaintClassifier();
        test("Classifier: billing", () -> eq(Category.BILLING, clf.predict("I was charged twice on my credit card for one order").category()));
        test("Classifier: delivery", () -> eq(Category.DELIVERY, clf.predict("My parcel is late and tracking has not updated for a week").category()));
        test("Classifier: refund", () -> eq(Category.REFUND, clf.predict("Returned the item but my refund is still not credited").category()));
        test("Classifier: account", () -> eq(Category.ACCOUNT, clf.predict("Cannot log in, password reset link and OTP not working").category()));
        test("Classifier: technical", () -> eq(Category.TECHNICAL, clf.predict("The mobile app crashes with an error when I open it").category()));
        test("Classifier: service", () -> eq(Category.SERVICE, clf.predict("The customer care agent was rude and nobody called back").category()));
        test("Classifier: product quality", () -> eq(Category.PRODUCT_QUALITY, clf.predict("Product arrived broken and defective, screen cracked").category()));
        test("Classifier: unknown words give OTHER with zero confidence", () -> {
            ComplaintClassifier.Prediction p = clf.predict("zzqx blorf");
            eq(Category.OTHER, p.category());
            eq(0.0, p.confidence());
        });
        test("Classifier: learns from new examples", () -> {
            ComplaintClassifier c2 = new ComplaintClassifier();
            for (int i = 0; i < 6; i++) {
                c2.train("gift wrapping ribbon torn wrapping paper", Category.PRODUCT_QUALITY);
            }
            eq(Category.PRODUCT_QUALITY, c2.predict("the gift wrapping ribbon was torn").category());
        });

        PriorityEngine pe = new PriorityEngine();
        test("Priority: safety risk is critical and auto-escalates", () -> {
            PriorityEngine.Result r = pe.evaluate("The charger caught fire and gave my son an electric shock",
                    Sentiment.NEGATIVE, Category.PRODUCT_QUALITY, CustomerTier.STANDARD, Channel.WEB, 0);
            eq(Priority.CRITICAL, r.priority());
            check(r.autoEscalate(), "should escalate");
        });
        test("Priority: calm question is low", () -> {
            PriorityEngine.Result r = pe.evaluate("I have a suggestion about your website colours",
                    Sentiment.NEUTRAL, Category.OTHER, CustomerTier.STANDARD, Channel.WEB, 0);
            eq(Priority.LOW, r.priority());
            check(!r.autoEscalate(), "no escalation");
        });
        test("Priority: VIP and repeat complaints raise priority", () -> {
            Priority normal = pe.evaluate("My order is late", Sentiment.NEGATIVE, Category.DELIVERY,
                    CustomerTier.STANDARD, Channel.WEB, 0).priority();
            Priority vip = pe.evaluate("My order is late", Sentiment.NEGATIVE, Category.DELIVERY,
                    CustomerTier.VIP, Channel.WEB, 2).priority();
            check(vip.ordinal() > normal.ordinal(), normal + " -> " + vip);
        });

        // ---- full service flow (in-memory store)
        test("Service: create runs the AI and recommends the right fix", () -> {
            Env env = new Env();
            Complaint c = env.complaints.create(input("Asha Rao", "asha@example.com",
                    "Double charge", "I was charged twice for order 991, two debits on my bank statement"), "admin");
            eq(Category.BILLING, c.category);
            check(!c.recommendations.isEmpty(), "has recommendations");
            eq("SOL-001", c.recommendations.get(0).solutionId);
            check(c.draftResponse.contains("Asha") && c.draftResponse.contains(c.id), "draft is personalised");
            check(c.dueAt != null && c.assignedTo != null, "deadline and agent set");
            check(c.recommendations.get(0).score >= 0 && c.recommendations.get(0).score <= 1, "score in range");
        });
        test("Service: validation rejects bad input", () -> {
            Env env = new Env();
            expectStatus(400, () -> env.complaints.create(input("A", "a@b.co", "Hi", "short"), "admin"));
            expectStatus(400, () -> env.complaints.create(input("Asha", "not-an-email", "Late order", "My order is very late now"), "admin"));
        });
        test("Service: apply, feedback, respond, resolve and rate", () -> {
            Env env = new Env();
            User agent = env.store.users().get("priya");
            Complaint c = env.complaints.create(input("Asha Rao", "asha@example.com", "Refund pending",
                    "I returned the shoes and my refund is still pending after two weeks"), null);
            String top = c.recommendations.get(0).solutionId;
            int before = env.store.solutions().get(top).helpfulCount;
            env.complaints.feedback(c.id, top, true, agent);
            env.complaints.feedback(c.id, top, true, agent); // same vote twice must not double count
            eq(before + 1, env.store.solutions().get(top).helpfulCount);
            env.complaints.applySolution(c.id, top, false, agent);
            eq(Status.IN_PROGRESS, env.complaints.get(c.id).status);
            env.complaints.sendResponse(c.id, "Dear Asha, your refund has been pushed today.", true, agent);
            Complaint done = env.complaints.get(c.id);
            eq(Status.RESOLVED, done.status);
            check(done.responseSent && done.resolvedAt != null, "resolved with reply");
            env.complaints.rate(c.id, "ASHA@example.com", 5);
            eq(5, env.complaints.get(c.id).satisfactionRating);
            expectStatus(404, () -> env.complaints.track(c.id, "someone@else.com"));
        });
        test("Service: closed complaints can only be re-opened", () -> {
            Env env = new Env();
            User agent = env.store.users().get("priya");
            Complaint c = env.complaints.create(input("Asha Rao", "asha@example.com", "App crash",
                    "The app crashes when I try to pay at checkout"), "admin");
            env.complaints.updateStatus(c.id, "CLOSED", null, agent);
            expectStatus(400, () -> env.complaints.updateStatus(c.id, "IN_PROGRESS", null, agent));
            env.complaints.updateStatus(c.id, "OPEN", null, agent);
            eq(Status.OPEN, env.complaints.get(c.id).status);
            expectStatus(400, () -> env.complaints.updateStatus(c.id, "ESCALATED", "", agent));
        });
        test("Service: detects duplicate complaints from the same customer", () -> {
            Env env = new Env();
            Complaint a = env.complaints.create(input("Asha Rao", "asha@example.com", "Parcel not delivered",
                    "My parcel shows delivered but I never received the package"), null);
            Complaint b = env.complaints.create(input("Asha Rao", "asha@example.com", "Still no parcel",
                    "The package shows delivered but I never received my parcel"), null);
            eq(a.id, b.duplicateOf);
            check(b.priorityReasons.stream().anyMatch(r -> r.startsWith("Repeat complaint")), "repeat raises priority");
        });
        test("Service: SLA monitor escalates overdue complaints", () -> {
            Env env = new Env();
            Complaint c = env.complaints.create(input("Asha Rao", "asha@example.com", "Coupon not applied",
                    "My coupon code was not applied at checkout"), "admin");
            c.dueAt = Instant.now().minus(Duration.ofHours(1));
            eq(1, env.complaints.escalateOverdue());
            eq(Status.ESCALATED, env.complaints.get(c.id).status);
            eq(0, env.complaints.escalateOverdue());
        });
        test("Service: correcting the category retrains the AI", () -> {
            Env env = new Env();
            User agent = env.store.users().get("priya");
            Complaint c = env.complaints.create(input("Asha Rao", "asha@example.com", "Loyalty points gone",
                    "My loyalty points vanished from the wallet"), "admin");
            int size = env.ai.classifier().trainingSize();
            env.complaints.changeCategory(c.id, "ACCOUNT", agent);
            eq(Category.ACCOUNT, env.complaints.get(c.id).category);
            eq(size + 1, env.ai.classifier().trainingSize());
        });
        test("Service: CSV export escapes commas and formula injection", () -> {
            Env env = new Env();
            env.complaints.create(input("=HYPERLINK(\"x\")", "asha@example.com", "Late, very late",
                    "My order is late, and tracking has not moved"), "admin");
            String csv = env.complaints.exportCsv();
            check(csv.contains("\"'=HYPERLINK(\"\"x\"\")\""), "formula neutralised: " + csv);
            check(csv.contains("\"Late, very late\""), "comma field quoted");
        });
        test("Knowledge base: admin only and validated", () -> {
            Env env = new Env();
            User agent = env.store.users().get("priya");
            User admin = env.store.users().get("admin");
            Map<String, Object> in = new LinkedHashMap<>();
            in.put("title", "Fix gift card balance");
            in.put("category", "BILLING");
            in.put("keywords", "gift card, balance");
            in.put("steps", List.of("Check the gift card ledger", "Restore missing balance"));
            in.put("responseTemplate", "I have restored the missing balance on your gift card.");
            expectStatus(403, () -> env.kb.create(in, agent));
            var s = env.kb.create(in, admin);
            eq(List.of("gift card", "balance"), s.keywords);
            in.put("steps", List.of());
            expectStatus(400, () -> env.kb.create(in, admin));
        });
        test("Auth: correct password works, wrong ones lock the account", () -> {
            Env env = new Env();
            check(env.auth.userForToken(env.auth.login("Priya", "agent123")) != null, "login ok");
            for (int i = 0; i < 5; i++) {
                expectStatus(401, () -> env.auth.login("rahul", "wrong"));
            }
            expectStatus(429, () -> env.auth.login("rahul", "agent123"));
            check(env.auth.userForToken("made-up-token") == null, "fake token rejected");
        });
        test("Demo data loads and analytics add up", () -> {
            Env env = new Env();
            env.complaints.seedDemoComplaints(SeedData.complaints(), env.auth);
            Map<String, Object> a = env.analytics.summary();
            @SuppressWarnings("unchecked")
            Map<String, Object> kpi = (Map<String, Object>) a.get("kpi");
            eq(SeedData.complaints().size(), ((Number) kpi.get("total")).intValue());
            check(((Number) kpi.get("resolved")).intValue() >= 5, "resolved demo complaints");
        });

        // ---- HTTP API end-to-end
        test("HTTP: login, create, read, permissions and security checks", () -> {
            Env env = new Env();
            ApiServer server = new ApiServer(env.store, env.ai, env.auth, env.complaints, env.kb, env.analytics);
            server.start(0);
            try {
                String base = "http://localhost:" + server.port();
                HttpClient http = HttpClient.newHttpClient();
                eq(401, send(http, "GET", base + "/api/complaints", null, null).statusCode());
                eq(401, send(http, "POST", base + "/api/auth/login", "{\"username\":\"priya\",\"password\":\"x\"}", null).statusCode());

                HttpResponse<String> login = send(http, "POST", base + "/api/auth/login",
                        "{\"username\":\"priya\",\"password\":\"agent123\"}", null);
                eq(200, login.statusCode());
                String token = Json.str(Json.parseObject(login.body()), "token");

                HttpResponse<String> created = send(http, "POST", base + "/api/complaints",
                        Json.stringify(input("Asha Rao", "asha@example.com", "App crash",
                                "The app keeps crashing when I open it after the update")), token);
                eq(201, created.statusCode());
                String id = Json.str(Json.parseObject(created.body()), "id");
                eq("TECHNICAL", Json.str(Json.parseObject(created.body()), "category"));

                HttpResponse<String> got = send(http, "GET", base + "/api/complaints/" + id, null, token);
                eq(200, got.statusCode());
                check(got.body().contains("solutionDetails"), "detail includes solutions");

                eq(403, send(http, "DELETE", base + "/api/complaints/" + id, null, token).statusCode());
                eq(404, send(http, "GET", base + "/api/complaints/CMP-9999", null, token).statusCode());
                eq(400, send(http, "POST", base + "/api/complaints/" + id + "/notes", "{bad json", token).statusCode());

                HttpResponse<String> track = send(http, "POST", base + "/api/public/track",
                        "{\"id\":\"" + id + "\",\"email\":\"asha@example.com\"}", null);
                eq(200, track.statusCode());
                check(!track.body().contains("priorityReasons"), "customers do not see internal AI notes");

                eq(404, send(http, "GET", base + "/static/..%2F..%2Fdata%2Fusers.json", null, null).statusCode());
                HttpResponse<String> page = send(http, "GET", base + "/", null, null);
                check(page.headers().firstValue("Content-Security-Policy").isPresent(), "CSP header present");
                eq(200, send(http, "GET", base + "/api/export/complaints.csv", null, token).statusCode());
            } finally {
                server.stop();
            }
        });
        test("Store: data survives a restart", () -> {
            Path dir = Files.createTempDirectory("crs-test");
            DataStore s1 = new DataStore(dir);
            Env env = new Env(s1);
            Complaint c = env.complaints.create(input("Asha Rao", "asha@example.com", "OTP missing",
                    "I cannot log in because the OTP never arrives"), "admin");
            DataStore s2 = new DataStore(dir);
            s2.load();
            Complaint back = s2.complaints().get(c.id);
            check(back != null, "complaint reloaded");
            eq(c.category, back.category);
            eq(c.recommendations.size(), back.recommendations.size());
            check(s2.users().get("admin").passwordHash != null, "users saved with hashed password");
            check(!Files.readString(dir.resolve("users.json")).contains("admin123"), "no plain passwords on disk");
        });

        // ---- summary
        System.out.println();
        System.out.println(passed + " passed, " + failures.size() + " failed");
        if (!failures.isEmpty()) {
            failures.forEach(f -> System.out.println("  FAILED: " + f));
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ test helpers

    /** A fresh, in-memory application with seeded users and knowledge base. */
    static final class Env {
        final DataStore store;
        final AiEngine ai = new AiEngine(new LlmClient(null, null));
        final AuthService auth;
        final ComplaintService complaints;
        final KnowledgeBaseService kb;
        final AnalyticsService analytics;

        Env() {
            this(DataStore.inMemory());
        }

        Env(DataStore store) {
            this.store = store;
            auth = new AuthService(store);
            complaints = new ComplaintService(store, ai);
            kb = new KnowledgeBaseService(store);
            analytics = new AnalyticsService(store, ZoneId.of("Asia/Kolkata"));
            auth.seedUsers();
            SeedData.solutions().forEach(s -> store.solutions().put(s.id, s));
        }
    }

    static Map<String, Object> input(String name, String email, String subject, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("customerName", name);
        m.put("customerEmail", email);
        m.put("subject", subject);
        m.put("description", description);
        return m;
    }

    static HttpResponse<String> send(HttpClient http, String method, String url, String body, String token)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static void test(String name, Test t) {
        try {
            t.run();
            passed++;
            System.out.println("  PASS  " + name);
        } catch (Throwable e) {
            failures.add(name + " -> " + e.getMessage());
            System.out.println("  FAIL  " + name + " -> " + e.getMessage());
        }
    }

    static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }

    static void eq(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("expected <" + expected + "> but got <" + actual + ">");
        }
    }

    static void expectThrows(Test t) {
        try {
            t.run();
        } catch (Exception e) {
            return;
        }
        throw new AssertionError("expected an exception");
    }

    static void expectStatus(int status, Test t) {
        try {
            t.run();
        } catch (ServiceException e) {
            if (e.status() != status) {
                throw new AssertionError("expected HTTP " + status + " but got " + e.status() + " (" + e.getMessage() + ")");
            }
            return;
        } catch (Exception e) {
            throw new AssertionError("expected ServiceException, got " + e);
        }
        throw new AssertionError("expected HTTP " + status + " error");
    }
}
