package com.cecil.swikriti;

import com.cecil.swikriti.core.BusinessCalendar;
import com.cecil.swikriti.core.Condition;
import com.cecil.swikriti.core.Diff;
import com.cecil.swikriti.core.DocNumber;
import com.cecil.swikriti.core.Ledger;
import com.cecil.swikriti.model.Comment;
import com.cecil.swikriti.model.Delegation;
import com.cecil.swikriti.model.DocStatus;
import com.cecil.swikriti.model.Document;
import com.cecil.swikriti.model.LedgerEntry;
import com.cecil.swikriti.model.Revision;
import com.cecil.swikriti.model.Role;
import com.cecil.swikriti.model.RouteStage;
import com.cecil.swikriti.model.Task;
import com.cecil.swikriti.model.Template;
import com.cecil.swikriti.model.User;
import com.cecil.swikriti.service.Problem;
import com.cecil.swikriti.store.DataStore;
import com.cecil.swikriti.store.SeedData;
import com.cecil.swikriti.util.Clock;
import com.cecil.swikriti.util.Csv;
import com.cecil.swikriti.util.Json;
import com.cecil.swikriti.util.Passwords;
import com.cecil.swikriti.web.ApiServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * All the tests, with no JUnit download needed: run  run-tests.bat  or  ./run-tests.sh.
 * The clock is frozen at Monday 16 November 2026, 08:00 UTC (13:30 in India - office hours).
 */
public final class AllTests {
    private static int passed, failed;
    private static final List<String> failures = new ArrayList<>();
    static final Instant START = Instant.parse("2026-11-16T08:00:00Z");
    static final long H = 3_600_000L, D = 24 * H;

    interface Test { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        run("JSON reads and writes", AllTests::json);
        run("Passwords are hashed with a salt", AllTests::passwords);
        run("CSV: quotes, commas and the formula guard", AllTests::csv);
        run("Office hours: weekends, holidays, hours between", AllTests::calendar);
        run("Condition language: and/or/not, brackets, 50k / 5L, in, contains", AllTests::conditions);
        run("Condition language: friendly mistakes and 'did you mean'", AllTests::conditionErrors);
        run("Diff (LCS): added, removed and kept lines", AllTests::diff);
        run("Ledger: hash chain checks and finds tampering", AllTests::ledger);
        run("Document numbers and revision letters", AllTests::numbers);
        run("Drafts: checks on the form, only the author edits", AllTests::drafts);
        run("Route: conditions skip and add stages", AllTests::routeConditions);
        run("Four eyes: the author never signs", AllTests::fourEyes);
        run("Quorum: 2 of 3 directors, and a 'no' that still leaves a way", AllTests::quorum);
        run("Parallel seats: Legal and Finance both sign", AllTests::parallel);
        run("One person signs only one stage (distinct)", AllTests::distinct);
        run("Send back -> revision B -> route starts again", AllTests::sendBack);
        run("Reject, approve, certificate and public check", AllTests::outcomes);
        run("Withdraw stops everything", AllTests::withdraw);
        run("Delegation: signs for, loops refused, waiting work moves", AllTests::delegation);
        run("Deadlines in office hours, reminder, escalation, urgent", AllTests::deadlines);
        run("A seat with nobody goes to the Document Controller", AllTests::fallback);
        run("Who may read a document", AllTests::access);
        run("Redlines: who may write, which lines", AllTests::redlines);
        run("Attachments: magic bytes, size, fingerprint", AllTests::attachments);
        run("Templates: rules checked, documents keep their route", AllTests::templates);
        run("Sign-in: wrong passwords lock the account", AllTests::lockout);
        run("16 threads sign at once: the stage completes exactly once", AllTests::threads);
        run("Web server: CSRF, cookies, JSON only, no path tricks, public check", AllTests::web);
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
        final User ctl, md, dir2, dir3, fin, fin2, pm, dev, dev2, proc, legal, qa, hr;

        W() {
            ctl = add("ctl", "Chitra Lal", Role.ADMIN, null);
            md = add("md", "Mohan Das", Role.MANAGER, null, "Directors");
            dir2 = add("dir2", "Divya Rao", Role.MANAGER, md.id, "Directors");
            dir3 = add("dir3", "Dinesh Kerketta", Role.MANAGER, md.id, "Directors");
            fin = add("fin", "Firoz Khan", Role.MANAGER, dir3.id, "Finance");
            fin2 = add("fin2", "Fatima Beg", Role.EMPLOYEE, fin.id, "Finance");
            pm = add("pm", "Prakash Minz", Role.MANAGER, dir2.id);
            dev = add("dev", "Dev Kumar", Role.EMPLOYEE, pm.id);
            dev2 = add("dev2", "Deepa Soren", Role.EMPLOYEE, pm.id);
            proc = add("proc", "Pankaj Oraon", Role.EMPLOYEE, pm.id, "Procurement");
            legal = add("legal", "Leela Tudu", Role.EMPLOYEE, md.id, "Legal");
            qa = add("qa", "Qasim Ali", Role.EMPLOYEE, pm.id, "Design QA");
            hr = add("hr", "Hari Lohra", Role.EMPLOYEE, md.id, "HR");
            String dirs = "[\"USER:" + md.id + "\",\"USER:" + dir2.id + "\",\"USER:" + dir3.id + "\"]";
            tpl("PR", true, false, true, "[" + st("Line manager", "[\"MANAGER\"]", "ANY", 1, "", 8) + ","
                    + st("Procurement", "[\"GROUP:Procurement\"]", "ANY", 1, "", 8) + ","
                    + st("Finance", "[\"GROUP:Finance\"]", "ANY", 1, "amount > 50k", 16) + ","
                    + st("Directors", dirs, "QUORUM", 2, "amount > 5L", 24) + "]");
            tpl("CON", true, false, true, "[" + st("Line manager", "[\"MANAGER\"]", "ANY", 1, "", 8) + ","
                    + st("Legal + Finance", "[\"GROUP:Legal\",\"GROUP:Finance\"]", "ALL", 1, "", 24) + "]");
            tpl("POL", false, true, false, "[" + st("HR", "[\"GROUP:HR\"]", "ANY", 1, "", 16) + "]");
            tpl("EXP", true, false, false, "[" + st("Line manager", "[\"MANAGER\"]", "ANY", 1, "", 8) + ","
                    + st("Department head", "[\"MANAGER2\"]", "ANY", 1, "", 8) + "]");
        }

        User add(String username, String name, Role role, String manager, String... groups) {
            User u = new User();
            u.id = "U-" + (store.users.size() + 1);
            u.username = username; u.name = name; u.role = role; u.managerId = manager; u.dept = "Projects"; u.title = "Tester";
            u.groups = new ArrayList<>(List.of(groups));
            u.passwordHash = Passwords.hash("member123");
            store.users.put(u.id, u);
            return u;
        }

        static String st(String name, String seats, String mode, int quorum, String cond, int sla) {
            return "{\"name\":\"" + name + "\",\"seats\":" + seats + ",\"mode\":\"" + mode + "\",\"quorum\":" + quorum
                    + ",\"condition\":\"" + cond + "\",\"slaHours\":" + sla + "}";
        }

        void tpl(String code, boolean amount, boolean publish, boolean distinct, String stages) {
            p.admin().saveTemplate(ctl, null, Json.obj("code", code, "name", code + " type", "hasAmount", amount,
                    "publishOnApproval", publish, "distinct", distinct, "stages", Json.parse(stages)));
        }

        Document draft(User u, String type, long amount) {
            return p.docs().create(u, Json.obj("type", type, "title", type + " for " + amount, "amount", amount, "body", "line one\nline two\nline three"));
        }

        Document submitted(User u, String type, long amount) {
            Document d = draft(u, type, amount);
            return p.docs().submit(u, d.id, null);
        }

        /** The open task of the document that this person may decide. */
        Task task(Document d, User u) {
            for (Task k : store.tasksOf(d.id)) if (k.state.open() && k.assignees.contains(u.id)) return k;
            return null;
        }

        Map<String, Object> sign(Document d, User u, String action, String comment) {
            Task k = task(d, u);
            if (k == null) throw new AssertionError("no open task for " + u.username + " on " + d.title);
            return p.flow().decide(u, k.id, action, comment);
        }

        Map<String, Object> ok(Document d, User u) { return sign(d, u, "APPROVE", "fine"); }

        List<RouteStage.State> states(Document d) {
            List<RouteStage.State> out = new ArrayList<>();
            for (RouteStage r : d.route) out.add(r.state);
            return out;
        }
    }

    // ------------------------------------------------------------------ the tests

    static void json() {
        Map<String, Object> m = Json.parseObject("{\"a\":1,\"b\":[true,null,\"x\\u0041\"],\"c\":{\"d\":2.5}}");
        eq(1L, m.get("a"), "number");
        eq("xA", ((List<?>) m.get("b")).get(2), "unicode escape");
        eq(2.5, ((Map<?, ?>) m.get("c")).get("d"), "nested");
        check(Json.write(Json.obj("s", "<b>")).contains("\\u003cb\\u003e"), "html-safe");
        try { Json.parse("{\"a\":}"); throw new AssertionError("bad JSON accepted"); } catch (IllegalArgumentException expected) { /* fine */ }
    }

    static void passwords() {
        String h1 = Passwords.hash("secret123"), h2 = Passwords.hash("secret123");
        check(!h1.equals(h2), "salted");
        check(Passwords.verify("secret123", h1) && !Passwords.verify("secret124", h1), "verify");
        check(Passwords.problem("short1") != null && Passwords.problem("longenough") != null && Passwords.problem("longenough1") == null, "rules");
    }

    static void csv() {
        String out = Csv.write(List.of(List.of("a,b", "say \"hi\"", "=SUM(A1)", "-12")));
        eq("\"a,b\",\"say \"\"hi\"\"\",'=SUM(A1),-12\r\n", out, "quoted and guarded");
        eq(List.of("a,b", "say \"hi\"", "'=SUM(A1)", "-12"), Csv.parse(out).get(0), "round trip");
    }

    static void calendar() {
        BusinessCalendar c = new BusinessCalendar(List.of("2026-11-23"));
        Instant fri5pm = Instant.parse("2026-11-20T11:30:00Z");                 // Fri 17:00 IST
        eq(Instant.parse("2026-11-24T05:00:00Z"), c.addHours(fri5pm, 2), "Fri 17:00 + 2 h -> Tue 10:30 (Mon is a holiday)");
        eq(Instant.parse("2026-11-16T12:30:00Z"), c.addHours(Instant.parse("2026-11-16T02:00:00Z"), 8.5), "07:30 Mon + 8.5 h -> 18:00 Mon");
        eq(8.5, c.hoursBetween(Instant.parse("2026-11-16T00:00:00Z"), Instant.parse("2026-11-17T00:00:00Z")), "one full day");
        eq(0.0, c.hoursBetween(Instant.parse("2026-11-21T00:00:00Z"), Instant.parse("2026-11-23T18:00:00Z")), "weekend + holiday = 0");
        eq(2.0, c.hoursBetween(fri5pm, c.addHours(fri5pm, 2)), "round trip");
    }

    static void conditions() {
        Map<String, Object> f = Json.obj("amount", 600_000L, "dept", "Projects", "type", "PR", "vendor", "New Vendor Ltd", "title", "Laptops", "urgent", false);
        check(Condition.test("amount > 50k", f), "50k");
        check(Condition.test("amount > 5L", f) && !Condition.test("amount > 6L", f), "lakh");
        check(!Condition.test("amount >= 1cr", f), "crore");
        check(Condition.test("amount > 5L and not urgent", f), "and not");
        check(Condition.test("urgent or dept = projects", f), "or, case");
        check(!Condition.test("urgent or (dept = Design and amount > 1k)", f), "brackets");
        check(Condition.test("dept in (Design, Projects)", f), "in");
        check(Condition.test("vendor contains \"new\"", f), "contains");
        check(Condition.test("", f), "empty = always");
        check(Condition.test("not urgent and amount != 0", f), "!=");
    }

    static void conditionErrors() {
        check(Condition.check("ammount > 5").contains("did you mean 'amount'"), "did you mean");
        check(Condition.check("amount >").contains("Expected a number"), "missing number");
        check(Condition.check("(amount > 5").contains("Missing ')'"), "bracket");
        check(Condition.check("dept > 5") != null, "text with >");
        check(Condition.check("amount > 5 amount") != null, "extra");
        check(Condition.check("amount ! 5") != null, "lonely !");
    }

    static void diff() {
        List<Diff.Line> d = Diff.lines("a\nb\nc\nd", "a\nB\nc\nd\ne");
        StringBuilder sb = new StringBuilder();
        for (Diff.Line l : d) sb.append(l.kind() == Diff.Kind.SAME ? "=" : l.kind() == Diff.Kind.ADD ? "+" : "-").append(l.text()).append(' ');
        eq("=a -b +B =c =d +e ", sb.toString(), "lcs result");
        eq(3, Diff.changes(d), "changed lines");
        eq(0, Diff.changes(Diff.lines("x\ny", "x\ny")), "same text");
        eq(2, Diff.lines("", "p\nq").size(), "from empty");
    }

    static void ledger() {
        byte[] key = "0123456789abcdef0123456789abcdef".getBytes();
        List<LedgerEntry> chain = new ArrayList<>();
        String prev = Ledger.GENESIS;
        for (int i = 1; i <= 5; i++) {
            LedgerEntry e = Ledger.make(key, i, 1000L * i, "D-1", 1, "APPROVE", "U-" + i, null, "h" + i, prev);
            chain.add(e);
            prev = e.hash();
        }
        check(Ledger.verify(chain, key).ok(), "intact chain");
        check(!Ledger.verify(chain, "another key 123456789012345678".getBytes()).ok(), "wrong key fails");
        List<LedgerEntry> changed = new ArrayList<>(chain);
        LedgerEntry e3 = chain.get(2);
        changed.set(2, new LedgerEntry(e3.seq(), e3.at(), e3.docId(), e3.rev(), "REJECT", e3.userId(), null, e3.docHash(), e3.prev(), e3.hash()));
        Ledger.Check c = Ledger.verify(changed, key);
        check(!c.ok() && c.brokenAt() == 3, "changed entry found: " + c);
        List<LedgerEntry> removed = new ArrayList<>(chain);
        removed.remove(1);
        check(!Ledger.verify(removed, key).ok(), "deleted entry found");
        eq(64, Ledger.sha256("abc").length(), "sha-256 hex");
        eq("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Ledger.sha256("abc"), "known SHA-256 value");
    }

    static void numbers() {
        eq("26-27", DocNumber.fy(LocalDate.of(2026, 11, 18)), "Nov");
        eq("26-27", DocNumber.fy(LocalDate.of(2027, 3, 31)), "31 Mar");
        eq("27-28", DocNumber.fy(LocalDate.of(2027, 4, 1)), "1 Apr");
        eq("PR/26-27/0042", DocNumber.format("PR", "26-27", 42), "format");
        eq("A", Revision.letter(1), "A");
        eq("Z", Revision.letter(26), "Z");
        eq("AA", Revision.letter(27), "AA");
        W w = new W();
        Document a = w.submitted(w.dev, "PR", 10_000), b = w.submitted(w.dev, "PR", 20_000), c = w.submitted(w.dev, "EXP", 500);
        eq("PR/26-27/0001", a.number, "first PR");
        eq("PR/26-27/0002", b.number, "second PR");
        eq("EXP/26-27/0001", c.number, "own counter per type");
    }

    static void drafts() {
        W w = new W();
        refused(() -> w.p.docs().create(w.dev, Json.obj("type", "XX", "title", "Hello")), 400, "unknown type");
        refused(() -> w.p.docs().create(w.dev, Json.obj("type", "PR", "title", "Hi")), 400, "short title");
        refused(() -> w.p.docs().create(w.dev, Json.obj("type", "PR", "title", "Chairs", "amount", -5L)), 400, "negative");
        refused(() -> w.p.docs().create(w.dev, Json.obj("type", "PR", "title", "Chairs", "needBy", "2026-11-01")), 400, "past date");
        Document d = w.p.docs().create(w.dev, Json.obj("type", "PR", "title", "Chairs", "body", "x"));
        refused(() -> w.p.docs().submit(w.dev, d.id, null), 400, "no amount yet");
        refused(() -> w.p.docs().update(w.dev2, d.id, Json.obj("title", "Mine now")), 404, "others cannot even see a draft");
        w.p.docs().update(w.dev, d.id, Json.obj("amount", 12_000L));
        w.p.docs().submit(w.dev, d.id, null);
        refused(() -> w.p.docs().update(w.dev, d.id, Json.obj("title", "Changed later")), 409, "frozen while in review");
    }

    static void routeConditions() {
        W w = new W();
        Document small = w.submitted(w.dev, "PR", 30_000);
        eq(List.of(RouteStage.State.ACTIVE, RouteStage.State.WAITING, RouteStage.State.SKIPPED, RouteStage.State.SKIPPED), w.states(small), "30k: no Finance, no directors");
        Document mid = w.submitted(w.dev, "PR", 60_000);
        eq(RouteStage.State.WAITING, mid.route.get(2).state, "60k: Finance needed");
        Document big = w.submitted(w.dev, "PR", 600_000);
        eq(RouteStage.State.WAITING, big.route.get(3).state, "6L: directors needed");
        check(small.route.get(2).why.contains("amount > 50k"), "the reason is kept");
        // walk the small one to the end
        w.ok(small, w.pm);
        w.ok(small, w.proc);
        eq(DocStatus.APPROVED, small.status, "approved after 2 stages");
    }

    static void fourEyes() {
        W w = new W();
        Document d = w.submitted(w.proc, "PR", 30_000);   // the author is in Procurement
        w.ok(d, w.pm);
        Task k = w.task(d, w.proc);
        check(k == null, "the author is not on the Procurement seat");
        Task open = w.store.tasksOf(d.id).stream().filter(t -> t.state.open()).findFirst().orElseThrow();
        check(!open.candidates.contains(w.proc.id), "not a candidate");
        refused(() -> w.p.flow().decide(w.proc, open.id, "APPROVE", null), 403, "author cannot sign");
        refused(() -> w.p.flow().decide(w.dev2, open.id, "APPROVE", null), 404, "someone not involved cannot even see it");
    }

    static void quorum() {
        W w = new W();
        Document d = w.submitted(w.dev, "PR", 700_000);
        w.ok(d, w.pm); w.ok(d, w.proc); w.ok(d, w.fin);
        eq(3, d.stageIndex, "at directors");
        eq("waiting for more", w.ok(d, w.md).get("outcome"), "1 of 2");
        eq("approved", w.ok(d, w.dir2).get("outcome"), "2 of 2 -> approved");
        Task third = w.store.tasksOf(d.id).stream().filter(t -> t.rule.endsWith(w.dir3.id)).findFirst().orElseThrow();
        eq(Task.State.NOT_NEEDED, third.state, "third seat not needed");
        // a "no" that still leaves 2 possible yes votes does not reject
        Document e = w.submitted(w.dev, "PR", 700_000);
        w.ok(e, w.pm); w.ok(e, w.proc); w.ok(e, w.fin);
        eq("vote recorded", w.sign(e, w.md, "REJECT", "Too expensive").get("outcome"), "one no");
        eq(DocStatus.IN_REVIEW, e.status, "still possible");
        eq("rejected", w.sign(e, w.dir2, "REJECT", "Agree, too much").get("outcome"), "second no -> cannot reach 2");
        eq(DocStatus.REJECTED, e.status, "rejected");
        Task left = w.store.tasksOf(e.id).stream().filter(t -> t.rule.endsWith(w.dir3.id)).findFirst().orElseThrow();
        refused(() -> w.p.flow().decide(w.dir3, left.id, "APPROVE", null), 409, "nothing left to sign");
    }

    static void parallel() {
        W w = new W();
        Document d = w.submitted(w.dev, "CON", 300_000);
        w.ok(d, w.pm);
        eq(2L, w.store.tasksOf(d.id).stream().filter(t -> t.state.open()).count(), "two seats open together");
        eq("waiting for more", w.ok(d, w.legal).get("outcome"), "Legal alone is not enough");
        eq("approved", w.ok(d, w.fin2).get("outcome"), "Finance too -> done");
        // one person in both groups may fill only one seat
        w.legal.groups.add("Finance");
        Document e = w.submitted(w.dev, "CON", 300_000);
        w.ok(e, w.pm);
        w.ok(e, w.legal);
        Task fin = w.store.tasksOf(e.id).stream().filter(t -> t.state.open()).findFirst().orElseThrow();
        check(fin.assignees.contains(w.legal.id), "Leela is also on the Finance seat");
        refused(() -> w.p.flow().decide(w.legal, fin.id, "APPROVE", null), 403, "but cannot fill it too");
    }

    static void distinct() {
        W w = new W();
        w.fin.groups.add("Procurement");      // Firoz is in Procurement and Finance
        Document d = w.submitted(w.dev, "PR", 80_000);
        w.ok(d, w.pm);
        Task procTask = w.task(d, w.fin);
        w.p.flow().decide(w.fin, procTask.id, "APPROVE", null);
        Task finTask = w.store.tasksOf(d.id).stream().filter(t -> t.state.open()).findFirst().orElseThrow();
        check(!finTask.candidates.contains(w.fin.id), "left out of the Finance seat because someone else can sign it");
        check(finTask.candidates.contains(w.fin2.id), "Fatima signs Finance");
        w.ok(d, w.fin2);
        eq(DocStatus.APPROVED, d.status, "done");
    }

    static void sendBack() {
        W w = new W();
        Document d = w.submitted(w.dev, "PR", 60_000);
        w.ok(d, w.pm);
        w.p.docs().comment(w.proc, d.id, 2, "Which model exactly?");
        refused(() -> w.sign(d, w.proc, "RETURN", ""), 400, "a reason is needed");
        eq("returned", w.sign(d, w.proc, "RETURN", "Add the model number").get("outcome"), "sent back");
        eq(DocStatus.RETURNED, d.status, "returned");
        check(w.store.tasksOf(d.id).stream().noneMatch(t -> t.state.open()), "no open tasks");
        refused(() -> w.p.docs().submit(w.dev, d.id, "Done as asked"), 400, "nothing changed yet");
        w.p.docs().update(w.dev, d.id, Json.obj("body", "line one\nline two: model X200\nline three"));
        refused(() -> w.p.docs().submit(w.dev, d.id, ""), 400, "change note needed");
        w.p.docs().submit(w.dev, d.id, "Model number added");
        eq(2, d.rev(), "revision B");
        eq("B", d.latest().letter(), "letter");
        check(!d.revisions.get(0).hash.equals(d.revisions.get(1).hash), "new fingerprint");
        eq(0, d.stageIndex, "starts again at the line manager");
        check(w.store.comments.values().stream().allMatch(c -> c.resolved), "old redlines closed");
        eq(1, w.p.views().diff(w.dev, d.id, 1, 2).get("changes") instanceof Integer i ? i / 2 : -1, "one line changed (removed + added)");
    }

    static void outcomes() {
        W w = new W();
        Document r = w.submitted(w.dev, "EXP", 2_000);
        refused(() -> w.sign(r, w.pm, "REJECT", "no"), 400, "reason too short");
        w.sign(r, w.pm, "REJECT", "Not a business expense");
        eq(DocStatus.REJECTED, r.status, "rejected");
        check(r.certificate == null && r.closedAt > 0, "no certificate");
        Document a = w.submitted(w.dev, "EXP", 2_000);
        w.ok(a, w.pm);
        Clock.advanceMillis(H);
        w.ok(a, w.dir2);
        eq(DocStatus.APPROVED, a.status, "approved");
        check(a.certificate != null && a.certificate.matches("[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}"), "certificate code " + a.certificate);
        Map<String, Object> v = w.p.views().verify(a.certificate.toLowerCase().replace("-", " "));
        eq(true, v.get("found"), "found by code");
        eq(true, v.get("ledgerOk"), "ledger fine");
        eq(2, ((List<?>) v.get("signers")).size(), "two signers");
        eq(false, w.p.views().verify("0000-0000-0000").get("found"), "unknown code");
        refused(() -> w.p.views().verify("123"), 400, "bad code");
        check((Boolean) w.p.flow().checkLedger().get("ok"), "whole ledger fine");
    }

    static void withdraw() {
        W w = new W();
        Document d = w.submitted(w.dev, "PR", 60_000);
        refused(() -> w.p.docs().withdraw(w.pm, d.id, "Not yours to withdraw"), 403, "only the author");
        refused(() -> w.p.docs().withdraw(w.dev, d.id, ""), 400, "reason needed");
        w.p.docs().withdraw(w.dev, d.id, "Plan changed");
        eq(DocStatus.WITHDRAWN, d.status, "withdrawn");
        check(w.store.tasksOf(d.id).stream().allMatch(t -> t.state == Task.State.CANCELLED), "tasks cancelled");
        refused(() -> w.p.docs().withdraw(w.dev, d.id, "again please"), 409, "closed");
    }

    static void delegation() {
        W w = new W();
        Document before = w.submitted(w.dev, "PR", 30_000);           // waits for pm already
        String today = Clock.today().toString(), later = Clock.today().plusDays(5).toString();
        Delegation g = w.p.flow().delegate(w.pm, Json.obj("toId", w.dev2.id, "start", today, "end", later));
        check(w.task(before, w.dev2) != null, "waiting work moved to the delegate");
        Document after = w.submitted(w.dev, "PR", 30_000);
        Task k = w.task(after, w.dev2);
        check(k != null && "delegate of U-7".equals(k.via.get(w.dev2.id)), "new work too, with the reason: " + (k == null ? null : k.via));
        w.ok(after, w.dev2);
        Task done = w.store.tasks.get(k.id);
        eq(w.pm.id, done.onBehalfOf, "signed for Prakash");
        // the delegate's own document never comes to them
        Document own = w.submitted(w.dev2, "PR", 30_000);
        check(w.task(own, w.dev2) == null, "not for your own document");
        // rules
        refused(() -> w.p.flow().delegate(w.pm, Json.obj("toId", w.fin.id, "start", today, "end", later)), 409, "one at a time");
        refused(() -> w.p.flow().delegate(w.dev2, Json.obj("toId", w.pm.id, "start", today, "end", later)), 409, "loop refused");
        refused(() -> w.p.flow().delegate(w.dev, Json.obj("toId", w.dev.id, "start", today, "end", later)), 400, "not to yourself");
        refused(() -> w.p.flow().delegate(w.dev, Json.obj("toId", w.fin.id, "start", later, "end", today)), 400, "end before start");
        refused(() -> w.p.flow().delegate(w.dev, Json.obj("toId", w.fin.id, "start", today, "end", Clock.today().plusDays(120).toString())), 400, "max 90 days");
        // a longer chain: dev2 -> fin while dev2 stands in for pm  => pm's work reaches fin too
        w.p.flow().delegate(w.dev2, Json.obj("toId", w.fin.id, "start", today, "end", later));
        Document chain = w.submitted(w.dev, "PR", 30_000);
        check(w.task(chain, w.fin) != null, "pm -> dev2 -> fin");
        w.p.flow().cancelDelegation(w.pm, g.id);
        Document none = w.submitted(w.dev, "PR", 30_000);
        check(w.task(none, w.dev2) == null, "cancelled delegation no longer applies");
    }

    static void deadlines() {
        W w = new W();
        Document d = w.submitted(w.dev, "PR", 30_000);                  // Mon 13:30, 8 office hours
        Task k = w.task(d, w.pm);
        eq(Instant.parse("2026-11-17T07:30:00Z"), Instant.ofEpochMilli(k.dueAt), "Mon 13:30 + 8 office hours = Tue 13:00");
        Clock.fix(Instant.parse("2026-11-17T04:00:00Z"));                // Tue 09:30 = 4.5 of 8 h used
        eq(0, ((Number) w.p.flow().tick().get("reminded")).intValue(), "not yet");
        Clock.fix(Instant.parse("2026-11-17T05:30:00Z"));                // Tue 11:00 = 6 of 8 h (75 %)
        eq(1, ((Number) w.p.flow().tick().get("reminded")).intValue(), "reminder at 75 %");
        eq(0, ((Number) w.p.flow().tick().get("reminded")).intValue(), "only once");
        Clock.fix(Instant.parse("2026-11-17T08:01:00Z"));
        eq(1, ((Number) w.p.flow().tick().get("escalated")).intValue(), "escalated when overdue");
        check(k.assignees.contains(w.dir2.id) && "escalated (overdue)".equals(k.via.get(w.dir2.id)), "the manager's manager can now decide");
        w.ok(d, w.dir2);
        eq(1, d.stageIndex, "moved on");
        Clock.fix(START);
        Document u = w.p.docs().create(w.dev, Json.obj("type", "PR", "title", "Urgent nets", "amount", 9_000L, "urgent", true, "body", "x"));
        w.p.docs().submit(w.dev, u.id, null);
        eq(Instant.parse("2026-11-16T12:00:00Z"), Instant.ofEpochMilli(w.task(u, w.pm).dueAt), "urgent: 4 h -> Mon 17:30");
    }

    static void fallback() {
        W w = new W();
        Document d = w.submitted(w.md, "EXP", 1_000);                    // the top person has no manager
        Task k = w.store.tasksOf(d.id).get(0);
        eq(List.of(w.ctl.id), k.candidates, "goes to the Document Controller");
        check(k.via.containsKey("fallback"), "with a note");
    }

    static void access() {
        W w = new W();
        Document d = w.submitted(w.dev, "PR", 60_000);
        refused(() -> w.p.views().doc(w.dev2, d.id), 404, "a colleague cannot read it");
        w.p.views().doc(w.pm, d.id);                                     // approver and manager
        w.p.views().doc(w.dir2, d.id);                                   // manager's manager
        w.p.views().doc(w.ctl, d.id);                                    // controller
        refused(() -> w.p.views().doc(w.proc, d.id), 404, "the next stage's people cannot read it yet");
        Document cc = w.p.docs().create(w.dev, Json.obj("type", "PR", "title", "With copy", "amount", 5L, "body", "x", "cc", List.of(w.dev2.id)));
        w.p.views().doc(w.dev2, cc.id);
        Document pol = w.p.docs().create(w.dev, Json.obj("type", "POL", "title", "Desk policy", "body", "Keep desks clean."));
        w.p.docs().submit(w.dev, pol.id, null);
        refused(() -> w.p.views().doc(w.legal, pol.id), 404, "policy in review is private");
        w.ok(pol, w.hr);
        w.p.views().doc(w.legal, pol.id);
        eq(1, ((List<?>) w.p.views().library(w.legal).get("docs")).size(), "in the library");
        refused(() -> w.p.views().reports(w.dev), 403, "reports for managers");
        refused(() -> w.p.views().admin(w.pm), 403, "desk for the controller");
    }

    static void redlines() {
        W w = new W();
        Document d = w.submitted(w.dev, "PR", 30_000);
        Comment c = w.p.docs().comment(w.pm, d.id, 2, "Which line two?");
        eq(1, c.rev, "on revision A");
        refused(() -> w.p.docs().comment(w.pm, d.id, 9, "No such line"), 400, "line range");
        refused(() -> w.p.docs().comment(w.dir2, d.id, 1, "Just looking"), 403, "readers without a task cannot write");
        refused(() -> w.p.docs().resolve(w.proc, c.id), 404, "others cannot close");
        w.p.docs().resolve(w.dev, c.id);
        check(c.resolved, "author closed it");
    }

    static void attachments() {
        W w = new W();
        Document d = w.draft(w.dev, "PR", 1_000);
        String pdf = Base64.getEncoder().encodeToString("%PDF-1.4 test".getBytes());
        refused(() -> w.p.docs().attach(w.dev, d.id, Json.obj("name", "x.exe", "data", Base64.getEncoder().encodeToString("MZ...".getBytes()))), 400, "not a PDF/PNG/JPG");
        refused(() -> w.p.docs().attach(w.dev, d.id, Json.obj("name", "big.pdf", "data", Base64.getEncoder().encodeToString(new byte[1_000_001]))), 400, "too big");
        w.p.docs().attach(w.dev, d.id, Json.obj("name", "quote.pdf", "data", pdf));
        eq("application/pdf", d.attachment.mime, "sniffed");
        check(w.store.getFile(d.attachment.sha) != null, "stored by fingerprint");
        w.p.docs().submit(w.dev, d.id, null);
        String h1 = d.latest().hash;
        eq(h1, com.cecil.swikriti.service.DocumentService.hash(d, d.latest()), "the fingerprint can be recomputed");
        Revision copy = Revision.fromJson(d.latest().toJson());
        copy.attachment = null;
        check(!com.cecil.swikriti.service.DocumentService.hash(d, copy).equals(h1), "removing the file changes the fingerprint");
    }

    static void templates() {
        W w = new W();
        refused(() -> w.p.admin().saveTemplate(w.dev, "PR", Json.obj("name", "Hacked")), 403, "controller only");
        refused(() -> w.p.admin().saveTemplate(w.ctl, null, Json.obj("code", "X1", "name", "Bad code", "stages", Json.parse("[" + W.st("A", "[\"MANAGER\"]", "ANY", 1, "", 8) + "]"))), 400, "code letters");
        refused(() -> w.p.admin().saveTemplate(w.ctl, "PR", Json.obj("stages", Json.parse("[" + W.st("A", "[\"MANAGER\"]", "ANY", 1, "amount >", 8) + "]"))), 400, "bad condition");
        refused(() -> w.p.admin().saveTemplate(w.ctl, "PR", Json.obj("stages", Json.parse("[" + W.st("A", "[\"GROUP:Nobody\"]", "ANY", 1, "", 8) + "]"))), 400, "empty group");
        refused(() -> w.p.admin().saveTemplate(w.ctl, "PR", Json.obj("stages", Json.parse("[" + W.st("A", "[\"MANAGER\"]", "QUORUM", 3, "", 8) + "]"))), 400, "quorum > seats");
        refused(() -> w.p.admin().saveTemplate(w.ctl, "PR", Json.obj("stages", Json.parse("[" + W.st("A", "[\"MANAGER\"]", "ANY", 1, "amount > 5", 8) + "]"))), 400, "every stage conditional");
        Document inFlight = w.submitted(w.dev, "PR", 30_000);
        Template t = w.p.admin().saveTemplate(w.ctl, "PR", Json.obj("stages", Json.parse("[" + W.st("Only HR", "[\"GROUP:HR\"]", "ANY", 1, "", 8) + "]")));
        eq(2, t.version, "version up");
        eq(4, inFlight.route.size(), "the document keeps its old route");
        w.ok(inFlight, w.pm);
        eq("Procurement", inFlight.route.get(inFlight.stageIndex).stage.name, "and goes on with it");
        Document fresh = w.submitted(w.dev, "PR", 30_000);
        eq(1, fresh.route.size(), "new documents use the new route");
    }

    static void lockout() {
        W w = new W();
        for (int i = 0; i < 5; i++) refused(() -> w.p.auth().login("dev", "wrong-pass1"), 401, "wrong");
        refused(() -> w.p.auth().login("dev", "member123"), 429, "locked even with the right password");
        Clock.advanceMillis(6 * 60_000);
        check(w.p.auth().login("dev", "member123") != null, "open again after 5 minutes");
        refused(() -> w.p.auth().login("nobody", "x"), 401, "same message for unknown user");
    }

    static void threads() throws Exception {
        for (int round = 0; round < 5; round++) {
            W w = new W();
            List<User> six = new ArrayList<>(), panel = new ArrayList<>();
            for (int i = 0; i < 6; i++) six.add(w.add("s" + i, "Seat " + i, Role.EMPLOYEE, w.md.id));
            for (int i = 0; i < 16; i++) panel.add(w.add("p" + i, "Person " + i, Role.EMPLOYEE, w.md.id, "Panel"));
            List<Object> seats = new ArrayList<>();
            for (User u : six) seats.add("USER:" + u.id);
            w.p.admin().saveTemplate(w.ctl, null, Json.obj("code", "VOTE", "name", "Vote", "stages",
                    List.of(Json.obj("name", "3 of 6", "seats", seats, "mode", "QUORUM", "quorum", 3L, "slaHours", 8L),
                            Json.obj("name", "Anyone of 16", "seats", List.of("GROUP:Panel"), "mode", "ANY", "slaHours", 8L))));
            Document d = w.p.docs().submit(w.dev, w.p.docs().create(w.dev, Json.obj("type", "VOTE", "title", "Panel vote", "body", "x")).id, null);
            eq(3, race(w, d, six), "exactly 3 of the 6 seats signed");
            eq(1, d.stageIndex, "the first stage completed once");
            eq(1, race(w, d, panel), "exactly one of 16 signed the group seat");
            eq(DocStatus.APPROVED, d.status, "approved once");
            eq(2L, w.store.ledger.stream().filter(e -> e.docId().equals(d.id) && !e.action().equals("APPROVE")).count(), "one SUBMIT and one CERTIFIED");
            check((Boolean) w.p.flow().checkLedger().get("ok"), "chain intact after the race");
        }
    }

    /** All people try to approve at the same moment; returns how many succeeded. */
    static int race(W w, Document d, List<User> people) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger yes = new AtomicInteger();
        List<Thread> ts = new ArrayList<>();
        for (User u : people) {
            Thread t = new Thread(() -> {
                try {
                    go.await();
                    Task k = w.task(d, u);
                    if (k != null) { w.p.flow().decide(u, k.id, "APPROVE", null); yes.incrementAndGet(); }
                } catch (Problem | InterruptedException refusedOrLate) { /* someone else was first */ }
            });
            t.start();
            ts.add(t);
        }
        go.countDown();
        for (Thread t : ts) t.join();
        return yes.get();
    }

    static void web() throws Exception {
        W w = new W();
        Path front = Files.createTempDirectory("swikriti-front");
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
            eq(401, http.send(HttpRequest.newBuilder(URI.create(base + "/api/inbox")).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "sign in first");
            eq(200, http.send(HttpRequest.newBuilder(URI.create(base + "/api/public/verify?code=0000-0000-0000")).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "public check works without sign-in");
            String login = "{\"username\":\"dev\",\"password\":\"member123\"}";
            eq(403, http.send(HttpRequest.newBuilder(URI.create(base + "/api/login")).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(login)).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "no app header -> blocked");
            eq(403, http.send(HttpRequest.newBuilder(URI.create(base + "/api/login")).header("Content-Type", "application/json").header("X-Swikriti", "1")
                    .header("Origin", "http://evil.example").POST(HttpRequest.BodyPublishers.ofString(login)).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "other website -> blocked");
            eq(415, http.send(HttpRequest.newBuilder(URI.create(base + "/api/login")).header("Content-Type", "text/plain").header("X-Swikriti", "1")
                    .POST(HttpRequest.BodyPublishers.ofString(login)).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "JSON only");
            HttpResponse<String> in = http.send(HttpRequest.newBuilder(URI.create(base + "/api/login")).header("Content-Type", "application/json").header("X-Swikriti", "1")
                    .POST(HttpRequest.BodyPublishers.ofString(login)).build(), HttpResponse.BodyHandlers.ofString());
            eq(200, in.statusCode(), "signed in");
            String cookie = in.headers().firstValue("Set-Cookie").orElse("");
            check(cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict"), "safe cookie");
            String c = cookie.split(";")[0];
            eq(200, http.send(HttpRequest.newBuilder(URI.create(base + "/api/inbox")).header("Cookie", c).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "inbox with cookie");
            HttpResponse<String> bad = http.send(HttpRequest.newBuilder(URI.create(base + "/api/docs")).header("Cookie", c).header("Content-Type", "application/json")
                    .header("X-Swikriti", "1").POST(HttpRequest.BodyPublishers.ofString("{\"type\":\"PR\",\"title\":\"x\"}")).build(), HttpResponse.BodyHandlers.ofString());
            eq(400, bad.statusCode(), "friendly 400");
            check(bad.body().contains("at least 3 characters"), "with the message");
            eq(403, http.send(HttpRequest.newBuilder(URI.create(base + "/api/admin")).header("Cookie", c).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "not the controller");
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
        eq(20, s.users.size(), "20 people");
        eq(6, s.templates.size(), "6 document types");
        check(s.docs.size() > 100, "many documents: " + s.docs.size());
        int approved = 0, rejected = 0, revised = 0;
        Set<String> numbers = new HashSet<>();
        for (Document d : s.docs.values()) {
            if (d.number != null) check(numbers.add(d.number), "unique number " + d.number);
            if (d.status == DocStatus.APPROVED) { approved++; check(d.certificate != null, "certificate"); }
            if (d.status == DocStatus.REJECTED) rejected++;
            if (d.rev() > 1) revised++;
            for (Task k : s.tasksOf(d.id)) {
                if (k.decidedBy != null && (k.state == Task.State.APPROVED || k.state == Task.State.REJECTED || k.state == Task.State.RETURNED))
                    check(!k.decidedBy.equals(d.authorId), "four eyes: " + d.number);
                check(!k.assignees.contains(d.authorId), "the author is never assigned");
            }
        }
        check(approved > 60 && rejected >= 3 && revised >= 5, "a real history: " + approved + "/" + rejected + "/" + revised);
        check((Boolean) p.flow().checkLedger().get("ok"), "the ledger chain is intact");
        check(SeedDataCheck.ok(), "document numbers have the right shape");
        User rahul = s.userByName("rahul");
        check(((List<?>) p.views().inbox(rahul).get("waiting")).stream().anyMatch(m -> String.valueOf(((Map<?, ?>) m).get("via")).startsWith("delegate of")),
                "Rahul signs for Farah");
        User ankit = s.userByName("ankit");
        check(s.docs.values().stream().anyMatch(d -> d.authorId.equals(ankit.id) && d.status == DocStatus.RETURNED), "Ankit has a drawing sent back");
        check(s.docs.values().stream().anyMatch(d -> d.authorId.equals(ankit.id) && d.status == DocStatus.APPROVED && d.rev() == 2), "and an approved revision B");
    }

    /** Small bridge to the package-private self check in SeedData. */
    static final class SeedDataCheck {
        static boolean ok() {
            try {
                var m = SeedData.class.getDeclaredMethod("selfCheck");
                m.setAccessible(true);
                return (Boolean) m.invoke(null);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
