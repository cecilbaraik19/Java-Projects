package com.cecil.sikho;

import com.cecil.sikho.ledger.TimeBank;
import com.cecil.sikho.match.Geo;
import com.cecil.sikho.match.Matcher;
import com.cecil.sikho.match.SearchIndex;
import com.cecil.sikho.match.Trust;
import com.cecil.sikho.model.Circle;
import com.cecil.sikho.model.Exchange;
import com.cecil.sikho.model.Exchange.Status;
import com.cecil.sikho.model.LedgerEntry;
import com.cecil.sikho.model.Listing;
import com.cecil.sikho.model.Report;
import com.cecil.sikho.model.Review;
import com.cecil.sikho.model.Role;
import com.cecil.sikho.model.User;
import com.cecil.sikho.service.Problem;
import com.cecil.sikho.service.Safety;
import com.cecil.sikho.store.DataStore;
import com.cecil.sikho.store.SeedData;
import com.cecil.sikho.util.Clock;
import com.cecil.sikho.util.Json;
import com.cecil.sikho.util.Passwords;
import com.cecil.sikho.web.ApiServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * All the backend tests, with no test library to download: each test is a method, a failed check throws, and the
 * runner prints PASS / FAIL for each one. The clock is frozen at Friday 13 Nov 2026, 10:30 India time, so the
 * results never change from day to day.
 *
 * Run: run-tests.bat (Windows) or ./run-tests.sh
 */
public final class AllTests {
    static final Instant NOW = Instant.parse("2026-11-13T05:00:00Z");
    static final long H = 3_600_000L, D = 24 * H;

    private static int passed, failed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        List<Object[]> tests = new ArrayList<>();
        for (var m : AllTests.class.getDeclaredMethods()) {
            if (m.getName().startsWith("test") && m.getParameterCount() == 0) tests.add(new Object[] { m.getName(), m });
        }
        tests.sort((a, b) -> ((String) a[0]).compareTo((String) b[0]));
        System.out.println("Running " + tests.size() + " tests (clock frozen at " + NOW + ")");
        for (Object[] t : tests) {
            Clock.fix(NOW);
            String name = (String) t[0];
            try {
                ((java.lang.reflect.Method) t[1]).invoke(null);
                passed++;
                System.out.println("  PASS  " + name);
            } catch (java.lang.reflect.InvocationTargetException e) {
                failed++;
                failures.add(name + ": " + e.getCause());
                System.out.println("  FAIL  " + name + "  -> " + e.getCause());
            }
        }
        Clock.fix(null);
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) {
            failures.forEach(f -> System.out.println("  " + f));
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ helpers

    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    static void eq(Object expected, Object actual, String what) {
        if (expected == null ? actual != null : !expected.equals(actual)) throw new AssertionError(what + ": expected " + expected + " but got " + actual);
    }

    static void fails(Runnable r, String containing) {
        try {
            r.run();
        } catch (Problem p) {
            if (containing != null && !p.getMessage().toLowerCase().contains(containing.toLowerCase()))
                throw new AssertionError("Wrong message: " + p.getMessage() + " (expected it to contain '" + containing + "')");
            return;
        }
        throw new AssertionError("Expected a Problem containing '" + containing + "'");
    }

    /** A small, empty community: places and skills only, and the parts on top. */
    static final class World {
        final DataStore store = new DataStore(null);
        final ApiServer.Parts p;
        World() {
            SeedData.placesAndSkills(store);
            p = App.parts(store);
        }
        User member(String name, String locality) { return SeedData.quickMember(store, p.bank(), name, locality); }
        String skill(String name) {
            for (var s : store.skills.values()) if (s.name.equals(name)) return s.id;
            throw new IllegalStateException(name);
        }
        Listing card(User u, Listing.Type type, String skill, Listing.Mode mode) {
            return p.listings().create(u, Json.obj("type", type.name(), "skillId", skill(skill), "mode", mode.name(), "level", "EXPERT"));
        }
        Exchange ask(User learner, Listing offer, int hours, long at) {
            return p.exchanges().request(learner, offer.id, Json.obj("hours", hours, "at", at, "message", "Hello!"));
        }
        int bal(User u) { return p.bank().balance(u.id); }
        Map<String, Object> verify() {
            Map<String, Integer> exp = new HashMap<>();
            p.exchanges().expectedEscrow(exp);
            p.circles().expectedEscrow(exp);
            return p.bank().verify(exp);
        }
    }

    static long now() { return Clock.millis(); }

    // ------------------------------------------------------------------ utilities

    static void test01_jsonRoundTrip() {
        String text = "{\"a\":[1,2.5,\"x<y\",true,null],\"b\":{\"c\":\"line\\nbreak\"}}";
        Map<String, Object> m = Json.parseObject(text);
        eq(2.5, ((List<?>) m.get("a")).get(1), "decimal");
        check(Json.write(m).contains("\\u003c"), "< is escaped");
        eq(m, Json.parseObject(Json.write(m)), "round trip");
        try { Json.parse("{\"a\":1} extra"); throw new AssertionError("should fail"); } catch (IllegalArgumentException ok) { }
    }

    static void test02_passwords() {
        String h = Passwords.hash("member123");
        check(Passwords.verify("member123", h), "right password");
        check(!Passwords.verify("member124", h), "wrong password");
        check(!h.contains("member123"), "not stored in clear");
        check(Passwords.problem("short1") != null && Passwords.problem("longpassword") != null && Passwords.problem("longpass1") == null, "rules");
    }

    static void test03_levenshtein() {
        eq(1, SearchIndex.levenshtein("gitar", "guitar", 2), "insert one letter");
        eq(3, SearchIndex.levenshtein("kitten", "sitting", 5), "classic example");
        eq(2, SearchIndex.levenshtein("abcdef", "zzzzzz", 1), "stops early at limit+1");
    }

    static void test04_searchSynonymsAndSpelling() {
        Map<String, String> docs = new java.util.LinkedHashMap<>();
        docs.put("cook", "Jharkhandi cooking dhuska litti recipe");
        docs.put("music", "Guitar chords strumming songs");
        docs.put("code", "Python programming for beginners");
        SearchIndex idx = new SearchIndex(docs);
        Map<String, Double> s = idx.scores(idx.prepare("khana"));
        check(s.containsKey("cook") && !s.containsKey("music"), "khana finds cooking: " + s);
        SearchIndex.Query q = idx.prepare("gitar lessons");
        eq("guitar", q.corrected().get("gitar"), "spelling fixed");
        check(idx.scores(q).containsKey("music"), "finds guitar");
        check(idx.scores(idx.prepare("coding")).containsKey("code"), "coding → programming");
    }

    static void test05_distance() {
        double km = Geo.km(23.3702, 85.3311, 23.3958, 85.3335);   // Lalpur → Morabadi
        check(km > 2.7 && km < 3.0, "about 2.9 km, got " + km);
        eq(0.5, Geo.shown(0.1), "never less than 0.5 km");
        eq(3.0, Geo.shown(2.6), "rounded up to half km");
    }

    // ------------------------------------------------------------------ time bank

    static void test10_welcomeAndNeverBelowZero() {
        World w = new World();
        User a = w.member("asha", "lalpur");
        eq(3, w.bal(a), "welcome credits");
        fails(() -> w.p.bank().post(a.id, TimeBank.ESCROW, 4, "HOLD", "X", null), "not enough");
        fails(() -> w.p.bank().post(a.id, a.id, 1, "GIFT", null, null), "different");
        fails(() -> w.p.bank().post(a.id, TimeBank.ESCROW, 0, "HOLD", null, null), "at least 1");
        check(Boolean.TRUE.equals(w.verify().get("ok")), "ledger ok");
    }

    static void test11_ledgerTamperFound() {
        World w = new World();
        User a = w.member("asha", "lalpur"), b = w.member("bina", "lalpur");
        w.p.bank().gift(a, b, 2, "thanks");
        check(Boolean.TRUE.equals(w.verify().get("ok")), "ok before");
        LedgerEntry e = w.store.ledger.get(2);
        e.amount = 5;                                                    // someone edits the file
        Map<String, Object> v = w.verify();
        check(Boolean.FALSE.equals(v.get("ok")), "tamper found");
        check(v.get("problems").toString().contains("Line 3"), "names the line: " + v.get("problems"));
    }

    static void test12_giftLimit() {
        World w = new World();
        User a = w.member("asha", "lalpur"), b = w.member("bina", "lalpur");
        w.p.bank().post(TimeBank.BANK, a.id, 10, "ADJUST", null, "test");
        w.p.bank().gift(a, b, 3, "one");
        fails(() -> w.p.bank().gift(a, b, 3, "two"), "at most 5");
        w.p.bank().gift(a, b, 2, "two");
        Clock.advanceMillis(D + 1);
        w.p.bank().gift(a, b, 1, "next day");
        eq(9, w.bal(b), "3 + 3 + 2 + 1");
    }

    // ------------------------------------------------------------------ exchanges

    static void test20_requestHoldsCredits() {
        World w = new World();
        User t = w.member("tara", "lalpur"), l = w.member("lalit", "lalpur");
        Listing o = w.card(t, Listing.Type.OFFER, "Guitar", Listing.Mode.IN_PERSON);
        Exchange e = w.ask(l, o, 2, now() + 2 * D);
        eq(1, w.bal(l), "2 of 3 credits held");
        eq(2, w.p.bank().heldFor(e.id), "escrow holds 2");
        eq(Status.REQUESTED, e.status, "status");
        check(Boolean.TRUE.equals(w.verify().get("ok")), "ledger ok");
        fails(() -> w.ask(l, o, 2, now() + 3 * D), "not enough");
    }

    static void test21_declineRefunds() {
        World w = new World();
        User t = w.member("tara", "lalpur"), l = w.member("lalit", "lalpur");
        Exchange e = w.ask(l, w.card(t, Listing.Type.OFFER, "Guitar", Listing.Mode.IN_PERSON), 2, now() + 2 * D);
        fails(() -> w.p.exchanges().accept(l, e.id), "wait");
        w.p.exchanges().decline(t, e.id, "busy");
        eq(Status.DECLINED, e.status, "declined");
        eq(3, w.bal(l), "credits back");
        eq(0, w.p.bank().heldFor(e.id), "escrow empty");
    }

    static void test22_bothDonePaysTeacher() {
        World w = new World();
        User t = w.member("tara", "lalpur"), l = w.member("lalit", "lalpur");
        Exchange e = w.ask(l, w.card(t, Listing.Type.OFFER, "Guitar", Listing.Mode.IN_PERSON), 2, now() + 2 * D);
        w.p.exchanges().accept(t, e.id);
        fails(() -> w.p.exchanges().done(t, e.id), "not started");
        Clock.advanceMillis(2 * D + 2 * H);
        w.p.exchanges().done(t, e.id);
        eq(Status.ACCEPTED, e.status, "waits for the learner");
        w.p.exchanges().done(l, e.id);
        eq(Status.COMPLETED, e.status, "completed");
        eq(5, w.bal(t), "teacher 3 + 2");
        eq(1, w.bal(l), "learner 3 - 2");
        check(Boolean.TRUE.equals(w.verify().get("ok")), "ledger ok");
    }

    static void test23_autoCompleteAfterThreeDays() {
        World w = new World();
        User t = w.member("tara", "lalpur"), l = w.member("lalit", "lalpur");
        Exchange e = w.ask(l, w.card(t, Listing.Type.OFFER, "Guitar", Listing.Mode.IN_PERSON), 1, now() + D);
        w.p.exchanges().accept(t, e.id);
        Clock.advanceMillis(D + 2 * H);
        w.p.exchanges().done(t, e.id);
        Clock.advanceMillis(2 * D);
        eq(0, w.p.exchanges().tick(), "not yet");
        Clock.advanceMillis(D);
        eq(1, w.p.exchanges().tick(), "now");
        eq(Status.COMPLETED, e.status, "completed by itself");
        eq(4, w.bal(t), "teacher paid");
    }

    static void test24_expiryAndNotConfirmed() {
        World w = new World();
        User t = w.member("tara", "lalpur"), l = w.member("lalit", "lalpur");
        Listing o = w.card(t, Listing.Type.OFFER, "Guitar", Listing.Mode.IN_PERSON);
        Exchange unanswered = w.ask(l, o, 1, now() + 2 * H);
        Exchange silent = w.ask(l, o, 1, now() + 3 * H);
        w.p.exchanges().accept(t, silent.id);
        Clock.advanceMillis(4 * H);
        w.p.exchanges().tick();
        eq(Status.EXPIRED, unanswered.status, "expired");
        eq(Status.ACCEPTED, silent.status, "still waiting");
        Clock.advanceMillis(7 * D);
        w.p.exchanges().tick();
        eq(Status.NOT_CONFIRMED, silent.status, "nobody confirmed");
        eq(3, w.bal(l), "all credits back");
        check(Boolean.TRUE.equals(w.verify().get("ok")), "ledger ok");
    }

    static void test25_lateCancelHurtsReliability() {
        World w = new World();
        User t = w.member("tara", "lalpur"), l = w.member("lalit", "lalpur");
        Listing o = w.card(t, Listing.Type.OFFER, "Guitar", Listing.Mode.IN_PERSON);
        Exchange early = w.ask(l, o, 1, now() + 5 * D);
        w.p.exchanges().accept(t, early.id);
        w.p.exchanges().cancel(t, early.id, "travel");
        check(!early.lateCancel, "5 days before is fine");
        Exchange late = w.ask(l, o, 1, now() + 10 * H);
        w.p.exchanges().accept(t, late.id);
        double before = new Trust(w.store).of(t.id).reliability();
        w.p.exchanges().cancel(t, late.id, "sorry");
        check(late.lateCancel, "10 hours before is late");
        check(new Trust(w.store).of(t.id).reliability() < before, "reliability went down");
        eq(3, w.bal(l), "learner refunded both times");
    }

    static void test26_teacherOffersHoldsAtAccept() {
        World w = new World();
        User t = w.member("tara", "lalpur"), l = w.member("lalit", "lalpur");
        Listing want = w.card(l, Listing.Type.WANT, "Excel", Listing.Mode.IN_PERSON);
        Exchange e = w.p.exchanges().request(t, want.id, Json.obj("hours", 2, "at", now() + 2 * D, "message", "I can help!"));
        eq(t.id, e.teacherId, "the one who offers teaches");
        eq(3, w.bal(l), "nothing held yet");
        w.p.exchanges().accept(l, e.id);
        eq(1, w.bal(l), "held when the learner accepts");
        check(Boolean.TRUE.equals(w.verify().get("ok")), "ledger ok");
    }

    static void test27_requestRules() {
        World w = new World();
        User t = w.member("tara", "lalpur"), l = w.member("lalit", "lalpur");
        Listing o = w.card(t, Listing.Type.OFFER, "Guitar", Listing.Mode.IN_PERSON);
        fails(() -> w.ask(t, o, 1, now() + D), "your own");
        fails(() -> w.ask(l, o, 4, now() + D), "1 to 3");
        fails(() -> w.ask(l, o, 1, now() + 10 * 60_000), "one hour");
        fails(() -> w.ask(l, o, 1, now() + 40 * D), "30 days");
        w.p.auth().block(t, l.id, true);
        fails(() -> w.ask(l, o, 1, now() + D), "cannot send");
    }

    static void test28_safetyHidesContactsAndFlagsMoney() {
        Safety.Checked c = Safety.check("Call me on 98765 43210 or a@b.in", false);
        check(!c.text().contains("98765") && !c.text().contains("a@b.in"), "hidden: " + c.text());
        eq("CONTACT_HIDDEN", c.flag(), "flag");
        eq("98765 43210", Safety.check("Call me on 98765 43210", true).text().substring(11), "shown after accept");
        eq("MONEY", Safety.check("Please pay 500 rupees by UPI", true).flag(), "money");
        eq("MONEY", Safety.check("fees ₹300", true).flag(), "rupee sign");
        eq(null, Safety.check("See you at the library!", false).flag(), "normal message");
    }

    static void test29_reviewsOnceAndInRange() {
        World w = new World();
        User t = w.member("tara", "lalpur"), l = w.member("lalit", "lalpur");
        Exchange e = w.ask(l, w.card(t, Listing.Type.OFFER, "Guitar", Listing.Mode.IN_PERSON), 1, now() + D);
        fails(() -> w.p.exchanges().review(l, e.id, Json.obj("stars", 5)), "after the session");
        w.p.exchanges().accept(t, e.id);
        Clock.advanceMillis(D + 2 * H);
        w.p.exchanges().done(t, e.id);
        w.p.exchanges().done(l, e.id);
        fails(() -> w.p.exchanges().review(l, e.id, Json.obj("stars", 6)), "1 to 5");
        Review r = w.p.exchanges().review(l, e.id, Json.obj("stars", 4, "tags", List.of("Patient", "Made-up tag")));
        eq(List.of("Patient"), r.tags, "unknown tags dropped");
        check(r.aboutTeacher, "about the teacher");
        fails(() -> w.p.exchanges().review(l, e.id, Json.obj("stars", 5)), "already");
    }

    static void test30_disputeSplitAndFault() {
        World w = new World();
        User t = w.member("tara", "lalpur"), l = w.member("lalit", "lalpur"), mod = w.member("mona", "lalpur");
        mod.role = Role.MODERATOR;
        Listing o = w.card(t, Listing.Type.OFFER, "Guitar", Listing.Mode.IN_PERSON);
        Exchange a = w.ask(l, o, 2, now() + D);
        w.p.exchanges().accept(t, a.id);
        Clock.advanceMillis(D + 3 * H);
        w.p.exchanges().dispute(l, a.id, "He did not come");
        eq(Status.DISPUTED, a.status, "disputed");
        fails(() -> w.p.exchanges().resolve(l, a.id, "LEARNER", "me"), "moderators");
        w.p.exchanges().resolve(mod, a.id, "SPLIT", "both waited");
        eq(4, w.bal(t), "teacher +1");
        eq(2, w.bal(l), "learner 1 + 1 back");
        eq(null, a.faultOf, "no fault in a split");

        Exchange b = w.ask(l, o, 1, now() + D);
        w.p.exchanges().accept(t, b.id);
        Clock.advanceMillis(D + 2 * H);
        w.p.exchanges().dispute(l, b.id, "no show");
        w.p.exchanges().resolve(mod, b.id, "LEARNER", "teacher admitted it");
        eq(t.id, b.faultOf, "teacher's fault");
        eq(1, new Trust(w.store).of(t.id).letDowns(), "counts as a let-down");
        check(Boolean.TRUE.equals(w.verify().get("ok")), "ledger ok");
    }

    // ------------------------------------------------------------------ circles

    static void test40_circleJoinLeaveComplete() {
        World w = new World();
        User host = w.member("hema", "lalpur"), a = w.member("asha", "lalpur"), b = w.member("bina", "kokar");
        Circle c = w.p.circles().create(host, Json.obj("skillId", w.skill("Yoga"), "title", "Morning yoga", "place", "Park",
                "localityId", "lalpur", "at", now() + 2 * D, "hours", 1, "seats", 2));
        w.p.circles().join(a, c.id);
        w.p.circles().join(b, c.id);
        fails(() -> w.p.circles().join(w.member("chandu", "lalpur"), c.id), "full");
        w.p.circles().leave(b, c.id);
        eq(3, w.bal(b), "left: credit back");
        fails(() -> w.p.circles().complete(host, c.id), "not started");
        Clock.advanceMillis(2 * D + H);
        fails(() -> w.p.circles().leave(a, c.id), "started");
        w.p.circles().complete(host, c.id);
        eq(4, w.bal(host), "host got 1 credit");
        eq(Circle.Status.DONE, c.status, "done");
        check(Boolean.TRUE.equals(w.verify().get("ok")), "ledger ok");
    }

    static void test41_circleNotClosedIsRefunded() {
        World w = new World();
        User host = w.member("hema", "lalpur"), a = w.member("asha", "lalpur");
        Circle c = w.p.circles().create(host, Json.obj("skillId", w.skill("Yoga"), "title", "Yoga", "place", "Park",
                "localityId", "lalpur", "at", now() + D, "hours", 1, "seats", 5));
        w.p.circles().join(a, c.id);
        Clock.advanceMillis(4 * D + H);
        eq(1, w.p.circles().tick(), "closed by the job");
        eq(Circle.Status.CANCELLED, c.status, "cancelled");
        eq(3, w.bal(a), "refunded");
    }

    // ------------------------------------------------------------------ matching

    static void test50_matcherRanksAndFilters() {
        World w = new World();
        User me = w.member("meena", "lalpur");
        me.maxKm = 5;
        User near = w.member("neel", "lalpur"), far = w.member("farah", "kanke"), mid = w.member("mohan", "kokar");
        near.availability = new ArrayList<>(me.availability);
        Listing nearGuitar = w.card(near, Listing.Type.OFFER, "Guitar", Listing.Mode.IN_PERSON);
        Listing farGuitar = w.card(far, Listing.Type.OFFER, "Guitar", Listing.Mode.IN_PERSON);       // ~6.5 km: too far
        Listing farOnline = w.card(far, Listing.Type.OFFER, "Keyboard", Listing.Mode.ONLINE);
        Listing midTabla = w.card(mid, Listing.Type.OFFER, "Tabla", Listing.Mode.IN_PERSON);
        List<Matcher.Result> r = w.p.matcher().search(me, Listing.Type.OFFER, new Matcher.Want(w.skill("Guitar"), null, Listing.Level.BEGINNER),
                null, 0, null, 10).results();
        List<String> ids = r.stream().map(x -> x.listing().id).toList();
        eq(nearGuitar.id, ids.get(0), "exact skill nearby first");
        check(!ids.contains(farGuitar.id), "in-person card beyond 5 km left out");
        check(ids.contains(farOnline.id), "online card from anywhere");
        check(ids.contains(midTabla.id), "same category (Music) still shown");
        eq(100L, r.get(0).parts().get("skill"), "skill fit 100%");
        w.p.auth().block(me, near.id, true);
        check(w.p.matcher().search(me, Listing.Type.OFFER, null, null, 0, null, 10).results().stream().noneMatch(x -> x.listing().id.equals(nearGuitar.id)), "blocked hidden");
    }

    static void test51_swapPairsAndCircles() {
        World w = new World();
        User a = w.member("anu", "lalpur"), b = w.member("bablu", "lalpur"), c = w.member("chhaya", "lalpur");
        w.card(a, Listing.Type.OFFER, "Guitar", Listing.Mode.IN_PERSON);
        w.card(b, Listing.Type.WANT, "Guitar", Listing.Mode.IN_PERSON);
        w.card(b, Listing.Type.OFFER, "Excel", Listing.Mode.IN_PERSON);
        w.card(c, Listing.Type.WANT, "Excel", Listing.Mode.IN_PERSON);
        w.card(c, Listing.Type.OFFER, "Yoga", Listing.Mode.IN_PERSON);
        w.card(a, Listing.Type.WANT, "Yoga", Listing.Mode.IN_PERSON);
        Map<String, Object> all = w.p.swaps().find(null);
        eq(0, all.get("pairCount"), "no pair");
        eq(1, all.get("circleCount"), "one circle of three, listed once");
        w.card(a, Listing.Type.WANT, "Excel", Listing.Mode.IN_PERSON);   // now a and b can also swap directly
        eq(1, w.p.swaps().find(a.id).get("pairCount"), "direct swap a ↔ b");
        eq(0, w.p.swaps().find(c.id).get("pairCount"), "c is not in the pair");
    }

    static void test52_trustBayesian() {
        World w = new World();
        User t = w.member("tara", "lalpur"), l = w.member("lalit", "lalpur");
        Review r = new Review();
        r.id = "R-1"; r.toId = t.id; r.fromId = l.id; r.stars = 5;
        w.store.reviews.put(r.id, r);
        Review r2 = new Review();
        r2.id = "R-2"; r2.toId = l.id; r2.fromId = t.id; r2.stars = 3;
        w.store.reviews.put(r2.id, r2);
        Trust trust = new Trust(w.store);
        eq(4.0, trust.communityMean(), "mean of 5 and 3");
        check(Math.abs(trust.of(t.id).rating() - 4.25) < 1e-9, "(3×4 + 5) / 4 = 4.25, not 5");
        check(Math.abs(trust.of("nobody").reliability() - 2.0 / 3) < 1e-9, "new member 67%");
    }

    // ------------------------------------------------------------------ accounts and moderation

    static void test60_loginLockAndJoin() {
        World w = new World();
        Map<String, Object> body = Json.obj("username", "Rani", "name", "Rani Devi", "password", "secret123", "localityId", "kokar", "agree", true, "phone", "+91 98765-43210");
        User u = w.p.auth().register(body);
        eq("rani", u.username, "lower case");
        eq("9876543210", u.phone, "phone cleaned");
        eq(3, w.bal(u), "welcome credits");
        fails(() -> w.p.auth().register(body), "taken");
        fails(() -> w.p.auth().register(Json.obj("username", "x1y", "name", "Xavi", "password", "secret123", "localityId", "kokar", "agree", false)), "rules");
        for (int i = 0; i < 4; i++) fails(() -> w.p.auth().login("rani", "wrong"), "wrong");
        fails(() -> w.p.auth().login("rani", "wrong"), "wrong");
        fails(() -> w.p.auth().login("rani", "secret123"), "try again");
        Clock.advanceMillis(6 * 60_000);
        check(w.p.auth().userFor(w.p.auth().login("rani", "secret123")) != null, "unlocked after 5 minutes");
    }

    static void test61_suspendCleansUp() {
        World w = new World();
        User t = w.member("tara", "lalpur"), l = w.member("lalit", "lalpur"), mod = w.member("mona", "lalpur");
        mod.role = Role.MODERATOR;
        Exchange e = w.ask(l, w.card(t, Listing.Type.OFFER, "Guitar", Listing.Mode.IN_PERSON), 2, now() + D);
        Circle c = w.p.circles().create(t, Json.obj("skillId", w.skill("Guitar"), "title", "Jam", "place", "Hall", "localityId", "lalpur", "at", now() + 3 * D));
        w.p.circles().join(l, c.id);
        eq(0, w.bal(l), "2 held + 1 seat");
        Report r = w.p.moderation().report(l, Json.obj("targetId", t.id, "reason", "MONEY", "text", "asked for fees"));
        fails(() -> w.p.moderation().report(l, Json.obj("targetId", t.id, "reason", "MONEY", "text", "again")), "already");
        w.p.moderation().resolve(mod, r.id, "SUSPEND", "money");
        check(t.suspended, "paused");
        eq(Status.CANCELLED, e.status, "exchange cancelled");
        eq(Circle.Status.CANCELLED, c.status, "circle cancelled");
        eq(3, w.bal(l), "all credits back to the learner");
        fails(() -> w.p.listings().get(w.store.listings.values().iterator().next().id), "not found");
        check(Boolean.TRUE.equals(w.verify().get("ok")), "ledger ok");
        w.p.moderation().reinstate(mod, t.id);
        check(!t.suspended, "back");
    }

    static void test62_viewsDoNotLeakPrivateData() {
        World w = new World();
        User t = w.member("tara", "lalpur"), l = w.member("lalit", "lalpur");
        t.phone = "9876543210";
        Exchange e = w.ask(l, w.card(t, Listing.Type.OFFER, "Guitar", Listing.Mode.IN_PERSON), 1, now() + D);
        String before = Json.write(w.p.views().exchange(e, l, new Trust(w.store), true)) + Json.write(w.p.insights().profile(l, t.id));
        check(!before.contains("9876543210"), "no phone before accepting");
        check(!before.contains("passwordHash") && !before.contains("\"lat\""), "no hash, no home position");
        w.p.exchanges().accept(t, e.id);
        check(Json.write(w.p.views().exchange(e, l, new Trust(w.store), true)).contains("9876543210"), "phone after accepting");
    }

    // ------------------------------------------------------------------ the demo community

    static void test70_demoCommunity() {
        DataStore store = new DataStore(null);
        ApiServer.Parts p = App.parts(store);
        SeedData.load(store, p.bank(), p.exchanges(), p.circles(), p.moderation());
        eq(NOW, Clock.now(), "clock put back");
        check(store.users.size() == 46 && store.skills.size() == 55, "46 members, 55 skills: " + store.users.size() + ", " + store.skills.size());
        Map<String, Integer> exp = new HashMap<>();
        p.exchanges().expectedEscrow(exp);
        p.circles().expectedEscrow(exp);
        Map<String, Object> v = p.bank().verify(exp);
        check(Boolean.TRUE.equals(v.get("ok")), "demo ledger is valid: " + v.get("problems"));
        User ananya = store.userByName("ananya");
        long waiting = store.exchanges.values().stream().filter(e -> e.status == Status.REQUESTED && e.teacherId.equals(ananya.id)).count();
        eq(1L, waiting, "Kabir's request waits for Ananya");
        check(store.exchanges.values().stream().anyMatch(e -> e.status == Status.DISPUTED), "a dispute");
        eq(2L, store.reports.values().stream().filter(r -> "OPEN".equals(r.status)).count(), "two open reports");
        check(store.userByName("gaurav").suspended, "Gaurav paused earlier");
        check(new Trust(store).of(ananya.id).reviews() >= 5, "Ananya has reviews");
        eq(0, p.exchanges().tick(), "nothing stale right after building");
        check(((Number) p.swaps().find(ananya.id).get("circleCount")).intValue() >= 1, "Ananya is in a swap circle");
    }

    // ------------------------------------------------------------------ the web server

    static void test80_webServer() throws Exception {
        Path front = Files.createTempDirectory("sikho-front");
        Files.writeString(front.resolve("index.html"), "<html>hi</html>");
        Files.writeString(front.getParent().resolve("secret.txt"), "nope");
        World w = new World();
        User u = w.member("asha", "lalpur");
        u.passwordHash = Passwords.hash("member123");
        Clock.fix(null);   // sessions use the real clock while the server runs
        ApiServer s = new ApiServer(front, w.p);
        s.start(0);
        try {
            HttpClient http = HttpClient.newBuilder().build();
            String base = "http://127.0.0.1:" + s.port();
            eq(200, http.send(HttpRequest.newBuilder(URI.create(base + "/")).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "index");
            eq(404, http.send(HttpRequest.newBuilder(URI.create(base + "/static/..%2Fsecret.txt")).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "no path tricks");
            eq(401, http.send(HttpRequest.newBuilder(URI.create(base + "/api/home")).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "needs sign-in");
            HttpRequest noHeader = HttpRequest.newBuilder(URI.create(base + "/api/login")).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"asha\",\"password\":\"member123\"}")).build();
            eq(403, http.send(noHeader, HttpResponse.BodyHandlers.ofString()).statusCode(), "CSRF header required");
            HttpResponse<String> login = http.send(HttpRequest.newBuilder(URI.create(base + "/api/login")).header("Content-Type", "application/json").header("X-Sikho", "1")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"asha\",\"password\":\"member123\"}")).build(), HttpResponse.BodyHandlers.ofString());
            eq(200, login.statusCode(), "login");
            String cookie = login.headers().firstValue("Set-Cookie").orElse("");
            check(cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict"), "safe cookie");
            String c = cookie.split(";")[0];
            HttpResponse<String> home = http.send(HttpRequest.newBuilder(URI.create(base + "/api/home")).header("Cookie", c).build(), HttpResponse.BodyHandlers.ofString());
            eq(200, home.statusCode(), "home");
            check(home.body().contains("\"balance\":3"), "balance shown");
            check(home.headers().firstValue("Content-Security-Policy").isPresent(), "CSP header");
            HttpResponse<String> mod = http.send(HttpRequest.newBuilder(URI.create(base + "/api/mod")).header("Cookie", c).build(), HttpResponse.BodyHandlers.ofString());
            eq(403, mod.statusCode(), "members cannot open moderator pages");
            HttpResponse<String> search = http.send(HttpRequest.newBuilder(URI.create(base + "/api/search?type=OFFER&q=khana")).header("Cookie", c).build(), HttpResponse.BodyHandlers.ofString());
            eq(200, search.statusCode(), "search");
        } finally {
            s.stop();
            Clock.fix(NOW);
        }
    }
}
