package com.cecil.kaushal;

import com.cecil.kaushal.core.Hungarian;
import com.cecil.kaushal.core.Levels;
import com.cecil.kaushal.core.Readiness;
import com.cecil.kaushal.core.SkillGraph;
import com.cecil.kaushal.core.SkillQuery;
import com.cecil.kaushal.core.Stats;
import com.cecil.kaushal.model.Cert;
import com.cecil.kaushal.model.Claim;
import com.cecil.kaushal.model.ClaimStatus;
import com.cecil.kaushal.model.Goal;
import com.cecil.kaushal.model.JobRole;
import com.cecil.kaushal.model.Project;
import com.cecil.kaushal.model.Req;
import com.cecil.kaushal.model.Skill;
import com.cecil.kaushal.model.Snapshot;
import com.cecil.kaushal.model.User;
import com.cecil.kaushal.service.Problem;
import com.cecil.kaushal.store.DataStore;
import com.cecil.kaushal.store.SeedData;
import com.cecil.kaushal.util.Clock;
import com.cecil.kaushal.util.Csv;
import com.cecil.kaushal.util.Json;
import com.cecil.kaushal.util.Passwords;
import com.cecil.kaushal.web.ApiServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * All the tests, with no JUnit download needed: run  run-tests.bat  or  ./run-tests.sh.
 * The clock is frozen at Monday 16 November 2026, 08:00 UTC (13:30 in India).
 */
public final class AllTests {
    private static int passed, failed;
    private static final List<String> failures = new ArrayList<>();
    static final Instant START = Instant.parse("2026-11-16T08:00:00Z");
    static final long H = 3_600_000L, D = 24 * H;

    interface Test { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        run("JSON reads and writes", AllTests::json);
        run("Passwords are hashed with a salt", AllTests::passwords);
        run("CSV: quotes, commas, new lines and the formula guard", AllTests::csv);
        run("Skill decay: one level lower per half-life without use", AllTests::decay);
        run("Readiness % and must-have skills", AllTests::readiness);
        run("Prerequisites: loops refused, safe order, learning path", AllTests::graph);
        run("Hungarian algorithm = brute force (400 random cases)", AllTests::hungarianRandom);
        run("Hungarian fills more slots than greedy", AllTests::hungarianVsGreedy);
        run("Search language: and/or/not, brackets, levels, names", AllTests::query);
        run("Search language: mistakes and 'did you mean'", AllTests::queryErrors);
        run("Claim -> verify: four eyes, reasons, assessors", AllTests::verify);
        run("A new claim waits; the old verified level still counts", AllTests::reclaim);
        run("Send back needs a reason; evidence for level 4+", AllTests::sendBack);
        run("Endorsements: not yourself, once, weekly limit, weight", AllTests::endorse);
        run("Certificates: valid/expiring/expired and reminders once", AllTests::certs);
        run("Learning plan: gaps + prerequisites, finish -> claim -> done", AllTests::goals);
        run("Staffing: free time, must-haves, stretch roles", AllTests::staffing);
        run("Suggest a team for a project", AllTests::suggest);
        run("Catalogue: unique names and synonyms, retire rules", AllTests::catalogue);
        run("People: no loops in the reporting line", AllTests::people);
        run("CSV import: dry run first, then import", AllTests::importCsv);
        run("Privacy: others see only verified skills", AllTests::privacy);
        run("Bus factor and team matrix", AllTests::busFactor);
        run("Monthly snapshot and trend", AllTests::snapshot);
        run("Sign-in: wrong passwords lock the account", AllTests::lockout);
        run("16 threads at once: no double endorsement, limit holds", AllTests::threads);
        run("Web server: CSRF, cookies, JSON only, no path tricks", AllTests::web);
        run("Demo company: built through the rules, none broken", AllTests::demo);
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

    static void refused(Runnable r, int status, String what) {
        try {
            r.run();
        } catch (Problem p) {
            if (status != 0 && p.status != status) throw new AssertionError(what + ": status " + p.status + " (" + p.getMessage() + ")");
            return;
        }
        throw new AssertionError(what + ": was allowed");
    }

    // ------------------------------------------------------------------ a small company for the tests

    static final class W {
        final DataStore store = new DataStore(null);
        final ApiServer.Parts p = App.parts(store);
        final User hr, boss, lead, dev, dev2, expert, out;
        final Map<String, String> s = new HashMap<>();
        String cat;

        W() {
            hr = new User();
            hr.id = "U-0"; hr.username = "hr"; hr.name = "Hema Roy"; hr.role = com.cecil.kaushal.model.Role.ADMIN;
            hr.passwordHash = Passwords.hash("admin123");
            store.users.put(hr.id, hr);
            cat = p.catalog().addCategory(hr, Json.obj("name", "Tech", "group", "lang", "halfLife", 12L)).id;
            for (String[] k : new String[][] { { "Jv", "Java", "core java" }, { "Sb", "Spring Boot", "spring" }, { "Dk", "Docker", "" },
                    { "K8s", "Kubernetes", "k8s" }, { "Lx", "Linux", "" }, { "Ml", "Machine learning", "ml" }, { "Py", "Python", "" },
                    { "Aw", "AWS", "amazon web services" }, { "Az", "Azure", "" } }) {
                List<Object> syn = k[2].isEmpty() ? List.of() : List.of(k[2]);
                s.put(k[0], p.catalog().saveSkill(hr, null, Json.obj("symbol", k[0], "name", k[1], "categoryId", cat, "synonyms", syn)).id);
            }
            p.catalog().saveSkill(hr, s.get("K8s"), Json.obj("prereqs", Json.obj(s.get("Dk"), 2L, s.get("Lx"), 2L)));
            p.catalog().saveSkill(hr, s.get("Sb"), Json.obj("prereqs", Json.obj(s.get("Jv"), 2L)));
            boss = add("boss", "Bala Oraon", "MANAGER", null);
            lead = add("lead", "Lata Minz", "MANAGER", boss.id);
            dev = add("dev", "Dev Kumar", "EMPLOYEE", lead.id);
            dev2 = add("dev2", "Dia Sen", "EMPLOYEE", lead.id);
            expert = add("expert", "Ekta Rao", "EMPLOYEE", boss.id);
            out = add("out", "Om Das", "EMPLOYEE", boss.id);
        }

        User add(String username, String name, String role, String manager) {
            return p.admin().saveUser(hr, null, Json.obj("username", username, "name", name, "role", role, "managerId", manager,
                    "password", "member123", "dept", "Engineering"));
        }

        /** Claim and have the manager (or HR) verify. */
        Claim has(User u, String sym, int level) {
            Claim c = p.profiles().claim(u, Json.obj("skillId", s.get(sym), "level", (long) level, "evidence", "Did real work with it on a project"));
            User v = u.managerId != null ? store.users.get(u.managerId) : hr;
            return p.profiles().verify(v, c.id, level, null);
        }

        int level(User u, String sym) { return store.levels(u.id, false, Clock.today()).getOrDefault(s.get(sym), 0); }
    }

    // ------------------------------------------------------------------ tests

    static void json() {
        Map<String, Object> m = Json.obj("a", 1L, "b", List.of("x", true), "c", null, "d", "<script>\"\n");
        String s = Json.write(m);
        check(!s.contains("<"), "< is escaped");
        eq(m, Json.parse(s), "round trip");
        eq(3.5, Json.parse("3.5"), "fraction");
        refused(() -> { try { Json.parse("{\"a\":}"); } catch (IllegalArgumentException e) { throw Problem.bad("x"); } }, 400, "bad JSON");
    }

    static void passwords() {
        String a = Passwords.hash("member123"), b = Passwords.hash("member123");
        check(!a.equals(b), "salted");
        check(Passwords.verify("member123", a) && !Passwords.verify("member124", a), "verify");
        check(Passwords.problem("short") != null && Passwords.problem("longenough1") == null, "rules");
    }

    static void csv() {
        List<List<String>> rows = List.of(List.of("a,b", "say \"hi\"", "two\nlines"), List.of("=SUM(A1)", "-3", "@x"));
        String text = Csv.write(rows);
        List<List<String>> back = Csv.parse(text);
        eq(rows.get(0), back.get(0), "quoted fields");
        eq("'=SUM(A1)", back.get(1).get(0), "formula guarded");
        eq("-3", back.get(1).get(1), "negative number is fine");
        eq(2, Csv.parse("﻿u,s\r\nx,y\r\n\r\n").size(), "BOM and blank lines");
    }

    static void decay() {
        LocalDate today = LocalDate.of(2026, 11, 16);
        eq(4, Levels.effective(4, "2026-11", 18, today), "used this month");
        eq(4, Levels.effective(4, "2025-06", 18, today), "17 months: still 4");
        eq(3, Levels.effective(4, "2025-05", 18, today), "18 months: one lower");
        eq(2, Levels.effective(4, "2023-11", 18, today), "36 months: two lower");
        eq(1, Levels.effective(2, "2015-01", 12, today), "never below 1");
        eq(5, Levels.effective(5, "2010-01", 0, today), "half-life 0 = never fades");
        eq(0, Levels.effective(0, "2026-11", 12, today), "not verified stays 0");
        check(Levels.fading(4, "2025-09", 18, today), "14 of 18 months: fading soon");
        check(!Levels.fading(4, "2026-06", 18, today), "5 months: fine");
        check(Levels.fading(4, "2025-05", 18, today), "already lower: fading");
        eq(5, Levels.parse("Expert"), "level names");
        eq(-1, Levels.parse("guru"), "unknown");
        eq(0, Levels.monthsSince("2027-01", today), "future month counts 0");
    }

    static void readiness() {
        Readiness.Result r = Readiness.of(Map.of("j", 3, "s", 3), List.of(new Req("j", 4, true, 3), new Req("s", 3, false, 1)));
        eq(81, r.percent(), "(3 x 0.75 + 1) / 4");
        eq(1, r.mustMissing(), "Java 4 is a must");
        check(!r.ready(), "not ready with a must missing");
        Readiness.Result ok = Readiness.of(Map.of("j", 4, "s", 1), List.of(new Req("j", 4, true, 3), new Req("s", 3, false, 1)));
        eq(83, ok.percent(), "nice-to-have gap");
        check(ok.ready(), "ready: musts met and 80 %+");
        eq("s", ok.gaps().get(0).skillId(), "gap listed");
        Readiness.Result order = Readiness.of(Map.of(), List.of(new Req("a", 2, false, 1), new Req("b", 2, true, 1), new Req("c", 5, false, 3)));
        eq("b", order.gaps().get(0).skillId(), "must-haves first");
        eq("c", order.gaps().get(1).skillId(), "then the biggest gap");
        eq(100, Readiness.of(Map.of(), List.of()).percent(), "nothing needed = 100 %");
    }

    static void graph() {
        W w = new W();
        // Docker needs Kubernetes? Kubernetes already needs Docker -> loop
        refused(() -> w.p.catalog().saveSkill(w.hr, w.s.get("Dk"), Json.obj("prereqs", Json.obj(w.s.get("K8s"), 1L))), 409, "direct loop");
        w.p.catalog().saveSkill(w.hr, w.s.get("Lx"), Json.obj("prereqs", Json.obj(w.s.get("Py"), 1L)));
        refused(() -> w.p.catalog().saveSkill(w.hr, w.s.get("Py"), Json.obj("prereqs", Json.obj(w.s.get("K8s"), 1L))), 409, "loop through 2 skills");
        refused(() -> w.p.catalog().saveSkill(w.hr, w.s.get("Py"), Json.obj("prereqs", Json.obj(w.s.get("Py"), 1L))), 409, "itself");
        check(w.store.skills.get(w.s.get("Py")).prereqs.isEmpty(), "refused change is not kept");
        List<String> order = SkillGraph.order(w.store.skills);
        check(order.indexOf(w.s.get("Py")) < order.indexOf(w.s.get("Lx")) && order.indexOf(w.s.get("Lx")) < order.indexOf(w.s.get("K8s"))
                && order.indexOf(w.s.get("Dk")) < order.indexOf(w.s.get("K8s")), "prerequisites first");
        Map<String, Integer> have = Map.of(w.s.get("Dk"), 3);
        LinkedHashMap<String, Integer> path = SkillGraph.learningPath(w.store.skills, have, Map.of(w.s.get("K8s"), 3));
        eq(List.of(w.s.get("Py"), w.s.get("Lx"), w.s.get("K8s")), new ArrayList<>(path.keySet()), "Python, Linux, then Kubernetes (Docker known)");
        eq(2, path.get(w.s.get("Lx")), "Linux to the level Kubernetes needs");
        eq(1, path.get(w.s.get("Py")), "Python to the level Linux needs");
    }

    static void hungarianRandom() {
        Random r = new Random(7);
        for (int t = 0; t < 400; t++) {
            int n = 1 + r.nextInt(5), m = n + r.nextInt(3);
            double[][] c = new double[n][m];
            for (double[] row : c) for (int j = 0; j < m; j++) row[j] = r.nextInt(20);
            int[] pick = Hungarian.solve(c);
            Set<Integer> cols = new HashSet<>();
            for (int x : pick) check(cols.add(x), "no column twice");
            eq(brute(c, 0, new boolean[m]), Hungarian.total(c, pick), "minimal total, case " + t);
        }
    }

    static double brute(double[][] c, int row, boolean[] used) {
        if (row == c.length) return 0;
        double best = Double.MAX_VALUE;
        for (int j = 0; j < c[0].length; j++) {
            if (used[j]) continue;
            used[j] = true;
            best = Math.min(best, c[row][j] + brute(c, row + 1, used));
            used[j] = false;
        }
        return best;
    }

    static void hungarianVsGreedy() {
        // slot 0: A fits 100, B fits 90 · slot 1: only A fits (80). Greedy gives A to slot 0 and leaves slot 1 empty.
        double no = 1_000_000, empty = 1000;
        double[][] c = { { 0, 10, empty, empty }, { 20, no, empty, empty } };
        int[] pick = Hungarian.solve(c);
        eq(1, pick[0], "B takes slot 0");
        eq(0, pick[1], "A takes slot 1");
        eq(30.0, Hungarian.total(c, pick), "total cost 30 = fits 90 + 80");
    }

    static void query() {
        W w = new W();
        SkillQuery.Names n = w.p.views().names();
        String j = w.s.get("Jv"), a = w.s.get("Aw"), z = w.s.get("Az"), m = w.s.get("Ml");
        SkillQuery.Node q = SkillQuery.parse("java >= 3 and (aws or azure >= 2) and not \"machine learning\"", n);
        check(q.test(Map.of(j, 3, a, 1)), "Java 3 + AWS");
        check(q.test(Map.of(j, 4, z, 2)), "Java 4 + Azure 2");
        check(!q.test(Map.of(j, 4, z, 1)), "Azure 1 is not enough");
        check(!q.test(Map.of(j, 3, a, 2, m, 1)), "not ML");
        check(!q.test(Map.of(a, 5)), "no Java");
        check(SkillQuery.parse("java aws:2", n).test(Map.of(j, 1, a, 2)), "side by side = and; ':' = '>='");
        check(SkillQuery.parse("java or aws and azure", n).test(Map.of(j, 1)), "and binds before or");
        check(SkillQuery.parse("machine learning > 2", n).test(Map.of(m, 3)), "two-word name without quotes");
        check(!SkillQuery.parse("ml > 2", n).test(Map.of(m, 2)), "synonym, and > is strict");
        check(SkillQuery.parse("k8s", n).test(Map.of(w.s.get("K8s"), 1)), "symbol works");
        check(SkillQuery.parse("python < 3", n).test(Map.of()), "< counts people without the skill");
        check(SkillQuery.parse("java >= expert", n).test(Map.of(j, 5)), "level names");
        check(SkillQuery.parse("JAVA = 2 || aws", n).test(Map.of(j, 2)), "case, = and ||");
        eq(Set.of(j, a), SkillQuery.skillsOf(SkillQuery.parse("java and aws and not azure", n)), "skills asked for");
    }

    static void queryErrors() {
        W w = new W();
        SkillQuery.Names n = w.p.views().names();
        for (String bad : new String[] { "", "java >=", "(java", "java >= 7", "and java", "\"java", "java )" }) {
            try { SkillQuery.parse(bad, n); throw new AssertionError("accepted: " + bad); } catch (SkillQuery.Error ok) { /* good */ }
        }
        try {
            SkillQuery.parse("kubernets >= 2", n);
            throw new AssertionError("typo accepted");
        } catch (SkillQuery.Error e) {
            check(e.suggestions.contains("Kubernetes"), "did you mean Kubernetes");
        }
        refused(() -> w.p.views().search(w.dev, "pythn", false), 400, "search shows the problem");
    }

    static void verify() {
        W w = new W();
        Claim c = w.p.profiles().claim(w.dev, Json.obj("skillId", w.s.get("Jv"), "level", 3L));
        eq(0, w.level(w.dev, "Jv"), "a claim does not count yet");
        refused(() -> w.p.profiles().verify(w.dev, c.id, 3, null), 403, "own claim");
        refused(() -> w.p.profiles().verify(w.dev2, c.id, 3, null), 403, "a colleague who is not an assessor");
        refused(() -> w.p.profiles().verify(w.lead, c.id, 2, ""), 400, "a different level needs a reason");
        w.p.profiles().verify(w.boss, c.id, 2, "Level 3 after the next release");
        eq(2, w.level(w.dev, "Jv"), "the manager's manager may check");
        eq(ClaimStatus.VERIFIED, c.status, "verified");
        check(w.store.notes.values().stream().anyMatch(x -> x.userId.equals(w.dev.id) && x.text.contains("you claimed 3")), "dev is told");
        refused(() -> w.p.profiles().verify(w.lead, c.id, 2, null), 409, "not waiting any more");
        // an assessor (level 4) may confirm up to 4, not 5
        w.has(w.expert, "Py", 4);
        Claim p5 = w.p.profiles().claim(w.dev2, Json.obj("skillId", w.s.get("Py"), "level", 5L, "evidence", "Wrote our ML platform in Python"));
        refused(() -> w.p.profiles().verify(w.expert, p5.id, 5, null), 403, "assessor above own level");
        w.p.profiles().verify(w.expert, p5.id, 4, "Level 5 needs a manager's check");
        eq(4, w.level(w.dev2, "Py"), "assessor verified");
        eq(0, w.p.profiles().queue(w.lead).size(), "nothing left to check");
        Claim k = w.p.profiles().claim(w.dev, Json.obj("skillId", w.s.get("K8s"), "level", 2L));
        eq(1, w.p.profiles().queue(w.lead).size(), "the manager sees the new claim");
        eq(0, w.p.profiles().queue(w.out).size(), "a colleague does not");
        eq(k.id, ((Claim) w.p.profiles().queue(w.hr).get(0).get("claim")).id, "HR sees it too");
    }

    static void reclaim() {
        W w = new W();
        w.has(w.dev, "Jv", 3);
        Claim c = w.p.profiles().claim(w.dev, Json.obj("skillId", w.s.get("Jv"), "level", 4L, "evidence", "Led the Java 21 upgrade of three services"));
        eq(ClaimStatus.CLAIMED, c.status, "waits again");
        eq(3, w.level(w.dev, "Jv"), "verified 3 still counts while 4 waits");
        eq(4, w.store.levels(w.dev.id, true, Clock.today()).get(w.s.get("Jv")), "with 'include claimed' it shows 4");
        Claim same = w.p.profiles().claim(w.dev2, Json.obj("skillId", w.s.get("Jv"), "level", 2L));
        w.p.profiles().verify(w.lead, same.id, 2, null);
        w.p.profiles().claim(w.dev2, Json.obj("skillId", w.s.get("Jv"), "level", 2L, "lastUsed", "2025-01"));
        eq(ClaimStatus.VERIFIED, same.status, "same level, new 'last used': no new check");
        w.p.profiles().used(w.dev2, same.id);
        eq(YearMonth.from(Clock.today()).toString(), same.lastUsed, "'used this month'");
        refused(() -> w.p.profiles().claim(w.dev, Json.obj("skillId", w.s.get("Jv"), "level", 3L, "lastUsed", "2030-01")), 400, "future month");
    }

    static void sendBack() {
        W w = new W();
        refused(() -> w.p.profiles().claim(w.dev, Json.obj("skillId", w.s.get("Dk"), "level", 4L, "evidence", "yes")), 400, "level 4 needs an example");
        Claim c = w.p.profiles().claim(w.dev, Json.obj("skillId", w.s.get("Dk"), "level", 4L, "evidence", "Containerised all 12 services"));
        refused(() -> w.p.profiles().sendBack(w.lead, c.id, ""), 400, "say what is missing");
        w.p.profiles().sendBack(w.lead, c.id, "Which registry? Add the pipeline link.");
        eq(ClaimStatus.RETURNED, c.status, "returned");
        refused(() -> w.p.profiles().verify(w.lead, c.id, 4, null), 409, "returned is not waiting");
        w.p.profiles().claim(w.dev, Json.obj("skillId", w.s.get("Dk"), "level", 4L, "evidence", "Containerised all 12 services, link in wiki"));
        eq(ClaimStatus.CLAIMED, c.status, "claimed again");
    }

    static void endorse() {
        W w = new W();
        w.has(w.dev, "Jv", 3);
        refused(() -> w.p.profiles().endorse(w.dev, w.dev.id, w.s.get("Jv"), null), 400, "yourself");
        refused(() -> w.p.profiles().endorse(w.dev2, w.dev.id, w.s.get("Py"), null), 400, "a skill they did not list");
        var e = w.p.profiles().endorse(w.dev2, w.dev.id, w.s.get("Jv"), "Great reviews");
        refused(() -> w.p.profiles().endorse(w.dev2, w.dev.id, w.s.get("Jv"), null), 409, "twice");
        eq(1.0, com.cecil.kaushal.service.ProfileService.weight(w.store, e, Clock.today()), "from someone without Java: weight 1");
        w.has(w.expert, "Jv", 5);
        var e2 = w.p.profiles().endorse(w.expert, w.dev.id, w.s.get("Jv"), null);
        eq(3.5, com.cecil.kaushal.service.ProfileService.weight(w.store, e2, Clock.today()), "from an expert: 3.5");
        // weekly limit
        User[] many = new User[16];
        for (int i = 0; i < 16; i++) { many[i] = w.add("per" + i, "Person " + i, "EMPLOYEE", w.boss.id); w.has(many[i], "Lx", 2); }
        for (int i = 0; i < 15; i++) w.p.profiles().endorse(w.out, many[i].id, w.s.get("Lx"), null);
        refused(() -> w.p.profiles().endorse(w.out, many[15].id, w.s.get("Lx"), null), 429, "16th in a week");
        Clock.advanceMillis(8 * D);
        w.p.profiles().endorse(w.out, many[15].id, w.s.get("Lx"), null);
    }

    static void certs() {
        W w = new W();
        LocalDate t = Clock.today();
        Cert a = w.p.profiles().saveCert(w.dev, null, Json.obj("name", "AWS SA", "skillId", w.s.get("Aw"), "issued", t.minusYears(2).toString(), "expires", t.plusDays(45).toString()));
        Cert b = w.p.profiles().saveCert(w.dev, null, Json.obj("name", "CKA", "issued", t.minusYears(1).toString(), "expires", t.plusDays(200).toString()));
        Cert c = w.p.profiles().saveCert(w.dev, null, Json.obj("name", "OSCP", "issued", t.minusYears(1).toString()));
        eq("EXPIRING", a.status(t), "45 days left");
        eq("VALID", b.status(t), "200 days left");
        eq("VALID", c.status(t), "never expires");
        refused(() -> w.p.profiles().saveCert(w.dev, null, Json.obj("name", "X", "issued", t.plusDays(1).toString())), 400, "issued in the future");
        refused(() -> w.p.profiles().saveCert(w.dev, null, Json.obj("name", "X", "issued", t.toString(), "expires", t.minusDays(1).toString())), 400, "expires before issued");
        refused(() -> w.p.profiles().saveCert(w.dev2, a.id, Json.obj("name", "Mine now")), 403, "someone else's");
        eq(1, w.p.profiles().certReminders(), "60-day reminder");
        eq(0, w.p.profiles().certReminders(), "only once");
        Clock.advanceMillis(20 * D);
        eq(1, w.p.profiles().certReminders(), "30-day reminder");
        Clock.advanceMillis(30 * D);
        eq("EXPIRED", a.status(Clock.today()), "expired");
        eq(1, w.p.profiles().certReminders(), "expired notice");
        check(w.store.notes.values().stream().anyMatch(n -> n.userId.equals(w.lead.id) && n.text.contains("expired")), "manager told too");
        w.p.profiles().saveCert(w.dev, a.id, Json.obj("expires", Clock.today().plusYears(3).toString()));
        eq("VALID", a.status(Clock.today()), "renewed");
        check(a.reminded.isEmpty(), "reminders start again");
    }

    static void goals() {
        W w = new W();
        w.has(w.dev, "Dk", 3);
        JobRole r = w.p.growth().saveRole(w.hr, null, Json.obj("name", "Platform Engineer", "ladder", "Platform", "step", 1L,
                "reqs", List.of(Json.obj("skillId", w.s.get("K8s"), "level", 3L, "must", true, "weight", 3L),
                        Json.obj("skillId", w.s.get("Dk"), "level", 3L, "must", true, "weight", 2L))));
        eq(40, w.p.growth().readiness(w.dev.id, r).percent(), "(0 x 3 + 1 x 2) / 5");
        List<Goal> plan = w.p.growth().planForRole(w.dev, w.dev.id, r.id);
        eq(2, plan.size(), "Linux (needed first) and Kubernetes");
        eq(w.s.get("Lx"), plan.get(0).skillId, "prerequisite first");
        eq(w.s.get("K8s"), plan.get(1).skillId, "then the gap");
        check(LocalDate.parse(plan.get(0).due).isBefore(LocalDate.parse(plan.get(1).due)), "due dates one after another");
        eq(0, w.p.growth().planForRole(w.dev, w.dev.id, r.id).size(), "a second plan adds nothing");
        refused(() -> w.p.growth().planForRole(w.dev2, w.dev.id, r.id), 403, "not for a colleague");
        Goal lx = plan.get(0);
        w.p.growth().step(w.dev, lx.id, 0, true);
        eq(1, lx.stepsDone(), "step ticked");
        refused(() -> w.p.growth().finish(w.lead, lx.id), 403, "only the person finishes");
        w.p.growth().finish(w.dev, lx.id);
        Claim c = w.store.claim(w.dev.id, w.s.get("Lx"));
        eq(ClaimStatus.CLAIMED, c.status, "finishing makes a claim");
        eq(2, c.claimed, "at the target level");
        w.p.profiles().verify(w.lead, c.id, 2, null);
        eq(2, w.level(w.dev, "Lx"), "verified");
        // a goal reached by a verification is closed automatically
        Goal g = w.p.growth().addGoal(w.lead, Json.obj("userId", w.dev.id, "skillId", w.s.get("Jv"), "target", 2L, "due", Clock.today().plusDays(30).toString()));
        check(w.store.notes.values().stream().anyMatch(n -> n.userId.equals(w.dev.id) && n.text.contains("learning goal")), "dev is told");
        w.has(w.dev, "Jv", 3);
        eq("DONE", g.status, "closed by the verification");
        refused(() -> w.p.growth().addGoal(w.dev, Json.obj("skillId", w.s.get("Dk"), "target", 3L, "due", Clock.today().plusDays(9).toString())), 400, "already there");
        refused(() -> w.p.growth().addGoal(w.dev, Json.obj("skillId", w.s.get("Py"), "target", 2L, "due", Clock.today().toString())), 400, "due in the past");
        w.p.growth().saveRole(w.hr, null, Json.obj("name", "Senior Platform Engineer", "ladder", "Platform", "step", 2L,
                "reqs", List.of(Json.obj("skillId", w.s.get("K8s"), "level", 4L, "must", true, "weight", 3L))));
        w.p.admin().saveUser(w.hr, w.dev.id, Json.obj("jobRoleId", r.id));
        eq("Senior Platform Engineer", w.p.growth().nextRole(w.dev).name, "next step on the ladder");
    }

    static Project project(W w, User owner, String status) {
        return w.p.staffing().saveProject(owner, null, Json.obj("name", "Apollo", "status", status,
                "start", Clock.today().toString(), "end", Clock.today().plusDays(90).toString()));
    }

    static Project slot(W w, User owner, Project p, String title, long pct, Object... reqs) {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < reqs.length; i += 3)
            list.add(Json.obj("skillId", w.s.get((String) reqs[i]), "level", ((Integer) reqs[i + 1]).longValue(), "must", reqs[i + 2], "weight", 1L));
        return w.p.staffing().saveSlot(owner, p.id, null, Json.obj("title", title, "pct", pct, "reqs", list));
    }

    static void staffing() {
        W w = new W();
        refused(() -> project(w, w.dev, "ACTIVE"), 403, "employees do not create projects");
        Project p = project(w, w.lead, "ACTIVE");
        slot(w, w.lead, p, "Backend", 60L, "Jv", 3, true, "Dk", 2, false);
        slot(w, w.lead, p, "Ops", 40L, "K8s", 2, true);
        w.has(w.dev, "Jv", 3);
        String s1 = p.slots.get(0).id, s2 = p.slots.get(1).id;
        refused(() -> w.p.staffing().assign(w.dev, p.id, s1, w.dev.id, false), 403, "only the owner staffs");
        w.p.staffing().assign(w.lead, p.id, s1, w.dev.id, false);
        eq(40, w.p.staffing().free(w.dev.id, null), "60 % used");
        try {
            w.p.staffing().assign(w.lead, p.id, s2, w.dev.id, false);
            throw new AssertionError("must-have missing was allowed");
        } catch (Problem pr) {
            eq(409, pr.status, "conflict");
            check(pr.getMessage().contains("stretch"), "offers a stretch role");
        }
        w.p.staffing().assign(w.lead, p.id, s2, w.dev.id, true);
        check(p.slot(s2).stretch, "marked as stretch");
        eq(0, w.p.staffing().free(w.dev.id, null), "60 + 40 = 100 %");
        Project q = project(w, w.lead, "PLANNED");
        slot(w, w.lead, q, "Java", 50L, "Jv", 2, true);
        refused(() -> w.p.staffing().assign(w.lead, q.id, q.slots.get(0).id, w.dev.id, false), 409, "dev has no free time");
        w.p.staffing().saveProject(w.lead, p.id, Json.obj("status", "DONE"));
        eq(100, w.p.staffing().free(w.dev.id, null), "a finished project frees the time");
        w.p.staffing().assign(w.lead, q.id, q.slots.get(0).id, w.dev.id, false);
        refused(() -> w.p.staffing().saveProject(w.lead, q.id, Json.obj("end", Clock.today().minusDays(1).toString())), 400, "end before start");
        refused(() -> w.p.staffing().saveSlot(w.lead, q.id, null, Json.obj("title", "Odd", "pct", 35L, "reqs", List.of())), 400, "10 % steps");
    }

    static void suggest() {
        W w = new W();
        // A (dev) fits both slots; B (dev2) fits only slot 1 a little worse. Best total: B -> slot 1, A -> slot 2.
        w.has(w.dev, "Jv", 4); w.has(w.dev, "Sb", 3); w.has(w.dev, "Py", 3);
        w.has(w.dev2, "Jv", 4); w.has(w.dev2, "Sb", 2);
        Project p = project(w, w.lead, "PLANNED");
        slot(w, w.lead, p, "Java lead", 50L, "Jv", 4, true, "Sb", 3, false);
        slot(w, w.lead, p, "Python", 50L, "Py", 3, true);
        Map<String, Object> s = w.p.staffing().suggest(w.lead, p.id);
        @SuppressWarnings("unchecked") List<Map<String, Object>> slots = (List<Map<String, Object>>) s.get("slots");
        @SuppressWarnings("unchecked") Map<String, Object> first = (Map<String, Object>) slots.get(0).get("pick");
        @SuppressWarnings("unchecked") Map<String, Object> second = (Map<String, Object>) slots.get(1).get("pick");
        eq(w.dev2.id, first.get("userId"), "Dia leads Java (fit 83 %)");
        eq(w.dev.id, second.get("userId"), "Dev takes Python - nobody else can");
        eq(2, s.get("filled"), "both filled");
        eq(1, s.get("greedyFilled"), "greedy would fill only one");
        // somebody busy elsewhere is not suggested
        Project busy = project(w, w.lead, "ACTIVE");
        slot(w, w.lead, busy, "Full time", 100L, "Py", 1, true);
        w.p.staffing().assign(w.lead, busy.id, busy.slots.get(0).id, w.dev.id, false);
        Map<String, Object> s2 = w.p.staffing().suggest(w.lead, p.id);
        @SuppressWarnings("unchecked") List<Map<String, Object>> sl2 = (List<Map<String, Object>>) s2.get("slots");
        check(sl2.get(1).get("pick") == null, "Python stays empty: Dev has no free time");
        refused(() -> w.p.staffing().suggest(w.dev, p.id), 403, "only the owner");
    }

    static void catalogue() {
        W w = new W();
        refused(() -> w.p.catalog().saveSkill(w.dev, null, Json.obj("name", "Rust", "symbol", "Rs", "categoryId", w.cat)), 403, "only HR");
        refused(() -> w.p.catalog().saveSkill(w.hr, null, Json.obj("name", "java", "symbol", "Jx", "categoryId", w.cat)), 409, "same name");
        refused(() -> w.p.catalog().saveSkill(w.hr, null, Json.obj("name", "Jakarta", "symbol", "Jv", "categoryId", w.cat)), 409, "same symbol");
        refused(() -> w.p.catalog().saveSkill(w.hr, null, Json.obj("name", "Jakarta", "symbol", "Jk", "categoryId", w.cat, "synonyms", List.of("Core Java"))), 409, "synonym taken");
        refused(() -> w.p.catalog().saveSkill(w.hr, null, Json.obj("name", "Rust", "symbol", "rust", "categoryId", w.cat)), 400, "symbol shape");
        Skill rust = w.p.catalog().saveSkill(w.hr, null, Json.obj("name", "Rust", "symbol", "Rs", "categoryId", w.cat, "synonyms", List.of("rustlang", "RUSTLANG")));
        eq(1, rust.synonyms.size(), "duplicate synonyms removed");
        eq(rust.id, w.store.skillByName("RustLang").id, "found by synonym");
        w.p.growth().saveRole(w.hr, null, Json.obj("name", "Systems dev", "reqs", List.of(Json.obj("skillId", rust.id, "level", 2L))));
        refused(() -> w.p.catalog().retire(w.hr, rust.id, false), 409, "a role still needs it");
        w.p.catalog().retire(w.hr, w.s.get("Az"), false);
        check(w.store.skillByName("Azure") == null, "retired skills are not found");
        refused(() -> w.p.profiles().claim(w.dev, Json.obj("skillId", w.s.get("Az"), "level", 1L)), 400, "or claimed");
    }

    static void people() {
        W w = new W();
        refused(() -> w.p.admin().saveUser(w.hr, w.boss.id, Json.obj("managerId", w.dev.id)), 409, "boss -> lead -> dev -> boss");
        refused(() -> w.p.admin().saveUser(w.hr, w.dev.id, Json.obj("managerId", w.dev.id)), 409, "own manager");
        refused(() -> w.p.admin().saveUser(w.hr, null, Json.obj("username", "dev", "name", "Again", "password", "member123")), 409, "username taken");
        refused(() -> w.p.admin().saveUser(w.hr, null, Json.obj("username", "new", "name", "New Person", "password", "short")), 400, "weak password");
        refused(() -> w.p.admin().saveUser(w.lead, w.dev.id, Json.obj("title", "x")), 403, "only HR");
        refused(() -> w.p.admin().saveUser(w.hr, w.hr.id, Json.obj("role", "EMPLOYEE")), 400, "HR keeps own rights");
        w.p.admin().saveUser(w.hr, w.dev.id, Json.obj("managerId", w.boss.id));
        check(w.store.manages(w.boss, w.dev) && !w.store.manages(w.lead, w.dev), "moved");
    }

    static void importCsv() {
        W w = new W();
        String csv = "username,skill,level,last used,verified\n"
                + "dev,core java,4,2026-10,yes\n"
                + "dev2,Spring,Practitioner,,no\n"
                + "nobody,Java,3\n"
                + "dev,Cobol,2\n"
                + "dev2,Docker,9\n"
                + "hr,Java,3,,yes\n";
        Map<String, Object> dry = w.p.admin().importCsv(w.hr, csv, true);
        eq(2, dry.get("ok"), "2 good lines");
        eq(4, dry.get("bad"), "4 bad lines");
        check(w.store.claims.isEmpty(), "dry run changes nothing");
        w.p.admin().importCsv(w.hr, csv, false);
        eq(4, w.level(w.dev, "Jv"), "verified by HR import");
        eq(ClaimStatus.CLAIMED, w.store.claim(w.dev2.id, w.s.get("Sb")).status, "not verified: waits for a check");
        String out = w.p.admin().exportCsv(w.lead);
        check(out.startsWith("Name,Username"), "header");
        check(out.contains("Dev Kumar,dev,") && !out.contains("Ekta Rao"), "only the lead's people");
        refused(() -> w.p.admin().exportCsv(w.dev), 403, "employees cannot export");
    }

    @SuppressWarnings("unchecked")
    static void privacy() {
        W w = new W();
        Claim a = w.has(w.dev, "Jv", 3);
        w.p.profiles().claim(w.dev, Json.obj("skillId", w.s.get("Py"), "level", 2L, "evidence", "scripts"));
        a.note = "Private note from Lata";
        Map<String, Object> seen = w.p.views().profile(w.out, w.dev.id);
        List<Map<String, Object>> skills = (List<Map<String, Object>>) seen.get("skills");
        eq(1, skills.size(), "a colleague sees only the verified skill");
        check(!skills.get(0).containsKey("note") && !skills.get(0).containsKey("evidence"), "no notes or evidence");
        check(!seen.containsKey("goals"), "no goals");
        Map<String, Object> boss = w.p.views().profile(w.boss, w.dev.id);
        eq(2, ((List<?>) boss.get("skills")).size(), "the manager's manager sees everything");
        eq(true, boss.get("full"), "full view");
        refused(() -> w.p.views().growth(w.out, w.dev.id), 403, "career page is private");
        w.p.views().growth(w.lead, w.dev.id);
    }

    @SuppressWarnings("unchecked")
    static void busFactor() {
        W w = new W();
        JobRole r = w.p.growth().saveRole(w.hr, null, Json.obj("name", "Dev", "reqs", List.of(
                Json.obj("skillId", w.s.get("Jv"), "level", 3L, "must", true, "weight", 1L),
                Json.obj("skillId", w.s.get("Dk"), "level", 2L, "must", true, "weight", 1L))));
        for (User u : List.of(w.dev, w.dev2)) w.p.admin().saveUser(w.hr, u.id, Json.obj("jobRoleId", r.id));
        w.has(w.dev, "Jv", 4); w.has(w.dev2, "Jv", 3); w.has(w.dev, "Dk", 3); w.has(w.dev2, "Dk", 2);
        Map<String, Object> m = w.p.views().matrix(w.lead, "team", null);
        List<Map<String, Object>> risks = (List<Map<String, Object>>) m.get("risks");
        eq(1, risks.size(), "only Docker is a risk");
        eq(w.s.get("Dk"), risks.get(0).get("skillId"), "one person at level 3+");
        eq(List.of(w.dev.id), risks.get(0).get("holders"), "it is Dev");
        eq(3, ((List<?>) m.get("rows")).size(), "lead + 2 devs");
        // decay can create a risk: Dev's Docker last used 3 years ago
        w.store.claim(w.dev.id, w.s.get("Dk")).lastUsed = YearMonth.from(Clock.today()).minusMonths(36).toString();
        risks = (List<Map<String, Object>>) w.p.views().matrix(w.lead, "team", null).get("risks");
        eq(0, risks.get(0).get("count"), "nobody left at Docker 3");
    }

    static void snapshot() {
        W w = new W();
        w.has(w.dev, "Jv", 4);
        w.has(w.dev2, "Jv", 2);
        Snapshot s = w.p.admin().snapshot(Clock.today());
        eq(1, s.atLeast(w.s.get("Jv"), 3), "one at 3+");
        eq(2, s.atLeast(w.s.get("Jv"), 1), "two in total");
        check(!w.p.admin().monthly(), "this month already taken");
        Clock.advanceMillis(20 * D);
        check(w.p.admin().monthly(), "new month -> new snapshot");
        eq(2, w.store.snapshots.size(), "two months");
        eq(50.0, Stats.median(List.of(10, 50, 90)), "median");
        eq(85.0, Stats.percentile(List.of(0, 100), 85), "interpolated percentile");
    }

    static void lockout() {
        W w = new W();
        for (int i = 0; i < 5; i++) refused(() -> w.p.auth().login("dev", "wrong-pass1"), 401, "wrong password");
        refused(() -> w.p.auth().login("dev", "member123"), 429, "locked now");
        refused(() -> w.p.auth().login("nobody", "member123"), 401, "same message for unknown users");
        Clock.advanceMillis(6 * 60_000);
        check(w.p.auth().userFor(w.p.auth().login("dev", "member123")) == w.dev, "unlocked after 5 minutes");
    }

    static void threads() throws Exception {
        W w = new W();
        w.has(w.dev, "Jv", 3);
        int n = 16;
        User[] fans = new User[n];
        for (int i = 0; i < n; i++) fans[i] = w.add("fan" + i, "Fan " + i, "EMPLOYEE", w.boss.id);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger(), errors = new AtomicInteger();
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            User f = fans[i % 4];          // 4 people, each tries 4 times at once
            ts.add(new Thread(() -> {
                try {
                    go.await();
                    w.p.profiles().endorse(f, w.dev.id, w.s.get("Jv"), null);
                    ok.incrementAndGet();
                } catch (Problem p) {
                    if (p.status != 409) errors.incrementAndGet();
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            }));
        }
        for (Thread t : ts) t.start();
        go.countDown();
        for (Thread t : ts) t.join();
        eq(4, ok.get(), "each person endorses once");
        eq(0, errors.get(), "no other errors");
        eq(4, w.store.endorsements.size(), "4 stored");
    }

    static void web() throws Exception {
        W w = new W();
        Path front = Files.createTempDirectory("kaushal-front");
        Files.writeString(front.resolve("index.html"), "<!doctype html><title>t</title>");
        ApiServer server = new ApiServer(front, w.p);
        server.start(0);
        try {
            String base = "http://127.0.0.1:" + server.port();
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/")).build(), HttpResponse.BodyHandlers.ofString());
            eq(200, r.statusCode(), "home page");
            check(r.headers().firstValue("Content-Security-Policy").isPresent(), "CSP header");
            eq(404, http.send(HttpRequest.newBuilder(URI.create(base + "/static/..%2Fsecret")).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "no path tricks");
            eq(401, http.send(HttpRequest.newBuilder(URI.create(base + "/api/home")).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "sign in first");
            String login = "{\"username\":\"dev\",\"password\":\"member123\"}";
            eq(403, http.send(HttpRequest.newBuilder(URI.create(base + "/api/login")).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(login)).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "no app header -> blocked");
            eq(403, http.send(HttpRequest.newBuilder(URI.create(base + "/api/login")).header("Content-Type", "application/json").header("X-Kaushal", "1")
                    .header("Origin", "http://evil.example").POST(HttpRequest.BodyPublishers.ofString(login)).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "other website -> blocked");
            eq(415, http.send(HttpRequest.newBuilder(URI.create(base + "/api/login")).header("Content-Type", "text/plain").header("X-Kaushal", "1")
                    .POST(HttpRequest.BodyPublishers.ofString(login)).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "JSON only");
            HttpResponse<String> in = http.send(HttpRequest.newBuilder(URI.create(base + "/api/login")).header("Content-Type", "application/json").header("X-Kaushal", "1")
                    .POST(HttpRequest.BodyPublishers.ofString(login)).build(), HttpResponse.BodyHandlers.ofString());
            eq(200, in.statusCode(), "signed in");
            String cookie = in.headers().firstValue("Set-Cookie").orElse("");
            check(cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict"), "safe cookie");
            String c = cookie.split(";")[0];
            HttpResponse<String> home = http.send(HttpRequest.newBuilder(URI.create(base + "/api/home")).header("Cookie", c).build(), HttpResponse.BodyHandlers.ofString());
            eq(200, home.statusCode(), "home with cookie");
            eq("Dev Kumar", ((Map<?, ?>) Json.parseObject(home.body()).get("me")).get("name"), "it is me");
            HttpResponse<String> bad = http.send(HttpRequest.newBuilder(URI.create(base + "/api/claims")).header("Cookie", c).header("Content-Type", "application/json")
                    .header("X-Kaushal", "1").POST(HttpRequest.BodyPublishers.ofString("{\"skillId\":\"S-101\",\"level\":9}")).build(), HttpResponse.BodyHandlers.ofString());
            eq(400, bad.statusCode(), "friendly 400");
            check(bad.body().contains("Pick a level"), "with the message");
            eq(403, http.send(HttpRequest.newBuilder(URI.create(base + "/api/admin")).header("Cookie", c).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "not HR");
        } finally {
            server.stop();
        }
    }

    /** The whole demo company: built through the services, so no rule may be broken anywhere. */
    static void demo() {
        Clock.fix(null);
        DataStore s = new DataStore(null);
        ApiServer.Parts p = App.parts(s);
        SeedData.load(p);
        LocalDate today = Clock.today();
        eq(24, s.users.size(), "24 people");
        check(s.skills.size() >= 40 && s.roles.size() == 16, "catalogue and roles");
        check(s.claims.size() > 250, "many skills: " + s.claims.size());
        int pending = 0, returned = 0, adjusted = 0;
        for (Claim c : s.claims.values()) {
            check(c.claimed >= 1 && c.claimed <= 5 && c.verified >= 0 && c.verified <= 5, "levels 1..5");
            if (c.verified > 0) {
                check(c.verifiedBy != null && !c.verifiedBy.equals(c.userId), "four eyes: " + c.id);
                check(c.verifiedAt >= c.claimedAt || c.status != ClaimStatus.VERIFIED, "checked after claimed");
            }
            if (c.status == ClaimStatus.CLAIMED) pending++;
            if (c.status == ClaimStatus.RETURNED) returned++;
            if (c.status == ClaimStatus.VERIFIED && c.verified != c.claimed) { adjusted++; check(c.note != null, "adjusted with a reason"); }
        }
        check(pending >= 5 && returned >= 1 && adjusted >= 3, "some waiting, returned and adjusted: " + pending + "/" + returned + "/" + adjusted);
        SkillGraph.order(s.skills);   // throws if the prerequisites loop
        Map<String, Integer> used = new HashMap<>();
        for (Project pr : s.projects.values()) if (pr.busy())
            for (Project.Slot sl : pr.slots) if (sl.assigneeId != null) used.merge(sl.assigneeId, sl.pct, Integer::sum);
        for (int v : used.values()) check(v <= 100, "nobody over 100 %");
        Set<String> pairs = new HashSet<>();
        for (var e : s.endorsements.values()) {
            check(!e.fromId.equals(e.toId), "no self endorsement");
            check(pairs.add(e.fromId + e.toId + e.skillId), "no double endorsement");
        }
        for (Goal g : s.goals.values()) check(List.of("OPEN", "DONE", "DROPPED").contains(g.status), "goal status");
        check(s.snapshots.size() >= 12, "a year of snapshots: " + s.snapshots.size());
        check(s.snapshots.containsKey(YearMonth.from(today).toString()), "this month's snapshot");
        Map<String, Object> sug = p.staffing().suggest(s.userByName("priya"), s.projects.values().stream()
                .filter(x -> x.name.startsWith("e-Mandi")).findFirst().get().id);
        check(((Number) sug.get("filled")).intValue() > ((Number) sug.get("greedyFilled")).intValue(), "the demo shows Hungarian beating greedy");
        User asha = s.userByName("asha");
        Readiness.Result r = p.growth().readiness(asha.id, s.roles.get(asha.targetRoleId));
        check(r.percent() > 40 && r.percent() < 90 && !r.ready(), "Asha is on her way to Senior: " + r.percent());
        check(!p.profiles().queue(s.userByName("ravi")).isEmpty(), "Ravi has claims to check");
        check(SeedDataCheck.ok(), "seed codes are valid");
    }

    /** Small bridge to the package-private self check in SeedData. */
    static final class SeedDataCheck {
        static boolean ok() {
            try {
                var m = SeedData.class.getDeclaredMethod("selfCheck");
                m.setAccessible(true);
                return (Boolean) m.invoke(null);
            } catch (Exception e) {
                return false;
            }
        }
    }
}
