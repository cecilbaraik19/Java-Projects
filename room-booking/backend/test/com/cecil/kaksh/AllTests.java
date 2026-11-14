package com.cecil.kaksh;

import com.cecil.kaksh.core.Recurrence;
import com.cecil.kaksh.core.RoomCalendar;
import com.cecil.kaksh.core.Rules;
import com.cecil.kaksh.model.Block;
import com.cecil.kaksh.model.Booking;
import com.cecil.kaksh.model.Role;
import com.cecil.kaksh.model.Room;
import com.cecil.kaksh.model.Status;
import com.cecil.kaksh.model.User;
import com.cecil.kaksh.model.WaitEntry;
import com.cecil.kaksh.service.Ics;
import com.cecil.kaksh.service.Problem;
import com.cecil.kaksh.store.DataStore;
import com.cecil.kaksh.store.SeedData;
import com.cecil.kaksh.util.Clock;
import com.cecil.kaksh.util.Json;
import com.cecil.kaksh.util.Passwords;
import com.cecil.kaksh.web.ApiServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * All the tests, with no JUnit download needed: run  run-tests.bat  or  ./run-tests.sh.
 * The clock is frozen at Monday 16 November 2026, 08:00 India time, so results never change.
 */
public final class AllTests {
    private static int passed, failed;
    private static final List<String> failures = new ArrayList<>();
    static final LocalDate MON = LocalDate.of(2026, 11, 16);
    static final long M = Rules.MINUTE;

    public static void main(String[] args) throws Exception {
        run("JSON reads and writes", AllTests::json);
        run("Passwords are hashed with a salt", AllTests::passwords);
        run("Office rules: quarter hours, opening hours, Sunday, past, length", AllTests::rules);
        run("Conflict index: overlap, touching, buffer", AllTests::calendarBasics);
        run("Conflict index agrees with a slow check on 3000 random cases", AllTests::calendarRandom);
        run("Repeat: weekdays skip Saturday and Sunday", AllTests::recurrence);
        run("Booking a taken time is refused with suggestions", AllTests::clashWithSuggestions);
        run("Buffer minutes after a meeting are kept free", AllTests::buffer);
        run("Too small / missing equipment is refused", AllTests::sizeAndEquipment);
        run("Series: all or nothing, or skip the taken dates", AllTests::series);
        run("Busy invited people need a yes first", AllTests::peopleClash);
        run("Fair use: at most 15 hours a week", AllTests::quota);
        run("Approval: pending holds the room; decline frees it", AllTests::approval);
        run("Check-in window and no-show release", AllTests::noShow);
        run("Waitlist: freed room goes to the first in the queue", AllTests::waitlist);
        run("End early gives the rest back", AllTests::endEarly);
        run("Extend only when the room is free after", AllTests::extend);
        run("Edit with an old version is refused (optimistic locking)", AllTests::versions);
        run("Moving a booking keeps the old slot free for others", AllTests::moveFrees);
        run("Cancel one date or the rest of a series", AllTests::cancelSeries);
        run("Closing a room moves bookings, or cancels when nothing fits", AllTests::blocks);
        run("16 people pressing Book at once: exactly one wins", AllTests::concurrency);
        run("Private meetings stay private", AllTests::privacy);
        run("Find a time when everybody is free", AllTests::commonTime);
        run("Free gaps of a room", AllTests::freeGaps);
        run("Calendar file (.ics) is well formed", AllTests::ics);
        run("Wrong passwords lock the account", AllTests::lockout);
        run("Web server: sign-in, CSRF header, cookies, static files", AllTests::web);
        run("Demo office builds and has no overlapping bookings", AllTests::demo);
        Clock.fix(null);
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        for (String f : failures) System.out.println("  FAILED: " + f);
        if (failed > 0) System.exit(1);
    }

    interface Test { void run() throws Exception; }

    static void run(String name, Test t) {
        Clock.fix(Instant.ofEpochMilli(Rules.at(MON, LocalTime.of(8, 0))));
        try {
            t.run();
            passed++;
            System.out.println("  ok    " + name);
        } catch (Throwable e) {
            failed++;
            failures.add(name + " - " + e);
            System.out.println("  FAIL  " + name + " - " + e);
        }
    }

    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    static void eq(Object want, Object got, String what) {
        if (want == null ? got != null : !want.equals(got)) throw new AssertionError(what + ": wanted " + want + ", got " + got);
    }

    static int refused(Runnable r) {
        try { r.run(); } catch (Problem p) { return p.status; }
        throw new AssertionError("expected a refusal");
    }

    // ------------------------------------------------------------------ a small office for tests

    static final class Office {
        final DataStore store = new DataStore(null);
        final ApiServer.Parts p;
        final User a, b, c, admin;

        Office() {
            room("R-1", "Small", 1, 4, 0, false, "SCREEN");
            room("R-2", "Middle", 1, 8, 10, false, "SCREEN,VIDEO_CALL");
            room("R-3", "Big", 2, 12, 10, false, "SCREEN,PROJECTOR,VIDEO_CALL");
            room("R-4", "Board", 3, 20, 15, true, "PROJECTOR,VIDEO_CALL");
            a = user("asha", Role.EMPLOYEE, 1);
            b = user("bikram", Role.EMPLOYEE, 1);
            c = user("chitra", Role.EMPLOYEE, 2);
            admin = user("admin", Role.ADMIN, 1);
            p = App.parts(store);
        }

        void room(String id, String name, int floor, int cap, int buf, boolean appr, String eq) {
            Room r = new Room();
            r.id = id; r.code = id; r.name = name; r.floor = floor; r.capacity = cap; r.bufferMin = buf; r.needsApproval = appr;
            r.equipment = new ArrayList<>(List.of(eq.split(",")));
            store.rooms.put(id, r);
        }

        User user(String name, Role role, int floor) {
            User u = new User();
            u.id = "U-" + name; u.username = name; u.name = name; u.role = role; u.floor = floor; u.team = "T";
            u.passwordHash = Passwords.hash("secret123");
            store.users.put(u.id, u);
            return u;
        }

        Map<String, Object> req(String room, int h, int m, int minutes, Object... more) {
            long s = Rules.at(MON, LocalTime.of(h, m));
            Map<String, Object> body = Json.obj("roomId", room, "title", "Meet", "start", s, "end", s + minutes * M);
            for (int i = 0; i + 1 < more.length; i += 2) body.put((String) more[i], more[i + 1]);
            return body;
        }

        Booking book(User who, String room, int h, int m, int minutes, Object... more) {
            return p.bookings().create(who, req(room, h, m, minutes, more)).get(0);
        }
    }

    // ------------------------------------------------------------------ tests

    static void json() {
        Map<String, Object> m = Json.obj("a", 1L, "b", "x\"<y>\n", "c", List.of(true, 2.5), "d", null);
        Map<String, Object> back = Json.parseObject(Json.write(m));
        eq(1L, back.get("a"), "number");
        eq("x\"<y>\n", back.get("b"), "text with quotes");
        eq(List.of(true, 2.5), back.get("c"), "list");
        check(!Json.write("<script>").contains("<"), "< is escaped");
        refused(() -> { try { Json.parse("{\"a\":}"); } catch (IllegalArgumentException e) { throw Problem.bad("x"); } });
    }

    static void passwords() {
        String h1 = Passwords.hash("member123"), h2 = Passwords.hash("member123");
        check(!h1.equals(h2), "salted");
        check(Passwords.verify("member123", h1) && !Passwords.verify("member124", h1), "verify");
        check(Passwords.problem("short") != null && Passwords.problem("longenough1") == null, "rules");
    }

    static void rules() {
        User u = new User(); u.role = Role.EMPLOYEE;
        long ten = Rules.at(MON, LocalTime.of(10, 0));
        Rules.checkTime(ten, ten + 60 * M, u);
        eq(400, refused(() -> Rules.checkTime(ten + 5 * M, ten + 65 * M, u)), "not on a quarter hour");
        eq(400, refused(() -> Rules.checkTime(Rules.at(MON, LocalTime.of(7, 45)), ten, u)), "before opening");
        eq(400, refused(() -> Rules.checkTime(Rules.at(MON, LocalTime.of(19, 30)), Rules.at(MON, LocalTime.of(20, 15)), u)), "after closing");
        long sun = Rules.at(MON.minusDays(1), LocalTime.of(10, 0));
        eq(400, refused(() -> Rules.checkTime(sun, sun + 60 * M, u)), "Sunday");
        long sat = Rules.at(MON.minusDays(2), LocalTime.of(10, 0));
        eq(400, refused(() -> Rules.checkTime(sat, sat + 60 * M, u)), "in the past");
        eq(400, refused(() -> Rules.checkTime(ten, ten + 300 * M, u)), "too long");
        User admin = new User(); admin.role = Role.ADMIN;
        Rules.checkTime(ten, ten + 300 * M, admin);
        long far = Rules.at(MON.plusDays(70), LocalTime.of(10, 0));
        eq(400, refused(() -> Rules.checkTime(far, far + 60 * M, u)), "too far ahead");
    }

    static void calendarBasics() {
        RoomCalendar cal = new RoomCalendar();
        Booking b = new Booking();
        b.id = "B-1"; b.roomId = "R"; b.start = 600 * M; b.end = 660 * M;
        cal.add(b);
        check(!cal.clashes("R", 630 * M, 700 * M, 0, null).isEmpty(), "overlap");
        check(cal.clashes("R", 660 * M, 700 * M, 0, null).isEmpty(), "touching is fine without buffer");
        check(!cal.clashes("R", 660 * M, 700 * M, 10 * M, null).isEmpty(), "touching clashes with a buffer");
        check(cal.clashes("R", 670 * M, 700 * M, 10 * M, null).isEmpty(), "after the buffer is fine");
        check(cal.clashes("R", 630 * M, 700 * M, 0, "B-1").isEmpty(), "ignore the booking being moved");
        check(cal.clashes("OTHER", 630 * M, 700 * M, 0, null).isEmpty(), "other room");
        // a very long busy time far before must still be found (the early stop uses the longest length)
        Block bl = new Block();
        bl.id = "K-1"; bl.roomId = "R"; bl.start = 0; bl.end = 590 * M;
        cal.add(bl);
        check(!cal.clashes("R", 500 * M, 520 * M, 0, null).isEmpty(), "inside a long block");
        cal.remove("R", 0, "K-1");
        check(cal.clashes("R", 500 * M, 520 * M, 0, null).isEmpty(), "removed");
    }

    static void calendarRandom() {
        Random r = new Random(7);
        RoomCalendar cal = new RoomCalendar();
        List<long[]> all = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            long s = r.nextInt(20_000), e = s + 1 + r.nextInt(i % 50 == 0 ? 3000 : 120);
            Booking b = new Booking();
            b.id = "B" + i; b.roomId = "R"; b.start = s; b.end = e;
            cal.add(b);
            all.add(new long[] { s, e });
        }
        for (int k = 0; k < 3000; k++) {
            long s = r.nextInt(21_000), e = s + 1 + r.nextInt(300), buf = r.nextInt(3) * 10;
            int slow = 0;
            for (long[] x : all) if (s < x[1] + buf && x[0] < e + buf) slow++;
            eq(slow, cal.clashes("R", s, e, buf, null).size(), "case " + k);
        }
    }

    static void recurrence() {
        long fri = Rules.at(MON.plusDays(4), LocalTime.of(9, 30));
        List<Recurrence.Slot> s = Recurrence.expand(fri, fri + 15 * M, "WEEKDAYS", 3);
        eq(3, s.size(), "count");
        eq(MON.plusDays(7), Rules.dayOf(s.get(1).start()), "Friday then Monday");
        eq(LocalTime.of(9, 30), Rules.zoned(s.get(2).start()).toLocalTime(), "same time");
        List<Recurrence.Slot> w = Recurrence.expand(fri, fri + 15 * M, "WEEKLY", 2);
        eq(MON.plusDays(11), Rules.dayOf(w.get(1).start()), "next week");
        List<Recurrence.Slot> d = Recurrence.expand(Rules.at(MON.plusDays(5), LocalTime.of(10, 0)), Rules.at(MON.plusDays(5), LocalTime.of(11, 0)), "DAILY", 2);
        eq(MON.plusDays(7), Rules.dayOf(d.get(1).start()), "daily skips Sunday");
    }

    @SuppressWarnings("unchecked")
    static void clashWithSuggestions() {
        Office o = new Office();
        o.book(o.a, "R-3", 11, 0, 60);
        try {
            o.book(o.b, "R-3", 11, 30, 60);
            throw new AssertionError("should clash");
        } catch (Problem p) {
            eq(409, p.status, "status");
            Map<String, Object> chk = (Map<String, Object>) ((Map<String, Object>) p.data).get("check");
            Map<String, Object> alt = (Map<String, Object>) chk.get("alternatives");
            List<Map<String, Object>> times = (List<Map<String, Object>>) alt.get("times");
            List<Map<String, Object>> rooms = (List<Map<String, Object>>) alt.get("rooms");
            check(!times.isEmpty() && !rooms.isEmpty(), "suggestions given");
            for (Map<String, Object> t : times) check(o.p.calendar().free(o.store.rooms.get("R-3"), (Long) t.get("start"), (Long) t.get("end"), null), "suggested time is free");
            eq(Rules.at(MON, LocalTime.of(12, 15)), times.get(0).get("start"), "nearest later time after buffer");
            for (Map<String, Object> r : rooms) check(!r.get("roomId").equals("R-3"), "other rooms only");
        }
        eq(1L, Json.num(o.store.meta, "stats.clashesStopped", 0), "counted");
    }

    static void buffer() {
        Office o = new Office();
        o.book(o.a, "R-2", 10, 0, 60);   // 10 min buffer
        eq(409, refused(() -> o.book(o.b, "R-2", 11, 0, 30)), "starts inside the buffer");
        o.book(o.b, "R-2", 11, 15, 30);
        eq(409, refused(() -> o.book(o.c, "R-2", 9, 0, 60)), "ends inside the buffer before");
        o.book(o.c, "R-1", 10, 0, 60);   // no buffer
        o.book(o.admin, "R-1", 11, 0, 30);
    }

    static void sizeAndEquipment() {
        Office o = new Office();
        List<String> many = List.of(o.b.id, o.c.id, o.admin.id);
        eq(409, refused(() -> o.book(o.a, "R-1", 10, 0, 30, "people", many, "guests", 2)), "6 people in 4 seats");
        eq(409, refused(() -> o.book(o.a, "R-1", 10, 0, 30, "needs", List.of("PROJECTOR"))), "no projector");
        eq(400, refused(() -> o.book(o.a, "R-1", 10, 0, 30, "needs", List.of("TELEPORTER"))), "unknown equipment");
    }

    static void series() {
        Office o = new Office();
        o.book(o.b, "R-3", 9, 30, 30);   // Wednesday? no: Monday. Take a date in the middle:
        long wed = Rules.at(MON.plusDays(2), LocalTime.of(9, 30));
        o.p.bookings().create(o.b, Json.obj("roomId", "R-3", "title", "x", "start", wed, "end", wed + 30 * M));
        Map<String, Object> s = Json.obj("roomId", "R-3", "title", "Stand-up", "start", Rules.at(MON.plusDays(1), LocalTime.of(9, 30)),
                "end", Rules.at(MON.plusDays(1), LocalTime.of(10, 0)), "repeat", "WEEKDAYS", "count", 4);
        int before = o.store.bookings.size();
        eq(409, refused(() -> o.p.bookings().create(o.a, s)), "one date taken: nothing booked");
        eq(before, o.store.bookings.size(), "all or nothing");
        s.put("skipTaken", true);
        List<Booking> made = o.p.bookings().create(o.a, s);
        eq(3, made.size(), "3 of 4 booked");
        eq(1, o.store.series.get(made.get(0).seriesId).skipped.size(), "one skipped");
    }

    static void peopleClash() {
        Office o = new Office();
        o.book(o.a, "R-1", 10, 0, 60, "people", List.of(o.b.id));
        eq(409, refused(() -> o.book(o.c, "R-2", 10, 30, 30, "people", List.of(o.b.id))), "Bikram is busy");
        Booking ok = o.book(o.c, "R-2", 10, 30, 30, "people", List.of(o.b.id), "peopleOk", true);
        eq(Status.CONFIRMED, ok.status, "booked anyway after yes");
    }

    static void quota() {
        Office o = new Office();
        for (int d = 0; d < 4; d++) {
            long s = Rules.at(MON.plusDays(d), LocalTime.of(9, 0));
            o.p.bookings().create(o.a, Json.obj("roomId", "R-1", "title", "x", "start", s, "end", s + 240 * M));   // 16 h would be the 4th
            if (d == 2) break;
        }
        long s = Rules.at(MON.plusDays(3), LocalTime.of(9, 0));
        eq(409, refused(() -> o.p.bookings().create(o.a, Json.obj("roomId", "R-1", "title", "x", "start", s, "end", s + 240 * M))), "over 15 h");
        o.p.bookings().create(o.a, Json.obj("roomId", "R-1", "title", "x", "start", s, "end", s + 180 * M));   // exactly 15 h
        o.p.bookings().create(o.admin, Json.obj("roomId", "R-2", "title", "x", "start", s, "end", s + 240 * M));   // admin has no limit
    }

    static void approval() {
        Office o = new Office();
        Booking b = o.book(o.a, "R-4", 14, 0, 60);
        eq(Status.PENDING, b.status, "waits for approval");
        eq(409, refused(() -> o.book(o.b, "R-4", 14, 30, 30)), "pending holds the room");
        o.p.bookings().decide(o.admin, b.id, false, "Kept for the all-hands");
        eq(Status.DECLINED, b.status, "declined");
        o.book(o.b, "R-4", 14, 30, 30);
        Booking b2 = o.book(o.c, "R-4", 16, 0, 30);
        eq(403, refused(() -> o.p.bookings().decide(o.a, b2.id, true, null)), "only the facility manager");
        o.p.bookings().decide(o.admin, b2.id, true, null);
        eq(Status.CONFIRMED, b2.status, "approved");
        Booking b3 = o.book(o.c, "R-4", 18, 0, 30);
        Clock.fix(Instant.ofEpochMilli(b3.start));
        o.p.bookings().tick();
        eq(Status.EXPIRED, b3.status, "not approved in time");
    }

    static void noShow() {
        Office o = new Office();
        Booking b = o.book(o.a, "R-2", 10, 0, 60, "people", List.of(o.b.id));
        Clock.fix(Instant.ofEpochMilli(b.start - 15 * M));
        eq(400, refused(() -> o.p.bookings().checkIn(o.a, b.id)), "too early");
        eq(403, refused(() -> o.p.bookings().checkIn(o.c, b.id)), "not in the meeting");
        Clock.fix(Instant.ofEpochMilli(b.start + 14 * M));
        o.p.bookings().tick();
        eq(Status.CONFIRMED, b.status, "still time to check in");
        Clock.fix(Instant.ofEpochMilli(b.start + 15 * M));
        o.p.bookings().tick();
        eq(Status.RELEASED, b.status, "no-show");
        check(o.p.calendar().free(o.store.rooms.get("R-2"), b.start + 30 * M, b.end, null), "room given back");
        eq(400, refused(() -> o.p.bookings().checkIn(o.a, b.id)), "too late");
        Booking b2 = o.book(o.a, "R-1", 11, 0, 30, "people", List.of(o.b.id));
        Clock.fix(Instant.ofEpochMilli(b2.start - 5 * M));
        o.p.bookings().checkIn(o.b, b2.id);
        eq(Status.CHECKED_IN, b2.status, "an invited person can check in");
        Clock.fix(Instant.ofEpochMilli(b2.end));
        o.p.bookings().tick();
        eq(Status.COMPLETED, b2.status, "done at the end");
    }

    static void waitlist() {
        Office o = new Office();
        Booking b = o.book(o.a, "R-3", 15, 0, 60);
        eq(400, refused(() -> o.p.bookings().waitFor(o.b, o.req("R-3", 17, 0, 60))), "free time: just book it");
        WaitEntry first = o.p.bookings().waitFor(o.b, o.req("R-3", 15, 0, 60, "title", "B's meeting"));
        Clock.advanceMillis(1000);
        WaitEntry second = o.p.bookings().waitFor(o.c, o.req("R-3", 15, 0, 60, "title", "C's meeting"));
        eq(409, refused(() -> o.p.bookings().waitFor(o.b, o.req("R-3", 15, 0, 60))), "no double entry");
        o.p.bookings().cancel(o.a, b.id, "one", null);
        eq("BOOKED", first.status, "first in the queue gets it");
        eq("WAITING", second.status, "second keeps waiting");
        Booking got = o.store.bookings.get(first.bookingId);
        eq(o.b.id, got.organiserId, "booked for Bikram");
        eq("B's meeting", got.title, "with his title");
        check(o.store.notes.values().stream().anyMatch(n -> n.userId.equals(o.b.id) && n.text.startsWith("Good news")), "told");
    }

    static void endEarly() {
        Office o = new Office();
        Booking b = o.book(o.a, "R-2", 10, 0, 120);
        WaitEntry w = o.p.bookings().waitFor(o.b, o.req("R-2", 11, 15, 45));
        Clock.fix(Instant.ofEpochMilli(b.start));
        o.p.bookings().checkIn(o.a, b.id);
        Clock.fix(Instant.ofEpochMilli(b.start + 52 * M));
        o.p.bookings().endNow(o.a, b.id);
        eq(Status.COMPLETED, b.status, "completed");
        eq(Rules.at(MON, LocalTime.of(11, 0)), b.end, "end rounded up to the next quarter");
        eq("BOOKED", w.status, "waitlist got 11:15 (after the 10 min buffer)");
    }

    static void extend() {
        Office o = new Office();
        Booking b = o.book(o.a, "R-2", 10, 0, 60);
        o.book(o.b, "R-2", 11, 30, 30);
        o.p.bookings().extend(o.a, b.id, 15);
        eq(Rules.at(MON, LocalTime.of(11, 15)), b.end, "11:15 + 10 min buffer still before 11:30");
        eq(409, refused(() -> o.p.bookings().extend(o.a, b.id, 15)), "11:30 would clash");
        eq(403, refused(() -> o.p.bookings().extend(o.c, b.id, 15)), "only the organiser");
    }

    static void versions() {
        Office o = new Office();
        Booking b = o.book(o.a, "R-1", 10, 0, 60);
        int v = b.version;
        o.p.bookings().update(o.a, b.id, Json.obj("version", v, "title", "New title"));
        eq("New title", b.title, "changed");
        eq(409, refused(() -> o.p.bookings().update(o.a, b.id, Json.obj("version", v, "title", "Old screen"))), "stale version");
        eq(403, refused(() -> o.p.bookings().update(o.b, b.id, Json.obj("version", b.version, "title", "x"))), "not the organiser");
        o.p.bookings().update(o.admin, b.id, Json.obj("version", b.version, "title", "Fixed by admin"));
    }

    static void moveFrees() {
        Office o = new Office();
        Booking b = o.book(o.a, "R-1", 10, 0, 60);
        long s = Rules.at(MON, LocalTime.of(14, 0));
        o.p.bookings().update(o.a, b.id, Json.obj("version", b.version, "start", s, "end", s + 60 * M));
        o.book(o.b, "R-1", 10, 0, 60);
        eq(409, refused(() -> o.book(o.c, "R-1", 14, 0, 30)), "new time is held");
        eq(1, o.p.calendar().clashes("R-1", s, s + 60 * M, 0, null).size(), "index has it once");
    }

    static void cancelSeries() {
        Office o = new Office();
        List<Booking> made = o.p.bookings().create(o.a, Json.obj("roomId", "R-1", "title", "Daily", "start", Rules.at(MON, LocalTime.of(9, 0)),
                "end", Rules.at(MON, LocalTime.of(9, 15)), "repeat", "DAILY", "count", 5));
        o.p.bookings().cancel(o.a, made.get(1).id, "one", null);
        eq(Status.CANCELLED, made.get(1).status, "one date");
        eq(Status.CONFIRMED, made.get(2).status, "others stay");
        List<Booking> rest = o.p.bookings().cancel(o.a, made.get(2).id, "series", "Project ended");
        eq(3, rest.size(), "this and later dates");
        eq(Status.CONFIRMED, made.get(0).status, "earlier date stays");
    }

    @SuppressWarnings("unchecked")
    static void blocks() {
        Office o = new Office();
        Booking movable = o.book(o.a, "R-2", 10, 0, 60);
        Booking big = o.book(o.b, "R-3", 13, 0, 60, "people", List.of(o.a.id, o.c.id, o.admin.id), "guests", 6, "peopleOk", true);
        o.book(o.c, "R-4", 13, 0, 60, "peopleOk", true);   // the only other big room is pending in the boardroom anyway (needs approval)
        Map<String, Object> body = Json.obj("roomId", "R-2", "reason", "AC repair", "start", Rules.at(MON, LocalTime.of(9, 0)), "end", Rules.at(MON, LocalTime.of(12, 0)));
        Map<String, Object> plan = o.p.bookings().block(o.admin, body, true);
        eq("MOVE", ((List<Map<String, Object>>) plan.get("plan")).get(0).get("action"), "plan: move");
        eq("R-2", movable.roomId, "dry run changes nothing");
        check(o.store.blocks.isEmpty(), "no block yet");
        o.p.bookings().block(o.admin, body, false);
        check(!movable.roomId.equals("R-2"), "moved");
        eq(Status.CONFIRMED, movable.status, "still booked");
        eq(409, refused(() -> o.book(o.b, "R-2", 10, 0, 30)), "room closed");
        o.p.bookings().block(o.admin, Json.obj("roomId", "R-3", "reason", "Painting", "start", Rules.at(MON, LocalTime.of(12, 0)), "end", Rules.at(MON, LocalTime.of(18, 0))), false);
        eq(Status.CANCELLED, big.status, "10 people: no other free room without approval");
        eq(403, refused(() -> o.p.bookings().block(o.a, body, true)), "only the facility manager");
    }

    static void concurrency() throws Exception {
        Office o = new Office();
        List<User> people = new ArrayList<>();
        for (int i = 0; i < 16; i++) people.add(o.user("p" + i, Role.EMPLOYEE, 1));
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger(), clashes = new AtomicInteger();
        List<Thread> ts = new ArrayList<>();
        for (User u : people) {
            Thread t = new Thread(() -> {
                try {
                    go.await();
                    o.book(u, "R-3", 10, 0, 60);
                    wins.incrementAndGet();
                } catch (Problem p) {
                    if (p.status == 409) clashes.incrementAndGet();
                } catch (InterruptedException ignored) { }
            });
            t.start();
            ts.add(t);
        }
        go.countDown();
        for (Thread t : ts) t.join();
        eq(1, wins.get(), "one winner");
        eq(15, clashes.get(), "fifteen clashes");
    }

    static void privacy() {
        Office o = new Office();
        Booking b = o.book(o.a, "R-1", 10, 0, 60, "title", "Salary talk", "privateTitle", true, "people", List.of(o.b.id));
        Map<String, Object> other = o.p.views().booking(b, o.c, true);
        eq("Private meeting", other.get("title"), "hidden from others");
        eq(0, ((List<?>) other.get("people")).size(), "names hidden");
        eq("Salary talk", o.p.views().booking(b, o.b, true).get("title"), "visible to the invited");
        eq("Salary talk", o.p.views().booking(b, o.admin, true).get("title"), "visible to the facility manager");
        check(Ics.of(o.store, List.of(b), o.c.id).contains("SUMMARY:Private meeting"), "private in calendar file too");
    }

    @SuppressWarnings("unchecked")
    static void commonTime() {
        Office o = new Office();
        o.book(o.a, "R-1", 8, 0, 120);
        o.book(o.b, "R-2", 10, 0, 60);
        List<Map<String, Object>> slots = o.p.finder().commonTimes(List.of(o.a.id, o.b.id), MON, 1, 60, 2, List.of(), o.a, 5);
        check(!slots.isEmpty(), "found some");
        eq(Rules.at(MON, LocalTime.of(11, 0)), slots.get(0).get("start"), "first time both are free");
        for (Map<String, Object> s : slots) {
            long st = (Long) s.get("start"), en = (Long) s.get("end");
            check(o.p.finder().peopleClashes(List.of(o.a.id, o.b.id), st, en, null).isEmpty(), "nobody busy");
            check((Boolean) ((Map<String, Object>) s.get("room")).get("usable"), "room usable");
        }
    }

    static void freeGaps() {
        Office o = new Office();
        o.book(o.a, "R-2", 10, 0, 60);   // buffer 10
        List<Map<String, Object>> gaps = o.p.finder().freeGaps(o.store.rooms.get("R-2"), MON);
        eq(2, gaps.size(), "before and after");
        eq(Rules.at(MON, LocalTime.of(9, 45)), gaps.get(0).get("end"), "ends 15 min before (buffer 10, rounded)");
        eq(Rules.at(MON, LocalTime.of(11, 15)), gaps.get(1).get("start"), "starts after the buffer");
    }

    static void ics() {
        Office o = new Office();
        Booking b = o.book(o.a, "R-1", 10, 0, 60, "title", "Plan; budget, Q4");
        String ics = Ics.of(o.store, List.of(b), o.a.id);
        check(ics.startsWith("BEGIN:VCALENDAR\r\n") && ics.endsWith("END:VCALENDAR\r\n"), "wrapped, CRLF");
        check(ics.contains("SUMMARY:Plan\\; budget\\, Q4"), "escaped");
        check(ics.contains("DTSTART:20261116T043000Z"), "UTC time (10:00 IST = 04:30 UTC)");
        for (String line : ics.split("\r\n")) check(line.length() <= 75, "folded lines");
    }

    static void lockout() {
        Office o = new Office();
        for (int i = 0; i < 5; i++) eq(401, refused(() -> o.p.auth().login("asha", "wrong")), "wrong");
        eq(429, refused(() -> o.p.auth().login("asha", "secret123")), "locked");
        Clock.advanceMillis(6 * 60_000);
        check(o.p.auth().login("asha", "secret123") != null, "open again");
        eq(401, refused(() -> o.p.auth().login("nobody", "x")), "same message for unknown user");
    }

    static void web() throws Exception {
        Office o = new Office();
        Path front = Files.createTempDirectory("kaksh-front");
        Files.writeString(front.resolve("index.html"), "<!doctype html><title>t</title>");
        ApiServer server = new ApiServer(front, o.p);
        server.start(0);
        try {
            String base = "http://127.0.0.1:" + server.port();
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/")).build(), HttpResponse.BodyHandlers.ofString());
            eq(200, r.statusCode(), "home page");
            check(r.headers().firstValue("Content-Security-Policy").isPresent(), "CSP header");
            eq(404, http.send(HttpRequest.newBuilder(URI.create(base + "/static/..%2F..%2Fetc%2Fpasswd")).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "no path tricks");
            eq(401, http.send(HttpRequest.newBuilder(URI.create(base + "/api/home")).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "needs sign-in");
            String login = Json.write(Json.obj("username", "asha", "password", "secret123"));
            HttpResponse<String> noHeader = http.send(HttpRequest.newBuilder(URI.create(base + "/api/login")).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(login)).build(), HttpResponse.BodyHandlers.ofString());
            eq(403, noHeader.statusCode(), "CSRF header required");
            HttpResponse<String> ok = http.send(HttpRequest.newBuilder(URI.create(base + "/api/login")).header("Content-Type", "application/json").header("X-Kaksh", "1")
                    .POST(HttpRequest.BodyPublishers.ofString(login)).build(), HttpResponse.BodyHandlers.ofString());
            eq(200, ok.statusCode(), "signed in");
            String cookie = ok.headers().firstValue("Set-Cookie").orElse("");
            check(cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict"), "safe cookie");
            String session = cookie.split(";")[0];
            HttpResponse<String> home = http.send(HttpRequest.newBuilder(URI.create(base + "/api/home")).header("Cookie", session).build(), HttpResponse.BodyHandlers.ofString());
            eq(200, home.statusCode(), "home data");
            long s = Rules.at(MON, LocalTime.of(10, 0));
            String body = Json.write(Json.obj("roomId", "R-1", "title", "Web", "start", s, "end", s + 30 * M));
            HttpRequest book = HttpRequest.newBuilder(URI.create(base + "/api/bookings")).header("Cookie", session).header("Content-Type", "application/json")
                    .header("X-Kaksh", "1").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            eq(200, http.send(book, HttpResponse.BodyHandlers.ofString()).statusCode(), "booked");
            HttpResponse<String> again = http.send(book, HttpResponse.BodyHandlers.ofString());
            eq(409, again.statusCode(), "clash over the web");
            check(again.body().contains("alternatives"), "suggestions in the answer");
            HttpResponse<String> evil = http.send(HttpRequest.newBuilder(URI.create(base + "/api/bookings")).header("Cookie", session).header("Content-Type", "application/json")
                    .header("X-Kaksh", "1").header("Origin", "http://evil.example").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            eq(403, evil.statusCode(), "other website blocked");
            eq(403, http.send(HttpRequest.newBuilder(URI.create(base + "/api/admin/insights")).header("Cookie", session).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "not the facility manager");
        } finally {
            server.stop();
        }
    }

    static void demo() {
        Clock.fix(null);
        DataStore store = new DataStore(null);
        ApiServer.Parts p = App.parts(store);
        SeedData.load(store, p.bookings());
        check(store.bookings.size() > 300, "a real history");
        Map<String, List<Booking>> byRoom = new HashMap<>();
        for (Booking b : store.bookings.values()) if (b.status.holdsRoom()) byRoom.computeIfAbsent(b.roomId, k -> new ArrayList<>()).add(b);
        for (Map.Entry<String, List<Booking>> e : byRoom.entrySet()) {
            List<Booking> l = e.getValue();
            l.sort((x, y) -> Long.compare(x.start, y.start));
            long buf = store.rooms.get(e.getKey()).bufferMin * M;
            for (int i = 1; i < l.size(); i++)
                check(l.get(i).start >= l.get(i - 1).end + buf, "overlap in " + e.getKey() + ": " + l.get(i - 1).id + " and " + l.get(i).id);
        }
        check(store.bookings.values().stream().anyMatch(b -> b.status == Status.RELEASED), "some no-shows");
        check(store.bookings.values().stream().anyMatch(b -> b.status == Status.PENDING), "requests to approve");
    }
}
