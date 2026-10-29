package com.cecil.qpg;

import com.cecil.qpg.generator.PaperGenerator;
import com.cecil.qpg.model.Blueprint;
import com.cecil.qpg.model.Enums.Difficulty;
import com.cecil.qpg.model.Enums.PaperStatus;
import com.cecil.qpg.model.Enums.QType;
import com.cecil.qpg.model.Paper;
import com.cecil.qpg.model.Question;
import com.cecil.qpg.model.User;
import com.cecil.qpg.service.ApiError;
import com.cecil.qpg.service.AuthService;
import com.cecil.qpg.store.DataStore;
import com.cecil.qpg.util.Csv;
import com.cecil.qpg.util.Json;
import com.cecil.qpg.util.Passwords;
import com.cecil.qpg.util.Text;
import com.cecil.qpg.web.ApiServer;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * All backend tests. A tiny test runner (no JUnit download needed):
 * each test is a method; it passes if it does not throw.
 * Run with run-tests.bat / run-tests.sh, or `mvn test`.
 */
public class AllTests {

    interface Test { void run() throws Exception; }

    private static int passed, failed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true"); // lets the test send an Origin header
        test("JSON read and write", AllTests::json);
        test("Passwords are hashed and checked", AllTests::passwords);
        test("CSV quoting, reading and formula guard", AllTests::csv);
        test("Similar-question detection", AllTests::similarity);
        test("Difficulty mix becomes whole numbers", AllTests::targets);
        test("Demo data is valid", AllTests::seedValid);
        test("Generated paper follows the blueprint", AllTests::followsBlueprint);
        test("Same seed gives the same paper", AllTests::sameSeed);
        test("Sets A, B, C use different questions", AllTests::setsDiffer);
        test("Bank too small: warning and attempt fixed", AllTests::shortfall);
        test("Near-duplicate questions never share a paper", AllTests::noDuplicatesInPaper);
        test("Recently used questions are avoided", AllTests::avoidRecent);
        test("MCQ options shuffle but answer stays right", AllTests::mcqShuffle);
        test("Paper analysis numbers", AllTests::analysis);
        test("Sign in, wrong password, lock-out, session end", AllTests::auth);
        test("Role rules (faculty / HOD / admin)", AllTests::roles);
        test("Question form checks", AllTests::questionChecks);
        test("Duplicate question is caught on save", AllTests::duplicateOnSave);
        test("CSV import and export", AllTests::importExport);
        test("Blueprint form checks", AllTests::blueprintChecks);
        test("Paper review flow and locking", AllTests::workflow);
        test("Swap and move questions", AllTests::swapMove);
        test("Delete rules", AllTests::deleteRules);
        test("Subjects: units in use cannot be removed", AllTests::subjects);
        test("Data is saved and loaded again", AllTests::persistence);
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
            System.out.println("  ok   " + name);
        } catch (Throwable e) {
            failed++;
            failures.add(name + " -> " + e);
            System.out.println("  FAIL " + name + " -> " + e);
        }
    }

    // ------------------------------------------------------------------ tiny assert helpers

    static void check(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }
    static void eq(Object want, Object got, String msg) {
        if (want == null ? got != null : !want.equals(got)) throw new AssertionError(msg + ": wanted <" + want + "> got <" + got + ">");
    }
    static int status(Runnable r) {
        try { r.run(); return 200; } catch (ApiError e) { return e.status; }
    }

    /** A clock the tests can move forward. */
    static class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-29T06:00:00Z");
        public ZoneId getZone() { return ZoneId.of("Asia/Kolkata"); }
        public Clock withZone(ZoneId z) { return this; }
        public Instant instant() { return now; }
        void add(Duration d) { now = now.plus(d); }
    }

    static Path tmp() throws IOException { return Files.createTempDirectory("qpg-test"); }

    static DataStore freshStore(TestClock c) throws IOException {
        DataStore s = new DataStore(tmp(), c);
        s.load();
        return s;
    }

    static ApiServer server(DataStore s) throws IOException {
        Path fe = tmp();
        Files.writeString(fe.resolve("index.html"), "<!doctype html><title>t</title>");
        Files.writeString(fe.resolve("app.js"), "console.log(1)");
        return new ApiServer(s, fe);
    }

    static Map<String, Object> m(Object... kv) { return Json.map(kv); }

    static Blueprint bp(String subject, int[] mix, Object[]... sections) {
        Blueprint b = new Blueprint();
        b.id = "B-T";
        b.name = "Test";
        b.subjectCode = subject;
        b.examType = "Test";
        b.durationMin = 60;
        b.mix.put(Difficulty.EASY, mix[0]);
        b.mix.put(Difficulty.MEDIUM, mix[1]);
        b.mix.put(Difficulty.HARD, mix[2]);
        for (Object[] x : sections) {
            Blueprint.Section s = new Blueprint.Section();
            s.title = "";
            s.type = (QType) x[0];
            s.count = (int) x[1];
            s.attempt = (int) x[2];
            s.marksEach = (int) x[3];
            b.sections.add(s);
        }
        return b;
    }

    static PaperGenerator.Options opts(int sets, long seed) {
        PaperGenerator.Options o = new PaperGenerator.Options();
        o.sets = sets;
        o.seed = seed;
        o.today = java.time.LocalDate.of(2026, 10, 29);
        return o;
    }

    static Question q(String id, QType t, int unit, Difficulty d, int marks, String text) {
        Question q = new Question();
        q.id = id; q.subjectCode = "X"; q.type = t; q.unit = unit; q.difficulty = d; q.marks = marks; q.text = text;
        q.bloom = com.cecil.qpg.model.Enums.Bloom.UNDERSTAND;
        q.answer = "a";
        return q;
    }

    // ------------------------------------------------------------------ utilities

    static void json() {
        String s = Json.write(m("a", 1, "b", List.of("x", true, 2.5), "c", null, "d", "q\"<\n"));
        Map<String, Object> back = Json.parseObject(s);
        eq(1L, back.get("a"), "int");
        eq("q\"<\n", back.get("d"), "escapes");
        check(!s.contains("<"), "< is escaped so JSON is safe inside HTML");
        eq(2.5, ((List<?>) back.get("b")).get(2), "double");
        for (String bad : new String[]{"{", "{\"a\":}", "[1,]", "{\"a\":1} x", "\"\\x\""}) {
            try { Json.parse(bad); throw new AssertionError("should fail: " + bad); } catch (IllegalArgumentException ok) { /* good */ }
        }
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 100; i++) deep.append('[');
        try { Json.parse(deep.toString()); throw new AssertionError("deep nesting should fail"); } catch (IllegalArgumentException ok) { /* good */ }
    }

    static void passwords() {
        String h = Passwords.hash("secret123");
        check(h.startsWith("pbkdf2$"), "format");
        check(!h.contains("secret123"), "not plain text");
        check(Passwords.verify("secret123", h), "right password");
        check(!Passwords.verify("secret124", h), "wrong password");
        check(!h.equals(Passwords.hash("secret123")), "random salt");
        for (String weak : new String[]{"short1", "allletters", "12345678"}) {
            try { Passwords.checkStrength(weak); throw new AssertionError("weak accepted: " + weak); } catch (IllegalArgumentException ok) { /* good */ }
        }
    }

    static void csv() {
        eq("\"a,b\"", Csv.cell("a,b"), "comma quoted");
        eq("\"say \"\"hi\"\"\"", Csv.cell("say \"hi\""), "quotes doubled");
        eq("'=SUM(A1)", Csv.cell("=SUM(A1)"), "formula guarded");
        eq("=SUM(A1)", Csv.unguard("'=SUM(A1)"), "unguard");
        List<List<String>> rows = Csv.parse("\uFEFFa,b\r\n\"x,1\",\"line1\nline2\"\n\n");
        eq(2, rows.size(), "rows");
        eq("x,1", rows.get(1).get(0), "quoted comma");
        eq("line1\nline2", rows.get(1).get(1), "line break in cell");
        eq("a", rows.get(0).get(0), "BOM removed");
    }

    static void similarity() {
        double same = Text.similarity("Explain paging with a neat diagram.", "Explain paging with a diagram");
        double close = Text.similarity("What is a semaphore? Explain wait and signal.", "Explain semaphores and the wait() and signal() operations");
        double diff = Text.similarity("Explain paging.", "Compare TCP and UDP.");
        check(same >= 0.9, "same question: " + same);
        check(close >= 0.5, "close: " + close);
        check(diff == 0, "different: " + diff);
    }

    static void targets() {
        Map<Difficulty, Integer> t = PaperGenerator.targets(Map.of(Difficulty.EASY, 30, Difficulty.MEDIUM, 50, Difficulty.HARD, 20), 7);
        eq(2, t.get(Difficulty.EASY), "easy");
        eq(4, t.get(Difficulty.MEDIUM), "medium");
        eq(1, t.get(Difficulty.HARD), "hard");
        for (int n = 1; n <= 30; n++) {
            Map<Difficulty, Integer> x = PaperGenerator.targets(Map.of(Difficulty.EASY, 33, Difficulty.MEDIUM, 33, Difficulty.HARD, 34), n);
            eq(n, x.values().stream().mapToInt(Integer::intValue).sum(), "adds up for " + n);
        }
        eq(5, PaperGenerator.targets(Map.of(), 5).get(Difficulty.MEDIUM), "no mix = medium");
    }

    // ------------------------------------------------------------------ generator

    static void seedValid() throws Exception {
        DataStore s = freshStore(new TestClock());
        check(s.questions.size() > 150, "questions: " + s.questions.size());
        eq(4, s.subjects.size(), "subjects");
        Set<String> ids = new HashSet<>();
        for (Question q : s.questions) {
            check(ids.add(q.id), "unique id " + q.id);
            check(s.subject(q.subjectCode).unit(q.unit) != null, q.id + " unit exists");
            if (q.type == QType.MCQ) check(q.options.contains(q.answer), q.id + " answer is an option");
            if (q.type == QType.TRUE_FALSE) check(q.answer.equals("True") || q.answer.equals("False"), q.id + " true/false");
            if (q.type == QType.FILL_BLANK) check(q.text.contains("___"), q.id + " has a blank");
        }
        eq(4, s.papers.size(), "demo papers");
        check(s.papers.stream().anyMatch(p -> p.status == PaperStatus.RELEASED), "one released");
        check(s.questions.stream().anyMatch(q -> q.timesUsed > 0), "usage history");
    }

    static void followsBlueprint() throws Exception {
        DataStore s = freshStore(new TestClock());
        Blueprint b = s.blueprints.get(0); // OS end-term, 70 marks
        Paper p = PaperGenerator.generate(b, s.subject(b.subjectCode), s.questions, opts(1, 42)).get(0);
        eq(b.totalMarks(), p.totalMarks(), "total marks");
        eq(70, p.totalMarks(), "70 marks");
        for (int i = 0; i < b.sections.size(); i++) {
            Blueprint.Section bs = b.sections.get(i);
            Paper.PSection ps = p.sections.get(i);
            eq(bs.count, ps.items.size(), "count in section " + i);
            for (Paper.Item it : ps.items) {
                eq(bs.type, it.type, "type");
                eq(bs.marksEach, it.marks, "marks");
            }
        }
        eq(p.questionIds().size(), new HashSet<>(p.questionIds()).size(), "no question twice");
        Map<String, Object> a = PaperGenerator.analyse(p, s.subject(b.subjectCode));
        eq(0, ((List<?>) a.get("missingUnits")).size(), "every unit covered");
        check((int) a.get("maxMixGap") <= 10, "mix close to plan: " + a.get("maxMixGap"));
    }

    static void sameSeed() throws Exception {
        DataStore s = freshStore(new TestClock());
        Blueprint b = s.blueprints.get(0);
        Paper p1 = PaperGenerator.generate(b, null, s.questions, opts(1, 7)).get(0);
        Paper p2 = PaperGenerator.generate(b, null, s.questions, opts(1, 7)).get(0);
        Paper p3 = PaperGenerator.generate(b, null, s.questions, opts(1, 8)).get(0);
        eq(p1.questionIds(), p2.questionIds(), "same seed");
        check(!p1.questionIds().equals(p3.questionIds()), "different seed gives a different paper");
    }

    static void setsDiffer() {
        List<Question> bank = new ArrayList<>();
        for (int i = 0; i < 30; i++) bank.add(q("Q" + i, QType.SHORT, 1 + i % 3, Difficulty.values()[i % 3], 5, "topic" + i + " alpha" + i));
        Blueprint b = bp("X", new int[]{34, 33, 33}, new Object[]{QType.SHORT, 9, 9, 5});
        List<Paper> sets = PaperGenerator.generate(b, null, bank, opts(3, 1));
        eq(3, sets.size(), "3 sets");
        Set<String> all = new HashSet<>();
        for (Paper p : sets) all.addAll(p.questionIds());
        eq(27, all.size(), "no shared questions");
        eq("C", sets.get(2).setLabel, "labels");
        check(sets.get(1).title.endsWith("(Set B)"), "title has set");
        for (Paper p : sets) eq(0, p.warnings.size(), "no warnings " + p.warnings);
    }

    static void shortfall() {
        List<Question> bank = List.of(q("Q1", QType.LONG, 1, Difficulty.EASY, 10, "aaa one"), q("Q2", QType.LONG, 1, Difficulty.EASY, 10, "bbb two"));
        Blueprint b = bp("X", new int[]{0, 0, 100}, new Object[]{QType.LONG, 4, 3, 10});
        Paper p = PaperGenerator.generate(b, null, bank, opts(1, 1)).get(0);
        eq(2, p.sections.get(0).items.size(), "takes what exists");
        eq(2, p.sections.get(0).attempt, "attempt lowered to what exists");
        eq(20, p.totalMarks(), "marks follow");
        check(p.warnings.get(0).contains("only 2 of 4"), "warning: " + p.warnings);
    }

    static void noDuplicatesInPaper() {
        List<Question> bank = new ArrayList<>();
        bank.add(q("Q1", QType.SHORT, 1, Difficulty.EASY, 5, "Explain paging with a neat diagram"));
        bank.add(q("Q2", QType.SHORT, 1, Difficulty.EASY, 5, "Explain paging with a diagram"));
        bank.add(q("Q3", QType.SHORT, 1, Difficulty.EASY, 5, "Compare TCP and UDP protocols"));
        Blueprint b = bp("X", new int[]{100, 0, 0}, new Object[]{QType.SHORT, 3, 3, 5});
        for (long seed = 0; seed < 20; seed++) {
            Paper p = PaperGenerator.generate(b, null, bank, opts(1, seed)).get(0);
            List<String> ids = p.questionIds();
            check(!(ids.contains("Q1") && ids.contains("Q2")), "near-duplicates together with seed " + seed);
            eq(2, ids.size(), "only 2 different questions possible");
        }
    }

    static void avoidRecent() {
        List<Question> bank = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Question x = q("Q" + i, QType.MCQ, 1, Difficulty.MEDIUM, 1, "word" + i + " thing" + i);
            x.options = List.of("a", "b");
            if (i < 5) x.lastUsedOn = "2026-10-01"; // 28 days ago
            bank.add(x);
        }
        Blueprint b = bp("X", new int[]{0, 100, 0}, new Object[]{QType.MCQ, 5, 5, 1});
        b.avoidRecentDays = 60;
        for (long seed = 0; seed < 10; seed++) {
            Paper p = PaperGenerator.generate(b, null, bank, opts(1, seed)).get(0);
            for (String id : p.questionIds()) check(Integer.parseInt(id.substring(1)) >= 5, "recent one used: " + id);
        }
        b.avoidRecentDays = 10; // used 28 days ago is fine now
        check(!PaperGenerator.recentlyUsed(bank.get(0), 10, java.time.LocalDate.of(2026, 10, 29)), "outside window");
    }

    static void mcqShuffle() {
        List<Question> bank = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Question x = q("Q" + i, QType.MCQ, 1, Difficulty.EASY, 1, "mcq" + i + " text" + i);
            x.options = new ArrayList<>(List.of("one" + i, "two" + i, "three" + i, "All of the above"));
            x.answer = "two" + i;
            bank.add(x);
        }
        Blueprint b = bp("X", new int[]{100, 0, 0}, new Object[]{QType.MCQ, 8, 8, 1});
        Paper p = PaperGenerator.generate(b, null, bank, opts(1, 3)).get(0);
        boolean moved = false;
        for (Paper.Item it : p.sections.get(0).items) {
            eq("All of the above", it.options.get(3), "'All of the above' stays last");
            eq(it.answer, it.options.get(it.answerLetter().charAt(0) - 'a'), "letter points at answer");
            if (it.options.indexOf(it.answer) != 1) moved = true;
        }
        check(moved, "options were shuffled");
    }

    static void analysis() {
        Paper p = new Paper();
        p.targetMix = m("EASY", 50, "MEDIUM", 50, "HARD", 0);
        Paper.PSection s = new Paper.PSection();
        s.type = QType.SHORT; s.attempt = 2; s.marksEach = 5;
        s.items.add(Paper.Item.of(q("A", QType.SHORT, 1, Difficulty.EASY, 5, "a"), null));
        s.items.add(Paper.Item.of(q("B", QType.SHORT, 2, Difficulty.MEDIUM, 5, "b"), null));
        s.items.add(Paper.Item.of(q("C", QType.SHORT, 2, Difficulty.HARD, 5, "c"), null));
        p.sections.add(s);
        eq(10, p.totalMarks(), "attempt 2 x 5");
        Map<String, Object> a = PaperGenerator.analyse(p, null);
        eq(15, a.get("printedMarks"), "printed");
        eq(33, a.get("maxMixGap"), "33/33/33 vs 50/50/0 -> biggest gap is Hard: 33 vs 0");
    }

    // ------------------------------------------------------------------ services

    static void auth() throws Exception {
        TestClock c = new TestClock();
        DataStore s = freshStore(c);
        AuthService a = new AuthService(s);
        AuthService.Session ses = a.login("Meera", "hod12345");
        eq("meera", a.require(ses.token).username, "signed in (username not case sensitive)");
        eq(401, status(() -> a.login("meera", "wrong")), "wrong password");
        eq(401, status(() -> a.login("nobody", "x")), "unknown user");
        for (int i = 0; i < 5; i++) status(() -> a.login("rohan", "bad"));
        eq(429, status(() -> a.login("rohan", "faculty123")), "locked after 5 wrong");
        c.add(Duration.ofMinutes(6));
        eq(200, status(() -> a.login("rohan", "faculty123")), "unlocked after 5 min");
        c.add(Duration.ofHours(13));
        eq(401, status(() -> a.require(ses.token)), "session ends after 12 h idle");
        eq(401, status(() -> a.require("made-up")), "bad token");
        AuthService.Session s2 = a.login("ananya", "faculty123");
        User u = a.require(s2.token);
        eq(400, status(() -> a.changePassword(u, "wrong", "newpass123", s2.token)), "current must match");
        eq(400, status(() -> a.changePassword(u, "faculty123", "weak", s2.token)), "strength");
        a.changePassword(u, "faculty123", "newpass123", s2.token);
        eq(200, status(() -> a.require(s2.token)), "still signed in here");
        eq(200, status(() -> a.login("ananya", "newpass123")), "new password works");
    }

    static void roles() throws Exception {
        DataStore s = freshStore(new TestClock());
        ApiServer api = server(s);
        User rohan = s.user("rohan"), ananya = s.user("ananya"), meera = s.user("meera"), admin = s.user("admin");
        Paper submitted = s.papers.stream().filter(p -> p.status == PaperStatus.SUBMITTED).findFirst().orElseThrow();
        eq(403, status(() -> api.papers.approve(rohan, submitted.id)), "faculty cannot approve");
        eq(200, status(() -> api.papers.approve(meera, submitted.id)), "HOD approves");
        Question byRohan = s.questions.stream().filter(q -> "rohan".equals(q.createdBy)).findFirst().orElseThrow();
        eq(403, status(() -> api.bank.update(ananya, byRohan.id, m("text", "Changed question text here"))), "not own question");
        eq(200, status(() -> api.bank.update(meera, byRohan.id, m("text", byRohan.text + " (edited)"))), "HOD can edit any");
        eq(403, status(() -> api.users.create(meera, m("username", "newone", "displayName", "New", "password", "abc12345"))), "only admin adds people");
        eq(200, status(() -> api.users.create(admin, m("username", "newone", "displayName", "New", "password", "abc12345", "role", "FACULTY"))), "admin adds");
        eq(409, status(() -> api.users.create(admin, m("username", "newone", "displayName", "New", "password", "abc12345"))), "username taken");
        eq(400, status(() -> api.users.update(admin, "admin", m("active", false))), "cannot turn off yourself");
        eq(400, status(() -> api.users.update(admin, "admin", m("role", "FACULTY"))), "keep one admin");
        eq(403, status(() -> api.bank.saveSubject(rohan, null, m())), "faculty cannot add subjects");
    }

    static void questionChecks() throws Exception {
        DataStore s = freshStore(new TestClock());
        ApiServer api = server(s);
        User r = s.user("rohan");
        Map<String, Object> base = m("subjectCode", "CSIT135", "unit", 2, "type", "MCQ", "difficulty", "EASY", "bloom", "REMEMBER", "marks", 1,
                "text", "Which scheduler picks the next process to run?", "options", List.of("Short-term", "Long-term", "Medium-term"), "answer", "short-term");
        Map<String, Object> made = api.bank.create(r, base);
        eq("Short-term", made.get("answer"), "answer matched to option text");
        Map<String, Object> bad = new java.util.LinkedHashMap<>(base);
        bad.put("answer", "Middle-term");
        eq(400, status(() -> api.bank.create(r, bad)), "answer must be an option");
        bad.put("answer", "Short-term"); bad.put("options", List.of("Short-term"));
        eq(400, status(() -> api.bank.create(r, bad)), "2+ options");
        bad.put("options", List.of("A", "a"));
        eq(400, status(() -> api.bank.create(r, bad)), "same option twice");
        Map<String, Object> fill = m("subjectCode", "CSIT135", "unit", 1, "type", "FILL_BLANK", "difficulty", "EASY", "bloom", "REMEMBER",
                "marks", 1, "text", "The heart of the OS is called the kernel layer.", "answer", "kernel");
        eq(400, status(() -> api.bank.create(r, fill)), "fill needs ___");
        Map<String, Object> tf = m("subjectCode", "CSIT135", "unit", 1, "type", "True / False", "difficulty", "EASY", "bloom", "L1",
                "marks", 1, "text", "Linux has a monolithic kernel design.", "answer", "t");
        Map<String, Object> tfMade = api.bank.create(r, tf);
        eq("True", tfMade.get("answer"), "true/false normalised");
        eq("TRUE_FALSE", tfMade.get("type").toString(), "type from label");
        eq(400, status(() -> api.bank.create(r, m("subjectCode", "CSIT135", "unit", 9, "type", "SHORT", "difficulty", "EASY",
                "bloom", "REMEMBER", "marks", 5, "text", "Some question text"))), "unit must exist");
        eq(400, status(() -> api.bank.create(r, m("subjectCode", "CSIT135", "unit", 1, "type", "MCQ", "difficulty", "EASY",
                "bloom", "REMEMBER", "marks", 10, "text", "Some question text", "options", List.of("a", "b"), "answer", "a"))), "objective max 5 marks");
        eq(400, status(() -> api.bank.create(r, m("subjectCode", "CSIT135", "unit", 1, "type", "SHORT", "difficulty", "WEIRD",
                "bloom", "REMEMBER", "marks", 5, "text", "Some question text"))), "bad difficulty");
    }

    static void duplicateOnSave() throws Exception {
        DataStore s = freshStore(new TestClock());
        ApiServer api = server(s);
        Question existing = s.questions.stream().filter(q -> q.type == QType.LONG).findFirst().orElseThrow();
        Map<String, Object> b = m("subjectCode", existing.subjectCode, "unit", existing.unit, "type", "LONG", "difficulty", "MEDIUM",
                "bloom", "UNDERSTAND", "marks", 10, "text", existing.text);
        eq(409, status(() -> api.bank.create(s.user("rohan"), b)), "exact duplicate blocked");
        b.put("force", true);
        eq(200, status(() -> api.bank.create(s.user("rohan"), b)), "force saves");
        List<Object> sim = api.bank.similar(existing.subjectCode, existing.text, null);
        check(sim.size() >= 2, "similar finds both");
    }

    @SuppressWarnings("unchecked")
    static void importExport() throws Exception {
        DataStore s = freshStore(new TestClock());
        ApiServer api = server(s);
        User r = s.user("rohan");
        String csv = "subject,unit,type,difficulty,bloom,marks,question,options,answer,tags\n"
                + "CSIT225,1,MCQ,EASY,REMEMBER,1,Which layer does a hub work at?,Physical | Network | Transport,Physical,devices\n"
                + "CSIT225,1,SHORT,MEDIUM,L2,5,\"Explain the bus topology, with merits and demerits.\",,Single cable...,\n"
                + "CSIT225,9,SHORT,MEDIUM,L2,5,Bad unit question here,,,\n"
                + "CSIT225,1,SHORT,MEDIUM,L2,5,\"'=cmd|' /C calc'!A0 is a formula\",,,\n"
                + "CSIT225,1,MCQ,EASY,REMEMBER,1,How many layers does the OSI model have?,5 | 6 | 7 | 4,7,\n";
        int before = s.questions.size();
        Map<String, Object> dry = api.bank.importCsv(r, csv, true);
        eq(3, dry.get("ok"), "3 good rows");
        eq(1, dry.get("duplicates"), "1 duplicate");
        eq(before, s.questions.size(), "dry run adds nothing");
        Map<String, Object> real = api.bank.importCsv(r, csv, false);
        eq(before + 3, s.questions.size(), "added");
        eq(2, ((List<Object>) real.get("errors")).size(), "bad unit + duplicate reported");
        Question formula = s.questions.get(s.questions.size() - 1);
        check(formula.text.startsWith("=cmd"), "text kept as typed: " + formula.text);
        String out = api.bank.exportCsv(Map.of("subject", "CSIT225"));
        check(out.contains("'=cmd"), "formula guarded on export");
        // export -> import again: everything is a duplicate
        Map<String, Object> again = api.bank.importCsv(r, out, true);
        eq(0, again.get("ok"), "round trip finds only duplicates");
        eq(400, status(() -> api.bank.importCsv(r, "a,b\n1,2", true)), "header checked");
    }

    static void blueprintChecks() throws Exception {
        DataStore s = freshStore(new TestClock());
        ApiServer api = server(s);
        User r = s.user("rohan");
        Map<String, Object> b = m("name", "Quiz", "subjectCode", "CSIT135", "examType", "Quiz 1", "durationMin", 30,
                "sections", List.of(m("type", "MCQ", "count", 10, "attempt", 10, "marksEach", 1)),
                "mix", m("EASY", 50, "MEDIUM", 30, "HARD", 10), "units", List.of(1, 2));
        eq(400, status(() -> api.blueprints.create(r, b)), "mix must be 100");
        b.put("mix", m("EASY", 50, "MEDIUM", 40, "HARD", 10));
        Map<String, Object> made = api.blueprints.create(r, b);
        eq(10, made.get("totalMarks"), "marks");
        b.put("sections", List.of(m("type", "SHORT", "count", 3, "attempt", 4, "marksEach", 5)));
        eq(400, status(() -> api.blueprints.create(r, b)), "attempt <= count");
        b.put("sections", List.of(m("type", "SHORT", "count", 3, "attempt", 2, "marksEach", 5)));
        b.put("units", List.of(7));
        eq(400, status(() -> api.blueprints.create(r, b)), "unit must exist");
        b.put("units", List.of(1, 2, 3, 4, 5));
        @SuppressWarnings("unchecked") Map<String, Object> made2 = api.blueprints.create(r, b);
        eq(0, ((List<?>) made2.get("units")).size(), "all units stored as 'any'");
        Map<String, Object> check = api.blueprints.checkDraft(b);
        eq(true, check.get("ok"), "bank has enough");
        eq(403, status(() -> api.blueprints.update(s.user("ananya"), (String) made.get("id"), b)), "only owner or HOD edits");
    }

    @SuppressWarnings("unchecked")
    static void workflow() throws Exception {
        TestClock c = new TestClock();
        DataStore s = freshStore(c);
        ApiServer api = server(s);
        User r = s.user("rohan"), hod = s.user("meera");
        List<Object> made = api.papers.generate(r, m("blueprintId", s.blueprints.get(1).id, "sets", 2, "examDate", "2026-11-20", "seed", 99));
        eq(2, made.size(), "2 sets");
        String id = (String) ((Map<String, Object>) made.get(0)).get("id");
        Map<String, Object> p = api.papers.get(id);
        eq(1, ((List<?>) p.get("otherSets")).size(), "knows its other set");
        eq(400, status(() -> api.papers.approve(hod, id)), "draft cannot be approved directly");
        api.papers.submit(r, id);
        eq(400, status(() -> api.papers.updateHeader(r, id, m("title", "New"))), "locked while in review");
        eq(400, status(() -> api.papers.sendBack(hod, id, m("note", ""))), "send back needs a note");
        Map<String, Object> back = api.papers.sendBack(hod, id, m("note", "Please swap Q3"));
        eq("DRAFT", back.get("status").toString(), "back to draft");
        eq("Please swap Q3", back.get("reviewNote"), "note kept");
        api.papers.updateHeader(r, id, m("title", "Mid-Term OS (final)", "instructions", List.of("Answer all", ""), "durationMin", 90));
        api.papers.submit(r, id);
        api.papers.approve(hod, id);
        eq(400, status(() -> api.papers.release(hod, (String) ((Map<String, Object>) made.get(1)).get("id"))), "release needs approval");
        Paper paper = s.paper(id);
        Question first = s.question(paper.questionIds().get(0));
        int usedBefore = first.timesUsed;
        api.papers.release(hod, id);
        eq(PaperStatus.RELEASED, paper.status, "released");
        eq(usedBefore + 1, first.timesUsed, "usage counted");
        eq(s.today().toString(), first.lastUsedOn, "last used today");
        eq("Mid-Term OS (final)", paper.title, "title saved");
        eq(1, paper.instructions.size(), "empty instruction lines dropped");
        // the paper is a snapshot: editing the bank later does not change it
        String oldText = paper.sections.get(0).items.get(0).text;
        api.bank.update(hod, paper.sections.get(0).items.get(0).questionId, m("text", oldText + " (new wording)"));
        eq(oldText, paper.sections.get(0).items.get(0).text, "released paper unchanged");
        String txt = api.papers.asText(id, true);
        check(txt.contains("ANSWER KEY") && txt.contains("Answer:"), "answer key text");
        check(api.papers.asText(id, false).contains("Maximum Marks: 30"), "paper text");
    }

    static void swapMove() throws Exception {
        DataStore s = freshStore(new TestClock());
        ApiServer api = server(s);
        User r = s.user("rohan");
        Paper draft = s.papers.stream().filter(p -> p.status == PaperStatus.DRAFT).findFirst().orElseThrow();
        User owner = s.user(draft.createdBy);
        eq(403, status(() -> api.papers.swap(r, draft.id, m("section", 0, "item", 0))), "not the owner");
        String before = draft.sections.get(0).items.get(0).questionId;
        api.papers.swap(owner, draft.id, m("section", 0, "item", 0));
        String after = draft.sections.get(0).items.get(0).questionId;
        check(!before.equals(after), "question changed");
        eq(draft.questionIds().size(), new HashSet<>(draft.questionIds()).size(), "still no repeats");
        List<Object> cands = api.papers.candidates(draft.id, 1, 0);
        check(!cands.isEmpty(), "candidates listed");
        String pick = (String) ((Map<?, ?>) cands.get(0)).get("id");
        api.papers.swap(owner, draft.id, m("section", 1, "item", 0, "questionId", pick));
        eq(pick, draft.sections.get(1).items.get(0).questionId, "chosen question used");
        String inPaper = draft.sections.get(0).items.get(1).questionId;
        eq(400, status(() -> api.papers.swap(owner, draft.id, m("section", 0, "item", 0, "questionId", inPaper))), "no repeats by hand");
        Question wrongKind = s.questions.stream().filter(q -> q.type == QType.LONG && q.subjectCode.equals(draft.subjectCode)).findFirst().orElseThrow();
        eq(400, status(() -> api.papers.swap(owner, draft.id, m("section", 0, "item", 0, "questionId", wrongKind.id))), "same kind only");
        String a = draft.sections.get(0).items.get(0).questionId, b = draft.sections.get(0).items.get(1).questionId;
        api.papers.move(owner, draft.id, m("section", 0, "item", 0, "dir", 1));
        eq(b, draft.sections.get(0).items.get(0).questionId, "moved down");
        eq(a, draft.sections.get(0).items.get(1).questionId, "moved up");
        eq(400, status(() -> api.papers.move(owner, draft.id, m("section", 0, "item", 0, "dir", -1))), "cannot move above first");
        // swaps stay inside the blueprint's units (OS mid-term = units 1-3)
        List<Object> made = api.papers.generate(owner, m("blueprintId", "B-002", "seed", 4));
        String mid = (String) ((Map<?, ?>) made.get(0)).get("id");
        for (int k = 0; k < 15; k++) api.papers.swap(owner, mid, m("section", 0, "item", k % 10));
        for (Paper.PSection ps : s.paper(mid).sections) for (Paper.Item it : ps.items) check(it.unit <= 3, "unit " + it.unit + " outside blueprint");
        for (Object c : api.papers.candidates(mid, 1, 0)) check(((Number) ((Map<?, ?>) c).get("unit")).intValue() <= 3, "candidate outside blueprint");
        List<String> old = new ArrayList<>(draft.questionIds());
        api.papers.regenerate(owner, draft.id);
        check(!old.equals(draft.questionIds()), "regenerated");
    }

    static void deleteRules() throws Exception {
        DataStore s = freshStore(new TestClock());
        ApiServer api = server(s);
        User hod = s.user("meera"), admin = s.user("admin");
        Paper released = s.papers.stream().filter(p -> p.status == PaperStatus.RELEASED).findFirst().orElseThrow();
        eq(400, status(() -> api.papers.delete(admin, released.id)), "released kept");
        String used = released.questionIds().get(0);
        eq(400, status(() -> api.bank.delete(hod, used)), "question in a paper cannot be deleted");
        api.bank.setArchived(hod, used, true);
        check(s.question(used).archived, "archived instead");
        check(PaperGenerator.pool(s.blueprints.get(1), s.questions).stream().noneMatch(q -> q.id.equals(used)), "archived not used");
        Question unused = s.questions.stream().filter(q -> !idsIn(s).contains(q.id)).findFirst().orElseThrow();
        api.bank.delete(hod, unused.id);
        check(s.question(unused.id) == null, "deleted");
        Paper draft = s.papers.stream().filter(p -> p.status == PaperStatus.DRAFT).findFirst().orElseThrow();
        api.papers.delete(s.user(draft.createdBy), draft.id);
        check(s.paper(draft.id) == null, "draft deleted");
    }

    static Set<String> idsIn(DataStore s) {
        Set<String> ids = new HashSet<>();
        for (Paper p : s.papers) ids.addAll(p.questionIds());
        return ids;
    }

    static void subjects() throws Exception {
        DataStore s = freshStore(new TestClock());
        ApiServer api = server(s);
        User hod = s.user("meera");
        eq(400, status(() -> api.bank.saveSubject(hod, "CSIT135", m("name", "OS", "semester", 2, "units", List.of("One", "Two")))), "units 3-5 have questions");
        api.bank.saveSubject(hod, null, m("code", "csit300", "name", "Cloud Computing", "course", "B.Sc. IT", "semester", 5, "units", List.of("Basics", "Virtualisation")));
        eq("CSIT300", s.subject("csit300").code, "code upper case");
        eq(409, status(() -> api.bank.saveSubject(hod, null, m("code", "CSIT300", "name", "X", "semester", 1, "units", List.of("a")))), "code unique");
        eq(400, status(() -> api.bank.deleteSubject(hod, "CSIT135")), "subject with questions kept");
        api.bank.deleteSubject(hod, "CSIT300");
        check(s.subject("CSIT300") == null, "empty subject deleted");
    }

    static void persistence() throws Exception {
        TestClock c = new TestClock();
        DataStore s = freshStore(c);
        ApiServer api = server(s);
        api.bank.create(s.user("rohan"), m("subjectCode", "CSIT124", "unit", 1, "type", "SHORT", "difficulty", "HARD", "bloom", "ANALYZE",
                "marks", 5, "text", "Why is String immutable in Java?", "answer", "Security, caching, thread safety", "tags", "strings, memory"));
        DataStore again = new DataStore(s.dir, c);
        check(!again.load(), "not demo again");
        eq(s.questions.size(), again.questions.size(), "questions");
        eq(s.papers.size(), again.papers.size(), "papers");
        Question last = again.questions.get(again.questions.size() - 1);
        eq(List.of("strings", "memory"), last.tags, "tags");
        Paper p0 = s.papers.get(1), p1 = again.paper(p0.id);
        eq(p0.questionIds(), p1.questionIds(), "paper questions");
        eq(p0.totalMarks(), p1.totalMarks(), "paper marks");
        eq(Json.write(p0.toJson()), Json.write(p1.toJson()), "paper identical");
        check(!Files.exists(s.dir.resolve("questions.json.tmp")), "no temp files left");
        String next = again.newQuestionId();
        check(again.question(next) == null, "new ids do not clash");
    }

    // ------------------------------------------------------------------ HTTP

    record Res(int code, String body, Map<String, List<String>> headers) {
        String header(String name) {
            for (var e : headers.entrySet()) if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue().get(0);
            return null;
        }
    }

    static Res call(int port, String method, String path, String token, String json, String origin) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + path).toURL().openConnection();
        c.setRequestMethod(method);
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        if (origin != null) c.setRequestProperty("Origin", origin);
        if (json != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        var in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        return new Res(code, body, c.getHeaderFields());
    }

    @SuppressWarnings("unchecked")
    static void http() throws Exception {
        DataStore s = freshStore(new TestClock());
        ApiServer api = server(s);
        api.start("127.0.0.1", 0);
        int port = api.port();
        try {
            Res home = call(port, "GET", "/", null, null, null);
            eq(200, home.code, "home page");
            check(home.header("Content-Security-Policy").contains("script-src 'self'"), "CSP header");
            eq(404, call(port, "GET", "/static/../../etc/passwd", null, null, null).code, "no path tricks");
            eq(404, call(port, "GET", "/static/%2e%2e/secret", null, null, null).code, "no encoded path tricks");
            eq(401, call(port, "GET", "/api/questions", null, null, null).code, "needs sign in");
            Res login = call(port, "POST", "/api/auth/login", null, "{\"username\":\"rohan\",\"password\":\"faculty123\"}", null);
            eq(200, login.code, "login");
            String token = (String) Json.parseObject(login.body).get("token");
            Map<String, Object> list = Json.parseObject(call(port, "GET", "/api/questions?subject=CSIT135&type=MCQ&limit=5", token, null, null).body);
            eq(5, ((List<?>) list.get("items")).size(), "paged list");
            check(((Number) list.get("total")).intValue() > 20, "total count");
            eq(403, call(port, "POST", "/api/papers/generate", token, "{\"blueprintId\":\"B-002\"}", "http://evil.example").code, "other website blocked");
            Res gen = call(port, "POST", "/api/papers/generate", token, "{\"blueprintId\":\"B-002\",\"seed\":5}", "http://127.0.0.1:" + port);
            eq(200, gen.code, "generate: " + gen.body);
            String pid = (String) ((Map<String, Object>) ((List<Object>) Json.parse(gen.body)).get(0)).get("id");
            Res txt = call(port, "GET", "/api/papers/" + pid + "/export.txt?key=1", token, null, null);
            eq(200, txt.code, "text export");
            check(txt.header("Content-Disposition").contains("answer-key"), "download name");
            eq(400, call(port, "POST", "/api/questions", token, "{bad json", null).code, "bad JSON");
            eq(405, call(port, "DELETE", "/api/dashboard", token, null, null).code, "wrong method");
            eq(404, call(port, "GET", "/api/nothing", token, null, null).code, "unknown");
            eq(403, call(port, "PUT", "/api/settings", token, "{\"institute\":\"X\"}", null).code, "faculty cannot change settings");
            eq(200, call(port, "GET", "/api/meta", token, null, null).code, "meta");
            Res csv = call(port, "GET", "/api/questions/export.csv?subject=CSIT213", token, null, null);
            check(csv.body.startsWith("\uFEFFsubject,unit,type"), "csv export");
            StringBuilder big = new StringBuilder("{\"csv\":\"");
            while (big.length() < 1_100_000) big.append("aaaaaaaaaa");
            int bigCode;
            try { bigCode = call(port, "POST", "/api/questions/import", token, big.append("\"}").toString(), null).code; }
            catch (java.net.SocketException e) { bigCode = 413; } // the server may close the line before the upload ends
            eq(413, bigCode, "body limit");
            eq(200, call(port, "POST", "/api/auth/logout", token, "{}", null).code, "logout");
            eq(401, call(port, "GET", "/api/dashboard", token, null, null).code, "token ended");
        } finally {
            api.stop();
        }
    }
}
