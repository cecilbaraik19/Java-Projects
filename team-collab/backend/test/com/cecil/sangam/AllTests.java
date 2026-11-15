package com.cecil.sangam;

import com.cecil.sangam.core.DependencyGraph;
import com.cecil.sangam.core.EventBus;
import com.cecil.sangam.core.MeetingFinder;
import com.cecil.sangam.core.Mentions;
import com.cecil.sangam.core.TextDiff;
import com.cecil.sangam.core.WorkHours;
import com.cecil.sangam.model.Channel;
import com.cecil.sangam.model.Doc;
import com.cecil.sangam.model.Handoff;
import com.cecil.sangam.model.Meeting;
import com.cecil.sangam.model.Message;
import com.cecil.sangam.model.Note;
import com.cecil.sangam.model.Project;
import com.cecil.sangam.model.Role;
import com.cecil.sangam.model.Standup;
import com.cecil.sangam.model.Task;
import com.cecil.sangam.model.TaskStatus;
import com.cecil.sangam.model.User;
import com.cecil.sangam.service.ChatService;
import com.cecil.sangam.service.Problem;
import com.cecil.sangam.store.DataStore;
import com.cecil.sangam.store.SeedData;
import com.cecil.sangam.util.Clock;
import com.cecil.sangam.util.Json;
import com.cecil.sangam.util.Passwords;
import com.cecil.sangam.web.ApiServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * All the tests, with no JUnit download needed: run  run-tests.bat  or  ./run-tests.sh.
 * The clock is frozen at Monday 16 November 2026, 08:00 UTC. At that moment:
 *   Ranchi 13:30 (working) · Berlin 09:00 (working) · São Paulo 05:00 (night) · Sydney 19:00 (evening) · LA 00:00 (night)
 */
public final class AllTests {
    private static int passed, failed;
    private static final List<String> failures = new ArrayList<>();
    static final Instant START = Instant.parse("2026-11-16T08:00:00Z");
    static final long MIN = WorkHours.MIN, HOUR = WorkHours.HOUR, DAY = WorkHours.DAY;

    public static void main(String[] args) throws Exception {
        run("JSON reads and writes", AllTests::json);
        run("Passwords are hashed with a salt", AllTests::passwords);
        run("Working hours in every time zone (India +05:30, Berlin summer/winter time)", AllTests::workHours);
        run("Cost of a call time: 0 in hours … 5 at night", AllTests::costs);
        run("Team strip: overlap, coverage and pair hours (checked by brute force)", AllTests::strip);
        run("Fair call times: avoids nights, rotates the pain", AllTests::fairness);
        run("Booking a call: pain added, busy people, cancel gives it back", AllTests::meetings);
        run("Task graph: loops refused, safe order, critical path", AllTests::graph);
        run("Blocked task cannot start until its blocker is done", AllTests::blocked);
        run("WIP limits: 2 per person, and per column", AllTests::wip);
        run("Review: someone else must finish (four eyes)", AllTests::review);
        run("Two people edit one task: different fields merge, same field asks", AllTests::fieldMerge);
        run("Ordering inside a column", AllTests::ranking);
        run("Line diff rebuilds the new text (500 random cases)", AllTests::diffRandom);
        run("3-way merge: clean, conflict, same change", AllTests::merge3);
        run("Documents: parallel saves merge; clashes are refused with markers", AllTests::docs);
        run("@mentions and task keys are found", AllTests::mentions);
        run("Quiet hours: a mention waits until the person's day starts", AllTests::quietHours);
        run("Urgent rings through, at most 3 a day; focus time holds", AllTests::urgentAndFocus);
        run("@here reaches only the people working now", AllTests::here);
        run("Threads, unread counts and reading", AllTests::threads);
        run("Privacy: guests see their project only; nobody reads others' DMs", AllTests::privacy);
        run("Stand-up: one per person per local day; digest states", AllTests::standups);
        run("Handoff goes to the right person (follow the sun)", AllTests::handoff);
        run("Live updates: a waiting request wakes up at once", AllTests::events);
        run("16 people pressing Start at once: the WIP limit holds", AllTests::concurrency);
        run("Wrong passwords lock the account", AllTests::lockout);
        run("Web server: sign-in, CSRF header, cookies, static files, live updates", AllTests::web);
        run("Demo workspace builds and keeps every rule", AllTests::demo);
        Clock.fix(null);
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        for (String f : failures) System.out.println("  FAILED: " + f);
        if (failed > 0) System.exit(1);
    }

    interface Test { void run() throws Exception; }

    static void run(String name, Test t) {
        Clock.fix(START);
        try {
            t.run();
            passed++;
            System.out.println("  ok    " + name);
        } catch (Throwable e) {
            failed++;
            failures.add(name + " - " + e);
            System.out.println("  FAIL  " + name + " - " + e);
            if (System.getenv("TRACE") != null) e.printStackTrace();
        }
    }

    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    static void eq(Object want, Object got, String what) {
        if (want == null ? got != null : !want.equals(got)) throw new AssertionError(what + ": wanted " + want + ", got " + got);
    }

    static Problem refused(Runnable r) {
        try { r.run(); } catch (Problem p) { return p; }
        throw new AssertionError("expected a refusal");
    }

    // ------------------------------------------------------------------ a small team for tests

    static final class Team {
        final DataStore store = new DataStore(null);
        final ApiServer.Parts p = App.parts(store);
        final User admin, asha, lena, diego, jack, client;
        final Project orb;
        final String general;

        Team() {
            admin = person("admin", "Priya Admin", "Asia/Kolkata", 9 * 60, 18 * 60, Role.ADMIN);
            asha = person("asha", "Asha Oraon", "Asia/Kolkata", 10 * 60, 19 * 60, Role.MEMBER);
            lena = person("lena", "Lena Vogel", "Europe/Berlin", 9 * 60, 17 * 60 + 30, Role.MEMBER);
            diego = person("diego", "Diego Ramos", "America/Sao_Paulo", 9 * 60, 18 * 60, Role.MEMBER);
            jack = person("jack", "Jack Morgan", "Australia/Sydney", 8 * 60 + 30, 17 * 60, Role.MEMBER);
            client = person("client", "Hana Ito", "America/Los_Angeles", 9 * 60, 17 * 60, Role.GUEST);
            orb = p.work().createProject(admin, Json.obj("key", "ORB", "name", "Orbit app", "memberIds",
                    List.of(asha.id, lena.id, diego.id, jack.id)));
            Channel g = new Channel();
            g.id = store.nextId("C", 1);
            g.name = "general";
            g.kind = Channel.Kind.PUBLIC;
            store.channels.put(g.id, g);
            general = g.id;
            store.notes.clear();   // start each test with no notifications ("added you to the project")
        }

        User person(String username, String name, String zone, int start, int end, Role role) {
            User u = new User();
            u.id = store.nextId("U", 1);
            u.username = username;
            u.name = name;
            u.city = zone;
            u.zone = zone;
            u.workStart = start;
            u.workEnd = end;
            u.role = role;
            u.passwordHash = Passwords.hash("secret123");
            store.users.put(u.id, u);
            return u;
        }

        Task task(String title, User who, String... blockers) {
            return p.work().create(asha, orb.id, Json.obj("title", title, "assigneeId", who == null ? null : who.id,
                    "estimate", 4, "blockedBy", List.of(blockers)));
        }

        Task move(User u, Task t, String status) { return p.work().move(u, t.id, status, null); }

        List<Note> notesOf(User u) {
            List<Note> l = new ArrayList<>();
            for (Note n : store.notes.values()) if (n.userId.equals(u.id)) l.add(n);
            return l;
        }
    }

    // ------------------------------------------------------------------ tests

    static void json() {
        String s = Json.write(Json.obj("a", 1, "b", "x\"<y>", "c", List.of(true, 2.5), "d", null));
        Map<String, Object> m = Json.parseObject(s);
        eq(1L, m.get("a"), "number");
        eq("x\"<y>", m.get("b"), "text with quotes");
        eq(2.5, ((List<?>) m.get("c")).get(1), "decimal");
        check(!s.contains("<"), "< is escaped");
        refused(() -> { try { Json.parse("{\"a\":"); } catch (IllegalArgumentException e) { throw Problem.bad("x"); } });
    }

    static void passwords() {
        String a = Passwords.hash("member123"), b = Passwords.hash("member123");
        check(!a.equals(b), "salted");
        check(Passwords.verify("member123", a) && !Passwords.verify("member124", a), "verify");
        check(Passwords.problem("short") != null && Passwords.problem("longenough1") == null, "rules");
    }

    static void workHours() {
        Team t = new Team();
        check(WorkHours.working(t.asha, Clock.millis()), "Ranchi 13:30 is working");
        check(WorkHours.working(t.lena, Clock.millis()), "Berlin 09:00 is working");
        check(!WorkHours.working(t.diego, Clock.millis()), "São Paulo 05:00 is not");
        // India is +05:30: 10:00 Ranchi = 04:30 UTC
        eq(Instant.parse("2026-11-16T04:30:00Z").toEpochMilli(), WorkHours.startOn(t.asha, LocalDate.of(2026, 11, 16)), "half-hour zone");
        // Berlin: summer time (+2) in July, winter time (+1) in November – same 09:00 local
        eq(Instant.parse("2026-07-15T07:00:00Z").toEpochMilli(), WorkHours.startOn(t.lena, LocalDate.of(2026, 7, 15)), "summer");
        eq(Instant.parse("2026-11-16T08:00:00Z").toEpochMilli(), WorkHours.startOn(t.lena, LocalDate.of(2026, 11, 16)), "winter");
        // Friday evening in Berlin → next start is Monday 09:00
        long fri = Instant.parse("2026-11-20T18:00:00Z").toEpochMilli();
        eq(Instant.parse("2026-11-23T08:00:00Z").toEpochMilli(), WorkHours.nextStart(t.lena, fri), "skips the weekend");
        eq(Clock.millis(), WorkHours.nextStart(t.lena, Clock.millis()), "working now = now");
        // Sydney: Monday 19:00 – the next start is Tuesday 08:30 Sydney = Monday 21:30 UTC
        eq(Instant.parse("2026-11-16T21:30:00Z").toEpochMilli(), WorkHours.nextStart(t.jack, Clock.millis()), "Sydney tomorrow");
        long day = Instant.parse("2026-11-16T00:00:00Z").toEpochMilli();
        eq(9 * HOUR, WorkHours.workingMillisBetween(t.asha, day, day + DAY), "a full day = 9 hours");
    }

    static void costs() {
        Team t = new Team();
        long now = Clock.millis();
        eq(0, WorkHours.stepCost(t.lena, now), "Berlin 09:00");
        eq(5, WorkHours.stepCost(t.diego, now), "São Paulo 05:00 is night");
        eq(2, WorkHours.stepCost(t.jack, now), "Sydney 19:00, two hours after work");
        eq(5, WorkHours.stepCost(t.client, now), "LA midnight");
        eq(1, WorkHours.stepCost(t.lena, now - 30 * MIN), "Berlin 08:30 – a bit early");
        long sat = Instant.parse("2026-11-21T10:00:00Z").toEpochMilli();
        eq(4, WorkHours.stepCost(t.lena, sat), "Saturday is a day off");
        eq(2, WorkHours.cost(t.jack, now - HOUR, now + 15 * MIN), "a call costs its worst quarter hour");
    }

    static void strip() {
        Team t = new Team();
        LocalDate d = LocalDate.of(2026, 11, 16);
        List<User> people = List.of(t.asha, t.lena, t.jack);
        int[] c = WorkHours.counts(people, d);
        long base = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        for (int i = 0; i < 96; i++) {
            int n = 0;
            for (User u : people) if (WorkHours.working(u, base + i * 15 * MIN)) n++;
            eq(n, c[i], "slot " + i);
        }
        int[] all = WorkHours.longestRun(c, 3);
        eq(-1, all[0], "Ranchi, Berlin and Sydney are never all working together");
        int[] two = WorkHours.longestRun(c, 2);
        eq(22, two[1], "Ranchi + Berlin overlap 08:00–13:30 UTC (22 quarter hours)");
        int[] rbs = WorkHours.longestRun(WorkHours.counts(List.of(t.asha, t.lena, t.diego), d), 3);
        eq(48, rbs[0], "Ranchi, Berlin and São Paulo DO meet: from 12:00 UTC");
        eq(6, rbs[1], "for 1.5 hours");
        // pair overlap: two-pointer result = brute force minute by minute
        Random r = new Random(7);
        for (int k = 0; k < 20; k++) {
            User a = t.person("a" + k, "A", pickZone(r), 6 * 60 + 15 * r.nextInt(16), 0, Role.MEMBER);
            a.workEnd = a.workStart + 4 * 60 + 15 * r.nextInt(24);
            User b = t.person("b" + k, "B", pickZone(r), 6 * 60 + 15 * r.nextInt(16), 0, Role.MEMBER);
            b.workEnd = b.workStart + 4 * 60 + 15 * r.nextInt(24);
            long from = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            long brute = 0;
            for (long x = from; x < from + 7 * DAY; x += 15 * MIN) if (WorkHours.working(a, x) && WorkHours.working(b, x)) brute += 15;
            eq(brute / 60.0, WorkHours.weeklyOverlapHours(a, b, d), "pair " + k);
        }
    }

    static String pickZone(Random r) {
        String[] z = { "Asia/Kolkata", "Europe/Berlin", "America/Sao_Paulo", "Australia/Sydney", "America/Los_Angeles", "Africa/Nairobi", "Asia/Tokyo" };
        return z[r.nextInt(z.length)];
    }

    static void fairness() {
        Team t = new Team();
        List<User> people = List.of(t.asha, t.lena, t.diego);
        long from = Clock.millis();
        List<MeetingFinder.Option> o = MeetingFinder.suggest(people, List.of(), from, from + DAY, 30, 5);
        check(!o.isEmpty(), "found times");
        for (User u : people) check(o.get(0).costs().get(u.id) < 5, "best time is no one's night");
        // Diego took a lot of bad calls: the finder should now protect him
        long firstPick = o.get(0).start();
        int diegoBefore = o.get(0).costs().get(t.diego.id);
        t.diego.pain = 30;
        List<MeetingFinder.Option> o2 = MeetingFinder.suggest(people, List.of(), from, from + DAY, 30, 5);
        int diegoAfter = o2.get(0).costs().get(t.diego.id);
        check(diegoAfter <= diegoBefore, "pain makes Diego's time better (or equal)");
        if (diegoBefore > 0) check(o2.get(0).start() != firstPick, "a different time is chosen");
        // the "go async" hint: Sydney + Berlin + São Paulo have no kind time for all three
        Map<String, Object> s = t.p.meetings().suggest(t.jack, Json.obj("attendeeIds", List.of(t.diego.id, t.lena.id), "minutes", 60, "days", 2));
        check(s.get("asyncHint") != null, "suggests async when the best time still hurts");
        // Sydney + São Paulo + LA DO have a kind time (21:00 UTC)
        Map<String, Object> s2 = t.p.meetings().suggest(t.jack, Json.obj("attendeeIds", List.of(t.diego.id, t.client.id), "minutes", 30, "days", 2));
        eq(null, s2.get("asyncHint"), "no hint when a decent time exists");
    }

    static void meetings() {
        Team t = new Team();
        long start = Clock.millis() + HOUR;   // 09:00 UTC = Ranchi 14:30, Berlin 10:00
        Meeting m = t.p.meetings().schedule(t.asha, Json.obj("title", "Sync", "attendeeIds", List.of(t.lena.id, t.jack.id), "minutes", 30, "start", start));
        eq(0, m.costs.get(t.asha.id), "Ranchi in hours");
        check(m.costs.get(t.jack.id) >= 3, "Sydney 20:00 is bad");
        double jackPain = t.jack.pain;
        check(jackPain >= 3, "Jack's pain went up");
        Problem busy = refused(() -> t.p.meetings().schedule(t.lena, Json.obj("title", "Other", "attendeeIds", List.of(t.jack.id), "minutes", 30, "start", start + 15 * MIN)));
        eq(409, busy.status, "Jack is busy then");
        refused(() -> t.p.meetings().cancel(t.lena, m.id));   // only the organiser
        t.p.meetings().cancel(t.asha, m.id);
        eq(0.0, t.jack.pain, "pain given back");
        check(t.notesOf(t.jack).stream().anyMatch(n -> n.text.contains("cancelled")), "Jack is told");
        eq(400, refused(() -> t.p.meetings().schedule(t.asha, Json.obj("title", "Past", "attendeeIds", List.of(t.lena.id), "minutes", 30, "start", Clock.millis() - HOUR))).status, "no past calls");
    }

    static void graph() {
        Team t = new Team();
        Task a = t.task("Task A", t.asha), b = t.task("Task B", t.lena, a.id), c = t.task("Task C", t.diego, b.id);
        Problem loop = refused(() -> t.p.work().setBlockers(t.asha, a.id, List.of(c.id)));
        eq(409, loop.status, "A→B→C→A is a loop");
        check(DependencyGraph.wouldLoop(t.store.tasks, a.id, c.id), "the DFS sees it");
        check(!DependencyGraph.wouldLoop(t.store.tasks, c.id, a.id), "C waiting for A is fine");
        refused(() -> t.p.work().setBlockers(t.asha, a.id, List.of(a.id)));
        Task d = t.task("Task D (big)", t.jack);
        d.estimate = 20;
        List<Task> order = DependencyGraph.order(new ArrayList<>(List.of(c, b, d, a)));
        check(order.indexOf(a) < order.indexOf(b) && order.indexOf(b) < order.indexOf(c), "blockers first");
        List<Task> path = DependencyGraph.criticalPath(List.of(a, b, c, d));
        eq(List.of(d), path, "one 20 h task is longer than 3 × 4 h");
        d.estimate = 4;
        eq(List.of(a, b, c), DependencyGraph.criticalPath(List.of(a, b, c, d)), "the chain A→B→C");
    }

    static void blocked() {
        Team t = new Team();
        Task a = t.task("Design screen", t.lena), b = t.task("Build screen", t.asha, a.id);
        Problem p = refused(() -> t.move(t.asha, b, "DOING"));
        eq(409, p.status, "cannot start");
        check(p.getMessage().contains(a.key), "the message names the blocker");
        t.move(t.lena, a, "DOING");
        t.move(t.lena, a, "REVIEW");
        t.move(t.asha, a, "DONE");
        check(t.notesOf(t.asha).stream().anyMatch(n -> n.text.contains("can start now")), "Asha is told she can start");
        eq(TaskStatus.DOING, t.move(t.asha, b, "DOING").status, "now it can start");
        refused(() -> t.p.work().setBlockers(t.asha, b.id, List.of(t.task("New blocker", t.lena).id)));   // started work cannot get new blockers
    }

    static void wip() {
        Team t = new Team();
        Task a = t.task("One", t.lena), b = t.task("Two", t.lena), c = t.task("Three", t.lena);
        t.move(t.lena, a, "DOING");
        t.move(t.lena, b, "DOING");
        Problem p = refused(() -> t.move(t.lena, c, "DOING"));
        check(p.getMessage().contains("2 tasks"), "personal limit");
        t.orb.wipDoing = 3;
        Task d = t.task("Four", t.asha), e = t.task("Five", t.diego);
        t.move(t.asha, d, "DOING");
        Problem full = refused(() -> t.move(t.diego, e, "DOING"));
        check(full.getMessage().contains("Doing is full"), "column limit");
        Task f = t.task("Unassigned", null);
        t.orb.wipDoing = 0;
        eq(t.jack.id, t.move(t.jack, f, "DOING").assigneeId, "starting an unassigned task takes it");
    }

    static void review() {
        Team t = new Team();
        Task a = t.task("Feature", t.lena);
        t.move(t.lena, a, "DOING");
        Problem skip = refused(() -> t.move(t.lena, a, "DONE"));
        check(skip.getMessage().contains("Review first"), "must go through review");
        t.move(t.lena, a, "REVIEW");
        eq(403, refused(() -> t.move(t.lena, a, "DONE")).status, "not your own review");
        eq(TaskStatus.DONE, t.move(t.asha, a, "DONE").status, "someone else finishes");
        check(a.doneAt > 0 && a.startedAt > 0, "times kept");
        check(t.notesOf(t.lena).stream().anyMatch(n -> n.text.contains("approved")), "Lena is told");
        eq(404, refused(() -> t.move(t.client, a, "TODO")).status, "a guest outside the project does not even see it");
    }

    static void fieldMerge() {
        Team t = new Team();
        Task a = t.task("Login bug", t.lena);
        String oldTitle = a.title, oldDue = a.due;
        // Lena changes the title, Asha (from the same old copy) the due date → both kept
        t.p.work().update(t.lena, a.id, Json.obj("changes", Json.obj("title", "Login bug on slow 3G"), "base", Json.obj("title", oldTitle)));
        t.p.work().update(t.asha, a.id, Json.obj("changes", Json.obj("due", "2026-11-25"), "base", Json.obj("due", oldDue)));
        eq("Login bug on slow 3G", a.title, "Lena's change kept");
        eq("2026-11-25", a.due, "Asha's change kept");
        // Diego changes the title from the OLD copy too → conflict, nothing saved
        Problem p = refused(() -> t.p.work().update(t.diego, a.id, Json.obj("changes", Json.obj("title", "Login broken"), "base", Json.obj("title", oldTitle))));
        eq(409, p.status, "same field");
        List<?> conflicts = (List<?>) ((Map<?, ?>) p.data).get("conflicts");
        eq("Login bug on slow 3G", ((Map<?, ?>) conflicts.get(0)).get("theirs"), "shows the other value");
        eq("Login bug on slow 3G", a.title, "nothing overwritten");
        // the same change twice is not a conflict
        t.p.work().update(t.diego, a.id, Json.obj("changes", Json.obj("title", "Login bug on slow 3G"), "base", Json.obj("title", oldTitle)));
        // numbers and text compare equal ("4" vs 4)
        t.p.work().update(t.asha, a.id, Json.obj("changes", Json.obj("estimate", 6), "base", Json.obj("estimate", "4")));
        eq(6, a.estimate, "estimate");
        refused(() -> t.p.work().update(t.asha, a.id, Json.obj("changes", Json.obj("assigneeId", t.client.id), "base", Json.obj("assigneeId", t.lena.id))));
    }

    static void ranking() {
        Team t = new Team();
        Task a = t.task("Task A", t.lena), b = t.task("Task B", t.lena), c = t.task("Task C", t.lena);
        t.p.work().move(t.asha, c.id, "TODO", a.id);   // C above A
        check(c.rank < a.rank && a.rank < b.rank, "C, A, B");
        t.p.work().move(t.asha, b.id, "TODO", a.id);   // B between C and A
        check(c.rank < b.rank && b.rank < a.rank, "C, B, A");
    }

    static void diffRandom() {
        Random r = new Random(11);
        for (int k = 0; k < 500; k++) {
            List<String> a = new ArrayList<>();
            int n = r.nextInt(25);
            for (int i = 0; i < n; i++) a.add("line " + r.nextInt(8));
            List<String> b = new ArrayList<>(a);
            for (int e = r.nextInt(6); e > 0; e--) {
                int op = r.nextInt(3);
                if (op == 0 || b.isEmpty()) b.add(r.nextInt(b.size() + 1), "new " + r.nextInt(5));
                else if (op == 1) b.remove(r.nextInt(b.size()));
                else b.set(r.nextInt(b.size()), "changed " + r.nextInt(5));
            }
            List<Map<String, Object>> d = TextDiff.diff(a, b);
            List<String> rebuiltB = new ArrayList<>(), rebuiltA = new ArrayList<>();
            int same = 0;
            for (Map<String, Object> x : d) {
                String op = (String) x.get("op");
                if (!op.equals("del")) rebuiltB.add((String) x.get("text"));
                if (!op.equals("add")) rebuiltA.add((String) x.get("text"));
                if (op.equals("same")) same++;
            }
            eq(b, rebuiltB, "case " + k + " new text");
            eq(a, rebuiltA, "case " + k + " old text");
            eq(lcs(a, b), same, "case " + k + " is a LONGEST common part");
        }
    }

    static int lcs(List<String> a, List<String> b) {
        int[][] L = new int[a.size() + 1][b.size() + 1];
        for (int i = 1; i <= a.size(); i++)
            for (int j = 1; j <= b.size(); j++)
                L[i][j] = a.get(i - 1).equals(b.get(j - 1)) ? L[i - 1][j - 1] + 1 : Math.max(L[i - 1][j], L[i][j - 1]);
        return L[a.size()][b.size()];
    }

    static void merge3() {
        List<String> base = List.of("Title", "one", "two", "three", "four");
        List<String> mine = List.of("Title", "ONE", "two", "three", "four");
        List<String> theirs = List.of("Title", "one", "two", "three", "four", "five");
        TextDiff.Merge m = TextDiff.merge3(base, mine, theirs, "Lena");
        check(m.clean(), "different places");
        eq(List.of("Title", "ONE", "two", "three", "four", "five"), m.lines(), "both changes");
        TextDiff.Merge c = TextDiff.merge3(base, List.of("Title", "uno", "two", "three", "four"), List.of("Title", "eins", "two", "three", "four"), "Lena");
        eq(1, c.conflicts(), "same line changed differently");
        check(c.lines().contains(TextDiff.MINE) && c.lines().contains("uno") && c.lines().contains("eins"), "markers with both versions");
        TextDiff.Merge s = TextDiff.merge3(base, mine, mine, "Lena");
        check(s.clean(), "same change twice is fine");
        TextDiff.Merge del = TextDiff.merge3(base, List.of("Title", "two", "three", "four"), List.of("Title", "one", "two", "three", "four", "five"), "Lena");
        eq(List.of("Title", "two", "three", "four", "five"), del.lines(), "a deletion and an addition");
    }

    static void docs() {
        Team t = new Team();
        Doc d = t.p.docs().create(t.asha, t.orb.id, Json.obj("title", "Plan", "body", "Plan\nGoal: ship\nOwner: Asha"));
        int base = d.version;
        t.p.docs().save(t.lena, d.id, Json.obj("baseVersion", base, "body", "Plan\nGoal: ship 3.2\nOwner: Asha"));
        var r = t.p.docs().save(t.diego, d.id, Json.obj("baseVersion", base, "body", "Plan\nGoal: ship\nOwner: Asha and Diego"));
        check(r.merged(), "merged");
        eq("Plan\nGoal: ship 3.2\nOwner: Asha and Diego", d.body, "both edits are in");
        eq(3, d.version, "two new versions");
        Problem p = refused(() -> t.p.docs().save(t.jack, d.id, Json.obj("baseVersion", base, "body", "Plan\nGoal: ship 4.0\nOwner: Asha")));
        eq(409, p.status, "same line, different text");
        String merged = (String) ((Map<?, ?>) p.data).get("merged");
        check(merged.contains("<<<<<<<") && merged.contains("ship 4.0") && merged.contains("ship 3.2"), "conflict text");
        refused(() -> t.p.docs().save(t.jack, d.id, Json.obj("baseVersion", d.version, "body", merged)));   // markers left in
        t.p.docs().restore(t.asha, d.id, 1);
        eq("Plan\nGoal: ship\nOwner: Asha", d.body, "old version back");
        eq("Plan\nGoal: ship 3.2\nOwner: Asha and Diego", d.bodyAt(3), "and nothing lost");
        eq(404, refused(() -> t.p.docs().save(t.client, d.id, Json.obj("baseVersion", d.version, "body", "x"))).status, "guest not in project");
        check(t.p.docs().editing(t.lena, d.id, false).isEmpty(), "nobody else editing");
        eq(List.of(t.lena.id), t.p.docs().editing(t.asha, d.id, false), "Asha sees Lena editing");
    }

    static void mentions() {
        eq(Set.of("lena", "diego"), Mentions.names("@lena and @Diego, please look."), "two names");
        eq(Set.of("lena"), Mentions.names("thanks @lena."), "dot at the end");
        check(Mentions.names("mail me at asha@lumen.example").isEmpty(), "an e-mail is not a mention");
        eq(Set.of("ORB-12", "ATL-3"), Mentions.taskKeys("see ORB-12 and ATL-3"), "task keys");
    }

    static void quietHours() {
        Team t = new Team();
        ChatService.Posted r = t.p.chat().post(t.asha, t.orb.channelId, "@diego can you check this?", null, false, "text");
        eq(List.of(t.diego.id), r.message().mentions, "Diego is mentioned");
        Note n = t.notesOf(t.diego).get(0);
        eq(WorkHours.nextStart(t.diego, Clock.millis()), n.deliverAt, "waits for 09:00 São Paulo");
        eq(Instant.parse("2026-11-16T12:00:00Z").toEpochMilli(), n.deliverAt, "= 12:00 UTC");
        eq(1, r.later().size(), "the sender is told");
        eq("outside working hours", r.later().get(0).get("why"), "why");
        eq(0, ((List<?>) t.p.views().notes(t.diego).get("notes")).size(), "Diego sees nothing yet");
        Clock.fix(Instant.parse("2026-11-16T12:00:00Z"));
        eq(1, ((List<?>) t.p.views().notes(t.diego).get("notes")).size(), "at 09:00 his time it is there");
        // Lena is working → at once
        Clock.fix(START);
        t.p.chat().post(t.asha, t.orb.channelId, "@lena hi", null, false, "text");
        eq(Clock.millis(), t.notesOf(t.lena).get(0).deliverAt, "working people get it now");
    }

    static void urgentAndFocus() {
        Team t = new Team();
        t.p.chat().post(t.asha, t.orb.channelId, "@diego the site is down!", null, true, "text");
        eq(Clock.millis(), t.notesOf(t.diego).get(0).deliverAt, "urgent rings now");
        t.p.chat().post(t.asha, t.orb.channelId, "@diego still down", null, true, "text");
        t.p.chat().post(t.asha, t.orb.channelId, "@diego up again", null, true, "text");
        Problem p = refused(() -> t.p.chat().post(t.asha, t.orb.channelId, "@diego one more", null, true, "text"));
        eq(409, p.status, "4th urgent today refused");
        t.p.people().focus(t.lena, 60);
        t.p.chat().post(t.asha, t.orb.channelId, "@lena when you have time", null, false, "text");
        eq(Clock.millis() + HOUR, t.notesOf(t.lena).get(0).deliverAt, "focus time holds it for an hour");
        t.p.people().focus(t.lena, 0);
        eq(Clock.millis(), t.notesOf(t.lena).get(0).deliverAt, "ending focus releases it");
    }

    static void here() {
        Team t = new Team();
        t.p.chat().post(t.asha, t.orb.channelId, "@here quick vote: ship today?", null, false, "text");
        check(!t.notesOf(t.lena).isEmpty(), "Lena (working) is told");
        check(t.notesOf(t.diego).isEmpty(), "Diego (asleep) is not");
        check(t.notesOf(t.jack).isEmpty(), "Jack (evening) is not");
        t.p.chat().post(t.asha, t.orb.channelId, "@team retro notes are in the doc", null, false, "text");
        check(!t.notesOf(t.diego).isEmpty(), "@team reaches everybody (later)");
    }

    static void threads() {
        Team t = new Team();
        Message q = t.p.chat().post(t.lena, t.orb.channelId, "Who knows the deploy steps?", null, false, "text").message();
        t.p.chat().post(t.asha, t.orb.channelId, "I do – see the doc", q.id, false, "text");
        check(t.notesOf(t.lena).stream().anyMatch(n -> n.text.contains("replied")), "the thread starter hears");
        Message r2 = t.p.chat().post(t.jack, t.orb.channelId, "Thanks!", q.id, false, "text").message();
        eq(q.id, r2.parentId, "reply");
        check(t.notesOf(t.asha).stream().anyMatch(n -> n.text.contains("Jack")), "earlier repliers hear too");
        Map<?, ?> view = t.p.views().thread(t.diego, r2.id);
        eq(2, ((List<?>) view.get("replies")).size(), "thread has 2 replies");
        long unread = unread(t, t.diego);
        eq(3L, unread, "Diego has 3 unread");
        t.p.chat().markRead(t.diego, t.orb.channelId);
        eq(0L, unread(t, t.diego), "read");
        t.p.chat().react(t.diego, q.id, "👍");
        eq(1, q.reactions.get("👍").size(), "reaction");
        t.p.chat().react(t.diego, q.id, "👍");
        check(!q.reactions.containsKey("👍"), "second click takes it back");
        refused(() -> t.p.chat().edit(t.diego, q.id, "hacked"));
        t.p.chat().delete(t.lena, q.id);
        check(q.deleted && q.text.isEmpty(), "deleted");
    }

    static long unread(Team t, User u) {
        for (Map<String, Object> c : t.p.views().channels(u)) if (t.orb.channelId.equals(c.get("id"))) return (Long) c.get("unread");
        return -1;
    }

    static void privacy() {
        Team t = new Team();
        Project web = t.p.work().createProject(t.admin, Json.obj("key", "WEB", "name", "Website", "memberIds", List.of(t.lena.id, t.client.id)));
        eq(404, refused(() -> t.p.views().board(t.client, t.orb.id)).status, "guest cannot see Orbit");
        eq(1, t.p.views().projects(t.client).size(), "guest sees only Website");
        check(t.p.views().channels(t.client).stream().noneMatch(c -> "#general".equals(c.get("name"))), "no #general for guests");
        Task w = t.p.work().create(t.lena, web.id, Json.obj("title", "Homepage"));
        eq(403, refused(() -> t.p.work().move(t.client, w.id, "DOING", null)).status, "guests cannot move tasks");
        t.p.work().comment(t.client, w.id, "Looks great, @lena!");   // but they can comment
        check(t.notesOf(t.lena).stream().anyMatch(n -> n.text.contains("mentioned")), "comment mention works");
        refused(() -> t.p.chat().direct(t.client, t.asha.id));   // guest and Asha share no project
        Channel dm = t.p.chat().direct(t.lena, t.diego.id);
        t.p.chat().post(t.lena, dm.id, "private note", null, false, "text");
        eq(404, refused(() -> t.p.views().channel(t.admin, dm.id, 0)).status, "even the admin cannot read a DM");
        eq(dm.id, t.p.chat().direct(t.diego, t.lena.id).id, "the same DM both ways");
        List<?> people = t.p.views().people(t.client);
        eq(3, people.size(), "a guest sees only the people of their project (Lena, the admin, herself)");
        Map<String, Object> s = t.p.views().search(t.client, "homepage");
        eq(1, ((List<?>) s.get("tasks")).size(), "search respects access");
    }

    static void standups() {
        Team t = new Team();
        Standup a = t.p.rhythm().postStandup(t.lena, Json.obj("yesterday", "x", "today", "Design review", "blockers", "none"));
        Standup b = t.p.rhythm().postStandup(t.lena, Json.obj("yesterday", "x", "today", "Design review and tests", "blockers", "Waiting for @asha"));
        eq(a.id, b.id, "same local day → updated, not a second one");
        eq("2026-11-16", a.date, "Lena's local date");
        check(t.notesOf(t.asha).stream().anyMatch(n -> n.text.contains("blocked")), "Asha is asked for help");
        Map<String, Object> d = t.p.rhythm().digest(t.asha, t.orb.id);
        Map<String, String> state = new HashMap<>();
        for (Object o : (List<?>) d.get("rows")) {
            Map<?, ?> r = (Map<?, ?>) o;
            state.put((String) ((Map<?, ?>) r.get("user")).get("username"), (String) r.get("state"));
        }
        eq("posted", state.get("lena"), "Lena");
        eq("late", state.get("asha"), "Asha's day began at 10:00, it is 13:30");
        eq("not-started", state.get("diego"), "Diego is still asleep");
        eq(1, ((List<?>) d.get("blockers")).size(), "one blocker");
        // Sydney: Monday 19:00 – Jack's local date is the 16th, his day is over, he never posted → late
        eq("late", state.get("jack"), "Jack");
        refused(() -> t.p.rhythm().postStandup(t.asha, Json.obj("today", " ")));
    }

    static void handoff() {
        Team t = new Team();
        // Asha (Ranchi 13:30). Lena is working with 8.5 h left → she gets the baton.
        List<Map<String, Object>> c = t.p.rhythm().candidates(t.orb, t.asha, Clock.millis());
        eq(t.lena.id, ((Map<?, ?>) c.get(0).get("user")).get("id"), "Lena first");
        // 17:00 UTC: Berlin 18:00 (off), São Paulo 14:00 (working) → Diego
        Clock.fix(Instant.parse("2026-11-16T17:00:00Z"));
        Handoff h = t.p.rhythm().handoff(t.lena, t.orb.id, Json.obj("text", "Done: login fix. Next: release notes. Watch out: staging slow."));
        eq(t.diego.id, h.toId, "Diego carries on");
        eq(Clock.millis(), h.deliverAt, "he is working, so at once");
        // 23:00 UTC: São Paulo 20:00 (off), Sydney Tue 10:00 (working) → Jack
        Clock.fix(Instant.parse("2026-11-16T23:00:00Z"));
        Handoff h2 = t.p.rhythm().handoff(t.diego, t.orb.id, Json.obj("text", "Done: release notes. Next: QA run on the new build."));
        eq(t.jack.id, h2.toId, "Jack in Sydney");
        refused(() -> t.p.rhythm().ack(t.lena, h2.id));
        t.p.rhythm().ack(t.jack, h2.id);
        check(h2.ackAt > 0, "acknowledged");
        check(t.store.messages.values().stream().anyMatch(m -> "handoff".equals(m.kind)), "posted in the project channel");
    }

    static void events() throws Exception {
        Team t = new Team();
        EventBus bus = t.p.events();
        long seq = bus.seq();
        AtomicReference<EventBus.Batch> got = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        long t0 = System.currentTimeMillis();
        Thread waiter = new Thread(() -> {
            try { got.set(bus.await(seq, 5000, e -> true)); } catch (InterruptedException ignored) { }
            done.countDown();
        });
        waiter.start();
        Thread.sleep(150);
        t.p.chat().post(t.asha, t.orb.channelId, "hello", null, false, "text");
        check(done.await(3, java.util.concurrent.TimeUnit.SECONDS), "woke up");
        check(System.currentTimeMillis() - t0 < 2000, "fast, not after the timeout");
        eq("message", got.get().events().get(0).type(), "the message event");
        // a filtered person keeps waiting and gets nothing
        EventBus.Batch none = bus.await(bus.seq() - 1, 200, e -> false);
        eq(0, none.events().size(), "not visible");
        for (int i = 0; i < EventBus.KEEP + 5; i++) bus.publish("x", null, null, null, Json.obj());
        check(bus.await(1, 10, e -> true).reset(), "too old → reset");
    }

    static void concurrency() throws Exception {
        Team t = new Team();
        t.orb.wipDoing = 0;
        List<Task> tasks = new ArrayList<>();
        for (int i = 0; i < 16; i++) tasks.add(t.task("Parallel " + i, t.lena));
        CountDownLatch go = new CountDownLatch(1), finished = new CountDownLatch(16);
        AtomicInteger ok = new AtomicInteger();
        for (Task x : tasks) new Thread(() -> {
            try { go.await(); t.p.work().move(t.lena, x.id, "DOING", null); ok.incrementAndGet(); }
            catch (Problem | InterruptedException ignored) { }
            finally { finished.countDown(); }
        }).start();
        go.countDown();
        finished.await();
        eq(2, ok.get(), "exactly 2 of 16 got in");
        eq(2L, tasks.stream().filter(x -> x.status == TaskStatus.DOING).count(), "and the board agrees");
    }

    static void lockout() {
        Team t = new Team();
        for (int i = 0; i < 5; i++) refused(() -> t.p.auth().login("lena", "wrong"));
        Problem p = refused(() -> t.p.auth().login("lena", "secret123"));
        eq(429, p.status, "locked");
        Clock.fix(START.plusSeconds(6 * 60));
        check(t.p.auth().login("lena", "secret123") != null, "open again after 5 minutes");
        eq(401, refused(() -> t.p.auth().login("nobody", "x")).status, "same message for unknown users");
    }

    static void web() throws Exception {
        Team t = new Team();
        Path front = Files.createTempDirectory("sangam-front");
        Files.writeString(front.resolve("index.html"), "<!doctype html><title>t</title>");
        ApiServer server = new ApiServer(front, t.p);
        server.pollMs = 300;
        server.start(0);
        try {
            String base = "http://127.0.0.1:" + server.port();
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/")).build(), HttpResponse.BodyHandlers.ofString());
            eq(200, r.statusCode(), "home page");
            check(r.headers().firstValue("Content-Security-Policy").isPresent(), "CSP header");
            eq(404, http.send(HttpRequest.newBuilder(URI.create(base + "/static/..%2F..%2Fetc%2Fpasswd")).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "no path tricks");
            eq(401, http.send(HttpRequest.newBuilder(URI.create(base + "/api/home")).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "needs sign-in");
            String login = Json.write(Json.obj("username", "lena", "password", "secret123"));
            HttpResponse<String> noHeader = http.send(HttpRequest.newBuilder(URI.create(base + "/api/login")).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(login)).build(), HttpResponse.BodyHandlers.ofString());
            eq(403, noHeader.statusCode(), "CSRF header required");
            HttpResponse<String> ok = http.send(HttpRequest.newBuilder(URI.create(base + "/api/login")).header("Content-Type", "application/json").header("X-Sangam", "1")
                    .POST(HttpRequest.BodyPublishers.ofString(login)).build(), HttpResponse.BodyHandlers.ofString());
            eq(200, ok.statusCode(), "signed in");
            String cookie = ok.headers().firstValue("Set-Cookie").orElse("");
            check(cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict"), "safe cookie");
            String session = cookie.split(";")[0];
            eq(200, http.send(HttpRequest.newBuilder(URI.create(base + "/api/home")).header("Cookie", session).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "home data");
            long seq = t.p.events().seq();
            String body = Json.write(Json.obj("text", "hello from the web @asha"));
            HttpRequest post = HttpRequest.newBuilder(URI.create(base + "/api/channels/" + t.orb.channelId + "/messages")).header("Cookie", session)
                    .header("Content-Type", "application/json").header("X-Sangam", "1").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            eq(200, http.send(post, HttpResponse.BodyHandlers.ofString()).statusCode(), "posted");
            HttpResponse<String> ev = http.send(HttpRequest.newBuilder(URI.create(base + "/api/events?since=" + seq)).header("Cookie", session).build(), HttpResponse.BodyHandlers.ofString());
            check(ev.body().contains("\"message\""), "the event comes back");
            HttpResponse<String> evil = http.send(HttpRequest.newBuilder(URI.create(base + "/api/channels/" + t.orb.channelId + "/messages")).header("Cookie", session)
                    .header("Content-Type", "application/json").header("X-Sangam", "1").header("Origin", "http://evil.example")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            eq(403, evil.statusCode(), "other website blocked");
            eq(403, http.send(HttpRequest.newBuilder(URI.create(base + "/api/admin/audit")).header("Cookie", session).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "not the admin");
            HttpResponse<String> conflict = http.send(HttpRequest.newBuilder(URI.create(base + "/api/projects/" + t.orb.id + "/tasks")).header("Cookie", session)
                    .header("Content-Type", "application/json").header("X-Sangam", "1").POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"x\"}")).build(), HttpResponse.BodyHandlers.ofString());
            eq(400, conflict.statusCode(), "friendly 400");
            check(conflict.body().contains("3 to 120"), "with the reason");
        } finally {
            server.stop();
        }
    }

    static void demo() {
        Clock.fix(null);
        DataStore store = new DataStore(null);
        ApiServer.Parts p = App.parts(store);
        SeedData.load(p);
        check(store.tasks.size() >= 45, "tasks");
        check(store.messages.size() > 150, "a real chat history");
        check(store.standups.size() > 50 && store.handoffs.size() > 10 && store.meetings.size() >= 8, "rhythm");
        Map<String, Integer> doing = new HashMap<>();
        for (Task t : store.tasks.values()) {
            if (t.status == TaskStatus.DOING) doing.merge(t.assigneeId, 1, Integer::sum);
            for (String b : t.blockedBy) check(!DependencyGraph.wouldLoop(store.tasks, t.id, b), "no loops: " + t.key);
            if (t.status.active() || t.status == TaskStatus.DONE)
                for (String b : t.blockedBy) check(store.tasks.get(b).status == TaskStatus.DONE, t.key + " started before its blocker was done");
        }
        for (int n : doing.values()) check(n <= 2, "personal WIP kept");
        for (Project pr : store.projects.values()) {
            long d = store.tasks.values().stream().filter(t -> t.projectId.equals(pr.id) && t.status == TaskStatus.DOING).count();
            check(pr.wipDoing == 0 || d <= pr.wipDoing, "column WIP kept in " + pr.key);
        }
        Set<String> days = new HashSet<>();
        for (Standup s : store.standups.values()) check(days.add(s.userId + s.date), "one stand-up per person per day");
        check(store.docs.values().stream().anyMatch(d -> d.note.contains("combined")), "one automatic merge");
        check(store.users.values().stream().anyMatch(u -> u.pain > 0), "some calls were at hard times");
        for (Meeting m : store.meetings.values())
            for (Meeting o : store.meetings.values())
                if (m != o && m.start < o.end && o.start < m.end)
                    for (String x : m.everyone()) check(!o.involves(x), "nobody in two calls at once");
    }
}
