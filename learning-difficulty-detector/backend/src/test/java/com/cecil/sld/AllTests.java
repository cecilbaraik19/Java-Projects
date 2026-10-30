package com.cecil.sld;

import com.cecil.sld.model.*;
import com.cecil.sld.service.*;
import com.cecil.sld.store.DataStore;
import com.cecil.sld.util.Csv;
import com.cecil.sld.util.Json;
import com.cecil.sld.util.Passwords;
import com.cecil.sld.web.ApiServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

/**
 * All backend tests in one file, with a tiny test runner - no JUnit download needed.
 * The clock is fixed at 30 Oct 2026, 10:00 so results never depend on the real date.
 * Run with run-tests.bat / run-tests.sh.
 */
public final class AllTests {
    private AllTests() {}

    static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    static final Instant START = LocalDateTime.of(2026, 10, 30, 10, 0).atZone(ZONE).toInstant();

    /** A clock the tests can move forward. */
    static final class TestClock extends Clock {
        Instant now = START;
        @Override public ZoneId getZone() { return ZONE; }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
        void plus(Duration d) { now = now.plus(d); }
    }

    private static int passed = 0, failed = 0;
    private static final List<String> failures = new ArrayList<>();

    interface Body { void run() throws Exception; }

    static void test(String name, Body b) {
        try {
            b.run();
            passed++;
            System.out.println("  PASS  " + name);
        } catch (Throwable t) {
            failed++;
            failures.add(name + ": " + t);
            System.out.println("  FAIL  " + name + "  ->  " + t);
        }
    }

    static void check(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }
    static void eq(Object expected, Object actual, String msg) {
        if (!Objects.equals(expected, actual)) throw new AssertionError(msg + " (expected " + expected + ", got " + actual + ")");
    }
    static void fails(Body b, String contains) {
        try { b.run(); } catch (ApiError e) {
            if (contains != null && !e.getMessage().toLowerCase().contains(contains.toLowerCase()))
                throw new AssertionError("Wrong error message: " + e.getMessage());
            return;
        } catch (Exception e) { throw new AssertionError("Wrong exception: " + e); }
        throw new AssertionError("Expected an error containing '" + contains + "'");
    }

    // ------------------------------------------------------------------ fixtures

    static DataStore seeded(TestClock clock) {
        DataStore db = new DataStore(null, clock);
        db.load();
        return db;
    }

    static Topic topic(String name, int expected) {
        Topic t = new Topic();
        t.id = "TX";
        t.subjectId = "SX";
        t.name = name;
        t.expectedSeconds = expected;
        return t;
    }

    static AttemptItem item(boolean correct, int seconds, int confidence, boolean hint, int level) {
        AttemptItem it = new AttemptItem();
        it.questionId = "Q";
        it.topicId = "TX";
        it.correct = correct;
        it.chosen = correct ? 0 : 1;
        it.seconds = seconds;
        it.confidence = confidence;
        it.hintUsed = hint;
        it.level = level;
        return it;
    }

    static List<LocalDateTime> times(int n, int daysAgo) {
        List<LocalDateTime> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(LocalDateTime.of(2026, 10, 30, 9, 0).minusDays(daysAgo).plusMinutes(i));
        return l;
    }

    static Topic topicByName(DataStore db, String name) {
        for (Topic t : db.topics) if (t.name.equals(name)) return t;
        throw new AssertionError("No topic " + name);
    }

    // ------------------------------------------------------------------ tests

    public static void main(String[] args) throws Exception {
        System.out.println("Smart Learning Difficulty Detector - tests (clock fixed at 30 Oct 2026)");
        TestClock clock = new TestClock();
        DataStore demo = seeded(clock);              // shared, read-only use
        DifficultyEngine demoEngine = new DifficultyEngine(demo);
        DifficultyEngine plain = new DifficultyEngine(new DataStore(null, clock));

        test("JSON writes and reads back, escaping < > & and quotes", () -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("text", "a \"quote\" <script>&");
            m.put("n", 3);
            m.put("x", 1.5);
            m.put("list", List.of(true, "b"));
            String s = Json.write(m);
            check(!s.contains("<script>"), "angle brackets must be escaped");
            Map<String, Object> back = Json.parseObject(s);
            eq("a \"quote\" <script>&", back.get("text"), "text");
            eq(3.0, back.get("n"), "number");
            eq(List.of(true, "b"), back.get("list"), "list");
            boolean bad = false;
            try { Json.parse("{\"a\":}"); } catch (IllegalArgumentException e) { bad = true; }
            check(bad, "bad JSON must be refused");
        });

        test("JSON binds a model object with lists and enums", () -> {
            User u = new User();
            u.id = "U9"; u.username = "x"; u.name = "X"; u.role = Role.TEACHER;
            Topic t = new Topic();
            t.id = "T1"; t.name = "Trees"; t.prerequisiteIds = new ArrayList<>(List.of("T0"));
            User u2 = Json.bind(Json.parse(Json.write(u)), User.class);
            Topic t2 = Json.bind(Json.parse(Json.write(t)), Topic.class);
            eq(Role.TEACHER, u2.role, "enum");
            eq(List.of("T0"), t2.prerequisiteIds, "list");
        });

        test("Passwords: hash, verify, and the rules for a new password", () -> {
            String h = Passwords.hash("secret123");
            check(Passwords.verify("secret123", h), "right password");
            check(!Passwords.verify("secret124", h), "wrong password");
            check(!h.equals(Passwords.hash("secret123")), "salt makes hashes different");
            check(Passwords.problem("short1") != null, "too short");
            check(Passwords.problem("onlyletters") != null, "needs a number");
            check(Passwords.problem("letters123") == null, "good password");
        });

        test("CSV quotes cells and blocks formula injection", () -> {
            eq("\"'=SUM(A1)\"", Csv.cell("=SUM(A1)"), "formula guarded");
            eq("\"a,b\"", Csv.cell("a,b"), "comma quoted");
            eq("-5", Csv.cell(-5), "negative number left alone");
        });

        test("Engine: all right and quick = Easy, low index", () -> {
            List<AttemptItem> items = new ArrayList<>();
            for (int i = 0; i < 8; i++) items.add(item(true, 40, 3, false, 2));
            DifficultyEngine.TopicResult r = plain.computeForTest(topic("A", 60), items, times(8, 0), 0);
            eq(Band.EASY, r.band, "band");
            check(r.index < 10, "index should be low, was " + r.index);
        });

        test("Engine: all wrong, slow, with hints = Very difficult", () -> {
            List<AttemptItem> items = new ArrayList<>();
            for (int i = 0; i < 8; i++) items.add(item(false, 150, 2, true, 1));
            DifficultyEngine.TopicResult r = plain.computeForTest(topic("A", 60), items, times(8, 0), 0);
            eq(Band.CRITICAL, r.band, "band");
            check(r.reasons.stream().anyMatch(x -> x.contains("expected time")), "slow reason");
            check(r.reasons.stream().anyMatch(x -> x.contains("hint")), "hint reason");
        });

        test("Engine: fewer answers than the minimum = Not enough data", () -> {
            List<AttemptItem> items = List.of(item(false, 60, 2, false, 2), item(false, 60, 2, false, 2));
            DifficultyEngine.TopicResult r = plain.computeForTest(topic("A", 60), items, times(2, 0), 5);
            eq(Band.NO_DATA, r.band, "band");
            eq(-1.0, r.index, "index");
        });

        test("Engine: wrong while 'sure' scores higher than wrong while unsure (misconception)", () -> {
            List<AttemptItem> sure = new ArrayList<>(), unsure = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                boolean right = i % 2 == 0;
                sure.add(item(right, 60, 3, false, 2));
                unsure.add(item(right, 60, 1, false, 2));
            }
            DifficultyEngine.TopicResult a = plain.computeForTest(topic("A", 60), sure, times(6, 0), 0);
            DifficultyEngine.TopicResult b = plain.computeForTest(topic("A", 60), unsure, times(6, 0), 0);
            check(a.index > b.index + 5, "sure-wrong " + a.index + " vs unsure " + b.index);
            check(a.reasons.stream().anyMatch(x -> x.contains("misunderstanding")), "misconception reason");
        });

        test("Engine: a wrong answer on an easy question counts more than on a hard one", () -> {
            List<AttemptItem> easy = new ArrayList<>(), hard = new ArrayList<>();
            for (int i = 0; i < 6; i++) { easy.add(item(i < 3, 60, 2, false, 1)); hard.add(item(i < 3, 60, 2, false, 3)); }
            double e = plain.computeForTest(topic("A", 60), easy, times(6, 0), 0).index;
            double h = plain.computeForTest(topic("A", 60), hard, times(6, 0), 0).index;
            check(e > h, "easy-wrong " + e + " should be above hard-wrong " + h);
        });

        test("Engine: newer answers count more than old ones (half-life)", () -> {
            List<AttemptItem> items = new ArrayList<>();
            List<LocalDateTime> when = new ArrayList<>();
            for (int i = 0; i < 4; i++) { items.add(item(false, 60, 2, false, 2)); when.add(LocalDateTime.of(2026, 8, 1, 10, i)); }
            for (int i = 0; i < 4; i++) { items.add(item(true, 60, 2, false, 2)); when.add(LocalDateTime.of(2026, 10, 29, 10, i)); }
            DifficultyEngine.TopicResult r = plain.computeForTest(topic("A", 60), items, when, 0);
            check(r.accuracy > 0.8, "recent right answers should dominate, accuracy " + r.accuracy);
            check(r.reasons.stream().anyMatch(x -> x.startsWith("Improving")), "improving reason");
        });

        test("Engine: getting worse and 'feels hard' both add to the index", () -> {
            List<AttemptItem> items = new ArrayList<>();
            for (int i = 0; i < 4; i++) items.add(item(true, 60, 2, false, 2));
            for (int i = 0; i < 4; i++) items.add(item(false, 60, 2, false, 2));
            List<LocalDateTime> when = times(8, 0);
            double noSelf = plain.computeForTest(topic("A", 60), items, when, 0).index;
            DifficultyEngine.TopicResult withSelf = plain.computeForTest(topic("A", 60), items, when, 5);
            check(withSelf.index > noSelf, "self rating 5 should raise it");
            check(withSelf.reasons.stream().anyMatch(x -> x.startsWith("Getting worse")), "decline reason");
        });

        test("Bands follow the limits in Settings", () -> {
            DataStore db = new DataStore(null, clock);
            DifficultyEngine e = new DifficultyEngine(db);
            eq(Band.EASY, e.bandOf(29.9), "29.9");
            eq(Band.MODERATE, e.bandOf(30), "30");
            eq(Band.DIFFICULT, e.bandOf(50), "50");
            eq(Band.CRITICAL, e.bandOf(70), "70");
            db.settings.criticalFrom = 90;
            eq(Band.DIFFICULT, e.bandOf(75), "after change");
        });

        test("Demo data: 3 subjects, 20 topics, 100 questions, 24 students, a hard Deadlocks topic", () -> {
            eq(3, demo.subjects.size(), "subjects");
            eq(20, demo.topics.size(), "topics");
            eq(100, demo.questions.size(), "questions");
            eq(24, demo.students().size(), "students");
            check(demo.attempts.size() > 250, "attempts " + demo.attempts.size());
            Topic dl = topicByName(demo, "Deadlocks");
            double deadlocks = 0, models = 0;
            for (Map<String, Object> m : demoEngine.classTopics(null, null)) {
                if (m.get("topicId").equals(dl.id)) deadlocks = ((Number) m.get("index")).doubleValue();
                if (m.get("topic").equals("OSI & TCP/IP Models")) models = ((Number) m.get("index")).doubleValue();
            }
            check(deadlocks > models + 20, "Deadlocks (" + deadlocks + ") should be much harder than OSI models (" + models + ")");
            for (Question q : demo.questions) check(q.answer >= 0 && q.answer < q.options.size(), "answer index in " + q.id);
        });

        test("Root cause: a weak prerequisite is reported for the topic that needs it", () -> {
            boolean found = false;
            for (User st : demo.students()) {
                for (DifficultyEngine.TopicResult r : demoEngine.forStudent(st.id, null)) {
                    if (r.rootCauseIds.isEmpty()) continue;
                    found = true;
                    Topic t = demo.topic(r.topicId);
                    for (String pid : r.rootCauseIds) check(t.prerequisiteIds.contains(pid), "root cause must be a prerequisite");
                }
            }
            check(found, "the demo should contain at least one root cause");
        });

        test("Study route: a prerequisite always comes before the topic that needs it", () -> {
            for (User st : demo.students()) {
                List<Map<String, Object>> plan = demoEngine.studyPlan(st.id, null);
                Map<String, Integer> pos = new HashMap<>();
                for (int i = 0; i < plan.size(); i++) pos.put(((DifficultyEngine.TopicResult) plan.get(i).get("result")).topicId, i);
                for (String tid : pos.keySet())
                    for (String pid : demo.topic(tid).prerequisiteIds)
                        if (pos.containsKey(pid)) check(pos.get(pid) < pos.get(tid), "prerequisite order for " + st.name);
            }
        });

        // ---- quizzes (on a separate copy so the shared demo stays the same)
        TestClock qc = new TestClock();
        DataStore qdb = seeded(qc);
        DifficultyEngine qe = new DifficultyEngine(qdb);
        QuizService quiz = new QuizService(qdb, qe, new Random(1));
        User karan = qdb.userByName("karan");
        User riya = qdb.userByName("riya");
        Subject os = qdb.subjects.get(0);

        test("Test quiz covers every topic and never sends the right answers", () -> {
            Map<String, Object> q = quiz.start(karan, os.id, "TEST", 0);
            @SuppressWarnings("unchecked") List<Map<String, Object>> qs = (List<Map<String, Object>>) q.get("questions");
            Set<Object> topics = new HashSet<>();
            for (Map<String, Object> m : qs) {
                topics.add(m.get("topicId"));
                check(!m.containsKey("answer") && !m.containsKey("explanation"), "answer leaked");
            }
            eq(qdb.topicsOf(os.id).size(), topics.size(), "topics covered");
            check(!Json.write(q).contains("\"answer\""), "no answer field anywhere");
        });

        test("Practice quiz focuses on the student's difficult topics", () -> {
            Set<String> hard = new HashSet<>();
            for (DifficultyEngine.TopicResult r : qe.forStudent(karan.id, os.id))
                if (r.band.ordinal() >= Band.MODERATE.ordinal()) { hard.add(r.topicId); hard.addAll(r.rootCauseIds); }
            int inHard = 0, total = 0;
            for (int round = 0; round < 5; round++) {
                Map<String, Object> q = quiz.start(karan, os.id, "PRACTICE", 10);
                @SuppressWarnings("unchecked") List<Map<String, Object>> qs = (List<Map<String, Object>>) q.get("questions");
                for (Map<String, Object> m : qs) { total++; if (hard.contains(m.get("topicId"))) inHard++; }
            }
            check(inHard >= total * 0.6, "only " + inHard + " of " + total + " practice questions were from difficult topics");
        });

        test("Submitting marks answers, records hints, saves the attempt, and cannot be repeated", () -> {
            Map<String, Object> q = quiz.start(riya, os.id, "PRACTICE", 5);
            String qid = (String) q.get("quizId");
            @SuppressWarnings("unchecked") List<Map<String, Object>> qs = (List<Map<String, Object>>) q.get("questions");
            String first = (String) qs.get(0).get("id");
            quiz.hint(riya, qid, first);
            List<Object> answers = new ArrayList<>();
            for (Map<String, Object> m : qs) {
                Question real = qdb.question((String) m.get("id"));
                answers.add(Map.of("questionId", real.id, "chosen", (double) real.answer, "seconds", 30.0, "confidence", 3.0));
            }
            int before = qdb.attempts.size();
            Map<String, Object> res = quiz.submit(riya, qid, answers);
            eq(qs.size(), res.get("score"), "all right");
            eq(before + 1, qdb.attempts.size(), "attempt saved");
            Attempt a = qdb.attempts.get(qdb.attempts.size() - 1);
            check(a.items.get(0).hintUsed, "hint recorded by the server");
            check(!a.items.get(1).hintUsed || a.items.get(1).questionId.equals(first), "no other hints");
            check(!((List<?>) res.get("changes")).isEmpty(), "topic changes returned");
            fails(() -> quiz.submit(riya, qid, answers), "not open");
        });

        test("Submit refuses bad answers: unknown question, option out of range, another student's quiz", () -> {
            Map<String, Object> q = quiz.start(riya, os.id, "TEST", 0);
            String qid = (String) q.get("quizId");
            @SuppressWarnings("unchecked") List<Map<String, Object>> qs = (List<Map<String, Object>>) q.get("questions");
            fails(() -> quiz.submit(riya, qid, List.of(Map.of("questionId", "Q9999", "chosen", 0.0))), "not in this quiz");
            fails(() -> quiz.submit(riya, qid, List.of(Map.of("questionId", qs.get(0).get("id"), "chosen", 7.0))), "out of range");
            fails(() -> quiz.submit(karan, qid, List.of()), "not open");
        });

        test("An open quiz expires after 3 hours", () -> {
            Map<String, Object> q = quiz.start(riya, os.id, "TEST", 0);
            qc.plus(Duration.ofHours(3).plusMinutes(1));
            fails(() -> quiz.submit(riya, (String) q.get("quizId"), List.of()), "expired");
            qc.now = START;
        });

        test("Self rating: 1 to 5 only, and a second rating on the same day replaces the first", () -> {
            Topic t = qdb.topicsOf(os.id).get(0);
            fails(() -> quiz.rate(riya, t.id, 6, ""), "1 (easy) to 5");
            int before = qdb.reports.size();
            quiz.rate(riya, t.id, 2, "ok");
            quiz.rate(riya, t.id, 5, "hard after all");
            eq(before + 1, qdb.reports.size(), "only one new rating");
            eq(5, qdb.reports.get(qdb.reports.size() - 1).rating, "replaced");
        });

        // ---- catalog, plans, users
        CatalogService cat = new CatalogService(qdb);
        PlanService plans = new PlanService(qdb, qe);
        AuthService auth = new AuthService(qdb);
        UserService users = new UserService(qdb, auth);
        User neha = qdb.userByName("neha");
        User admin = qdb.userByName("admin");

        test("Topics: a prerequisite loop is refused", () -> {
            Topic proc = topicByName(qdb, "Processes & Threads");
            Topic dead = topicByName(qdb, "Deadlocks");
            Map<String, Object> in = new HashMap<>();
            in.put("subjectId", proc.subjectId);
            in.put("name", proc.name);
            in.put("unit", proc.unit);
            in.put("expectedSeconds", 55);
            in.put("prerequisiteIds", List.of(dead.id));       // Deadlocks already needs Processes (through Sync)
            fails(() -> cat.saveTopic(neha, proc.id, in), "loop");
        });

        test("Questions: answered ones cannot change their right answer; delete turns them off", () -> {
            Question q = null;
            for (Attempt a : qdb.attempts) { q = qdb.question(a.items.get(0).questionId); break; }
            Question fq = q;
            Map<String, Object> in = new HashMap<>();
            in.put("topicId", fq.topicId);
            in.put("text", fq.text);
            in.put("options", fq.options);
            in.put("answer", (fq.answer + 1) % fq.options.size());
            in.put("level", fq.level);
            fails(() -> cat.saveQuestion(neha, fq.id, in), "already answered");
            String msg = cat.deleteQuestion(neha, fq.id);
            check(msg.contains("turned off") && !fq.active && qdb.question(fq.id) != null, "turned off, not removed");
            Map<String, Object> bad = new HashMap<>(in);
            bad.put("options", List.of("Same", "same"));
            bad.put("answer", 0);
            fails(() -> cat.saveQuestion(neha, null, bad), "same");
        });

        test("Support plans: no duplicate open plan, no past due date, done keeps the starting index", () -> {
            Topic t = topicByName(qdb, "Routing");
            Map<String, Object> in = new HashMap<>();
            in.put("studentId", karan.id);
            in.put("topicId", t.id);
            in.put("action", "Worksheet");
            in.put("dueDate", "2026-11-05");
            SupportPlan p = plans.create(neha, in);
            check(p.indexAtStart >= 0, "starting index saved");
            fails(() -> plans.create(neha, in), "already has an open plan");
            Map<String, Object> past = new HashMap<>(in);
            past.put("topicId", topicByName(qdb, "TCP & UDP").id);
            past.put("dueDate", "2026-10-01");
            fails(() -> plans.create(neha, past), "past");
            plans.setStatus(neha, p.id, "DONE");
            eq("DONE", p.status, "done");
            check(p.doneAt != null, "done time");
            fails(() -> plans.delete(qdb.userByName("arvind"), p.id), "only the teacher");
        });

        test("Logins: one admin must remain, roll numbers are unique, passwords follow the rules", () -> {
            fails(() -> users.setActive(admin, admin.id, false), "your own login");
            Map<String, Object> in = new HashMap<>();
            in.put("username", "newkid");
            in.put("name", "New Kid");
            in.put("role", "STUDENT");
            in.put("rollNo", riya.rollNo);
            in.put("password", "goodpass1");
            fails(() -> users.save(admin, null, in), "already belongs");
            in.put("rollNo", "2025-BSCIT/099A");
            in.put("password", "weak");
            fails(() -> users.save(admin, null, in), "at least 8");
            in.put("password", "goodpass1");
            User u = users.save(admin, null, in);
            eq(Role.STUDENT, u.role, "created");
            check(!Json.write(UserService.view(u)).contains("pbkdf2"), "hash never sent");
        });

        test("Sign-in: 5 wrong passwords lock the username for 5 minutes", () -> {
            for (int i = 0; i < 5; i++) fails(() -> auth.login("neha", "wrong"), "wrong username");
            fails(() -> auth.login("neha", "teacher123"), "too many");
            qc.plus(Duration.ofMinutes(6));
            check(auth.login("neha", "teacher123").token != null, "unlocked after 5 minutes");
            qc.now = START;
        });

        test("Settings: weights 0-100 and limits must go up", () -> {
            DashboardService dash = new DashboardService(qdb, qe, quiz, plans);
            fails(() -> dash.saveSettings(admin, Map.of("moderateFrom", 60.0, "difficultFrom", 50.0)), "must go up");
            fails(() -> dash.saveSettings(admin, Map.of("wWrong", 150.0)), "0 to 100");
            Settings s = dash.saveSettings(admin, Map.of("criticalFrom", 80.0));
            eq(80, s.criticalFrom, "saved");
        });

        test("Saving to disk and loading back gives the same data", () -> {
            Path dir = Files.createTempDirectory("sld-test");
            DataStore a = new DataStore(dir, clock);
            a.load();                                    // creates demo data and saves it
            DataStore b = new DataStore(dir, clock);
            b.load();
            eq(a.attempts.size(), b.attempts.size(), "attempts");
            eq(a.questions.get(5).text, b.questions.get(5).text, "question text");
            eq(a.topics.get(4).prerequisiteIds, b.topics.get(4).prerequisiteIds, "prerequisites");
            eq(new DifficultyEngine(a).forStudent(a.students().get(1).id, null).get(3).index,
               new DifficultyEngine(b).forStudent(b.students().get(1).id, null).get(3).index, "same index after reload");
            check(!b.nextId("Q").equals("Q0001"), "ids are not reused after reload");
        });

        test("HTTP API end to end: sign in, roles, origin check, quiz, security headers", () -> {
            Path front = Files.createTempDirectory("sld-front");
            Files.writeString(front.resolve("index.html"), "<!doctype html><title>t</title>");
            ApiServer server = new ApiServer(qdb, front, new Random(3));
            server.start(0);
            try {
                String base = "http://127.0.0.1:" + server.port();
                HttpClient http = HttpClient.newHttpClient();
                HttpResponse<String> home = http.send(HttpRequest.newBuilder(URI.create(base + "/")).build(), HttpResponse.BodyHandlers.ofString());
                eq(200, home.statusCode(), "home page");
                check(home.headers().firstValue("Content-Security-Policy").orElse("").contains("script-src 'self'"), "CSP header");
                eq(404, http.send(HttpRequest.newBuilder(URI.create(base + "/static/../../etc/passwd")).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "path tricks blocked");
                eq(401, http.send(HttpRequest.newBuilder(URI.create(base + "/api/me")).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "needs sign in");

                HttpResponse<String> login = http.send(HttpRequest.newBuilder(URI.create(base + "/api/login"))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"riya\",\"password\":\"student123\"}")).build(), HttpResponse.BodyHandlers.ofString());
                eq(200, login.statusCode(), "login");
                String cookie = login.headers().firstValue("Set-Cookie").orElse("").split(";")[0];
                check(login.headers().firstValue("Set-Cookie").orElse("").contains("HttpOnly"), "HttpOnly cookie");

                eq(403, http.send(HttpRequest.newBuilder(URI.create(base + "/api/heatmap?subject=" + os.id)).header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "student cannot see the class heat map");
                String other = qdb.userByName("karan").id;
                eq(403, http.send(HttpRequest.newBuilder(URI.create(base + "/api/students/" + other + "/map")).header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "student cannot see another student's map");
                eq(200, http.send(HttpRequest.newBuilder(URI.create(base + "/api/students/" + riya.id + "/map")).header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "own map");

                HttpResponse<String> evil = http.send(HttpRequest.newBuilder(URI.create(base + "/api/quiz/start")).header("Cookie", cookie).header("Origin", "http://evil.example")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"subjectId\":\"" + os.id + "\",\"kind\":\"TEST\"}")).build(), HttpResponse.BodyHandlers.ofString());
                eq(403, evil.statusCode(), "other website refused");

                HttpResponse<String> start = http.send(HttpRequest.newBuilder(URI.create(base + "/api/quiz/start")).header("Cookie", cookie)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"subjectId\":\"" + os.id + "\",\"kind\":\"TEST\"}")).build(), HttpResponse.BodyHandlers.ofString());
                eq(200, start.statusCode(), "quiz start");
                Map<String, Object> q = Json.parseObject(start.body());
                @SuppressWarnings("unchecked") List<Map<String, Object>> qs = (List<Map<String, Object>>) q.get("questions");
                StringBuilder ans = new StringBuilder("{\"answers\":[");
                for (int i = 0; i < qs.size(); i++) {
                    if (i > 0) ans.append(',');
                    ans.append("{\"questionId\":\"").append(qs.get(i).get("id")).append("\",\"chosen\":0,\"seconds\":20,\"confidence\":2}");
                }
                ans.append("]}");
                HttpResponse<String> sub = http.send(HttpRequest.newBuilder(URI.create(base + "/api/quiz/" + q.get("quizId") + "/submit")).header("Cookie", cookie)
                        .POST(HttpRequest.BodyPublishers.ofString(ans.toString())).build(), HttpResponse.BodyHandlers.ofString());
                eq(200, sub.statusCode(), "submit");
                check(Json.parseObject(sub.body()).containsKey("changes"), "changes in result");
                eq(400, http.send(HttpRequest.newBuilder(URI.create(base + "/api/login")).POST(HttpRequest.BodyPublishers.ofString("{bad")).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "bad JSON = 400");
            } finally {
                server.stop();
            }
        });

        System.out.println();
        System.out.println("Passed: " + passed + "   Failed: " + failed);
        if (failed > 0) {
            failures.forEach(f -> System.out.println("  - " + f));
            System.exit(1);
        }
        System.exit(0);
    }
}
