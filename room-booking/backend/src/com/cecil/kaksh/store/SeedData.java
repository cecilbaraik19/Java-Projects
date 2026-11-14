package com.cecil.kaksh.store;

import com.cecil.kaksh.core.Rules;
import com.cecil.kaksh.model.Booking;
import com.cecil.kaksh.model.Role;
import com.cecil.kaksh.model.Room;
import com.cecil.kaksh.model.Status;
import com.cecil.kaksh.model.User;
import com.cecil.kaksh.service.BookingService;
import com.cecil.kaksh.service.Problem;
import com.cecil.kaksh.util.Clock;
import com.cecil.kaksh.util.Json;
import com.cecil.kaksh.util.Passwords;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Builds the demo office on the very first start: "Nimbus Works", a made-up software company in Ranchi with
 * 12 meeting rooms on 3 floors and 30 people. ALL PEOPLE ARE MADE UP.
 *
 * The history (4 weeks back, 2 weeks ahead) is NOT typed in. It is PLAYED THROUGH THE REAL SERVICES with the clock
 * moved back: people book rooms the evening before, some bookings clash and are refused, some people join the
 * waitlist, most check in, some never come (no-show -> the room is given back), some finish early, the facility
 * manager approves boardroom requests. So every number on the reports got there by the normal rules.
 * Everything is dated relative to TODAY, so the demo always looks fresh.
 */
public final class SeedData {
    private SeedData() { }

    private static final String[][] ROOMS = {
            // code, name, floor, seats, buffer, approval, equipment, note
            { "1.01", "Hundru", "1", "2", "0", "n", "SPEAKERPHONE", "Phone booth for calls and 1:1s" },
            { "1.02", "Dassam", "1", "4", "5", "n", "SCREEN,WHITEBOARD", "" },
            { "1.03", "Jonha", "1", "6", "5", "n", "SCREEN,VIDEO_CALL,WHEELCHAIR", "Next to reception – good for visitors" },
            { "1.04", "Patratu", "1", "8", "10", "n", "SCREEN,WHITEBOARD,WHEELCHAIR", "" },
            { "2.01", "Koel", "2", "4", "5", "n", "SCREEN,VIDEO_CALL", "" },
            { "2.02", "Karo", "2", "6", "5", "n", "SCREEN,VIDEO_CALL,WHITEBOARD", "" },
            { "2.03", "Barakar", "2", "8", "10", "n", "PROJECTOR,WHITEBOARD", "" },
            { "2.04", "Damodar", "2", "12", "10", "n", "SCREEN,VIDEO_CALL,SPEAKERPHONE,WHITEBOARD", "Biggest room on floor 2" },
            { "3.01", "Netarhat", "3", "3", "0", "n", "SCREEN", "Quiet room" },
            { "3.02", "Rajrappa", "3", "6", "5", "n", "SCREEN,VIDEO_CALL,WHEELCHAIR", "" },
            { "3.03", "Parasnath Boardroom", "3", "18", "15", "y", "SCREEN,PROJECTOR,VIDEO_CALL,SPEAKERPHONE", "Needs the facility manager's approval" },
            { "3.04", "Subarnarekha Hall", "3", "30", "15", "y", "PROJECTOR,SPEAKERPHONE,WHITEBOARD,WHEELCHAIR", "Training hall – needs approval" },
    };

    private static final String[][] PEOPLE = {
            // username, name, team, floor
            { "priya", "Priya Sharma", "Engineering", "2" }, { "rahul", "Rahul Verma", "Engineering", "2" },
            { "aman", "Aman Gupta", "Engineering", "2" }, { "sneha", "Sneha Kumari", "Engineering", "2" },
            { "vikash", "Vikash Oraon", "Engineering", "2" }, { "pooja", "Pooja Singh", "Engineering", "2" },
            { "imran", "Imran Ansari", "Engineering", "2" }, { "kiran", "Kiran Lakra", "Engineering", "2" },
            { "deepak", "Deepak Mahto", "Engineering", "2" }, { "ritu", "Ritu Agarwal", "Engineering", "2" },
            { "arjun", "Arjun Mehta", "Sales", "1" }, { "kavita", "Kavita Das", "Sales", "1" },
            { "manoj", "Manoj Yadav", "Sales", "1" }, { "sana", "Sana Khan", "Sales", "1" }, { "rohit", "Rohit Jha", "Sales", "1" },
            { "anjali", "Anjali Minz", "People & HR", "1" }, { "suresh", "Suresh Prasad", "People & HR", "1" },
            { "neha", "Neha Tirkey", "Design", "3" }, { "farhan", "Farhan Siddiqui", "Design", "3" },
            { "meera", "Meera Ekka", "Design", "3" }, { "nikhil", "Nikhil Bose", "Design", "3" },
            { "rakesh", "Rakesh Sinha", "Finance", "3" }, { "shalini", "Shalini Choudhary", "Finance", "3" },
            { "alok", "Alok Banerjee", "Leadership", "3" }, { "sushmita", "Sushmita Hembrom", "Leadership", "3" },
            { "ayesha", "Ayesha Rahman", "Customer Success", "2" }, { "tarun", "Tarun Pandey", "Customer Success", "2" },
            { "divya", "Divya Nair", "Customer Success", "2" }, { "ramesh", "Ramesh Munda", "IT Support", "1" },
            { "admin", "Sunita Kujur", "Facilities", "1" },
    };

    private static final Map<String, String[]> TITLES = Map.of(
            "Engineering", new String[] { "Sprint planning", "Code review", "Design review: payments API", "Bug triage", "Architecture chat",
                    "Pairing session", "Release go/no-go", "Incident review", "Tech talk: Kafka basics", "Backlog grooming" },
            "Sales", new String[] { "Pipeline review", "Client call – Ranchi Smart City", "Demo practice", "Pricing discussion",
                    "Proposal write-up", "Partner call", "Weekly sales sync" },
            "People & HR", new String[] { "Interview", "Onboarding session", "Policy update", "Interview – Java developer", "Training plan" },
            "Design", new String[] { "Design critique", "User testing", "Wireframe walkthrough", "Brand workshop", "Design system sync" },
            "Finance", new String[] { "Budget review", "Month-end close", "Invoice check", "Audit prep" },
            "Leadership", new String[] { "Leadership sync", "Strategy session", "Board prep", "Hiring plan" },
            "Customer Success", new String[] { "Customer escalation", "Renewal planning", "Support handover", "Onboarding call" },
            "IT Support", new String[] { "Laptop handover", "Security awareness", "Network upgrade plan" },
            "Facilities", new String[] { "Vendor meeting", "Housekeeping review", "Fire drill planning" });

    private static final String[] PRIVATE = { "1:1", "Salary discussion", "Interview", "Performance review" };

    /** Builds everything. The clock is moved back while the history is played, and put back when done. */
    public static void load(DataStore store, BookingService svc) {
        Instant realNow = Clock.now();
        Instant frozenBefore = Clock.fixed();
        Random rnd = new Random(2026);
        store.batch = true;
        try {
            makeRoomsAndPeople(store);
            LocalDate today = LocalDate.ofInstant(realNow, Clock.ZONE);
            LocalDate first = today.minusDays(28);
            List<User> people = new ArrayList<>(store.users.values());
            User admin = store.userByName("admin");

            // repeating meetings, set up a month ago (and renewed half way)
            for (LocalDate setup : new LocalDate[] { first.minusDays(1), today.minusDays(4) }) {
                Clock.fix(Instant.ofEpochMilli(Rules.at(setup, LocalTime.of(9, 0))));
                LocalDate s = Rules.nextOpenDay(setup.plusDays(1));
                series(svc, store, "priya", "Platform team stand-up", "R-6", s, "09:30", "09:45", "WEEKDAYS", 22, "rahul,aman,sneha,vikash");
                series(svc, store, "neha", "Design team check-in", "R-10", s, "10:00", "10:30", "WEEKLY", 6, "farhan,meera,nikhil");
                series(svc, store, "arjun", "Weekly sales sync", "R-4", s, "17:00", "18:00", "WEEKLY", 6, "kavita,manoj,sana,rohit");
                series(svc, store, "alok", "Leadership sync", "R-11", s, "11:00", "12:00", "WEEKLY", 6, "sushmita,rakesh,priya,arjun");
                series(svc, store, "ayesha", "Support handover", "R-5", s, "18:00", "18:30", "WEEKDAYS", 22, "tarun,divya");
                // the boardroom series needs approval: the facility manager approves it the same morning
                Clock.fix(Instant.ofEpochMilli(Rules.at(setup, LocalTime.of(10, 0))));
                for (Booking b : new ArrayList<>(store.bookings.values()))
                    if (b.status == Status.PENDING) safe(() -> svc.decide(admin, b.id, true, "Approved – regular meeting"));
            }

            for (LocalDate d = first; !d.isAfter(today.plusDays(14)); d = d.plusDays(1)) {
                if (!Rules.openDay(d)) continue;
                long evening = Rules.at(d.minusDays(1), LocalTime.of(18, 30));
                if (evening >= realNow.toEpochMilli()) evening = realNow.toEpochMilli() - 60_000;
                Clock.fix(Instant.ofEpochMilli(evening));
                svc.tick();
                long ahead = d.toEpochDay() - today.toEpochDay();
                int tries = ahead <= 0 ? 48 : (int) Math.max(5, 44 - ahead * 3);
                if (d.getDayOfWeek().getValue() == 6) tries /= 3;   // a quiet Saturday
                for (int i = 0; i < tries; i++) adHoc(svc, store, rnd, people, d);
                // the facility manager deals with requests for the big rooms
                for (Booking b : new ArrayList<>(store.bookings.values()))
                    if (b.status == Status.PENDING && Rules.dayOf(b.start).equals(d) && ahead <= 1) {
                        boolean yes = rnd.nextInt(10) < 9;
                        safe(() -> svc.decide(admin, b.id, yes, yes ? null : "The hall is kept for the all-hands that day"));
                    }
                // a few plans change the evening before
                for (Booking b : dayBookings(store, d))
                    if (b.status == Status.CONFIRMED && b.seriesId == null && rnd.nextInt(100) < 7) {
                        User o = store.users.get(b.organiserId);
                        safe(() -> svc.cancel(o, b.id, "one", rnd.nextBoolean() ? "Client moved the call" : "Not needed any more"));
                    }
                if (ahead <= 0) playDay(svc, store, rnd, d, realNow.toEpochMilli());
            }
            Clock.fix(Instant.ofEpochMilli(realNow.toEpochMilli()));
            svc.tick();
            demoScenes(store, svc, today, realNow.toEpochMilli());
            // old messages count as read, so the demo does not open with 50 unread messages
            for (var n : store.notes.values()) if (n.at < realNow.toEpochMilli() - 36 * 3_600_000L) n.read = true;
        } finally {
            Clock.fix(frozenBefore);
            store.saveAll();
        }
        System.out.println("  Demo office ready: " + store.rooms.size() + " rooms, " + store.users.size() + " people, "
                + store.bookings.size() + " bookings.");
    }

    // ------------------------------------------------------------------ rooms and people

    private static void makeRoomsAndPeople(DataStore store) {
        int i = 1;
        for (String[] r : ROOMS) {
            Room room = new Room();
            room.id = "R-" + i++;
            room.code = r[0];
            room.name = r[1];
            room.floor = Integer.parseInt(r[2]);
            room.capacity = Integer.parseInt(r[3]);
            room.bufferMin = Integer.parseInt(r[4]);
            room.needsApproval = r[5].equals("y");
            room.equipment = new ArrayList<>(List.of(r[6].split(",")));
            room.note = r[7];
            store.rooms.put(room.id, room);
        }
        long created = Clock.millis() - 120L * 86_400_000;
        for (String[] p : PEOPLE) {
            User u = new User();
            u.id = store.nextId("U", 101);
            u.username = p[0];
            u.name = p[1];
            u.team = p[2];
            u.floor = Integer.parseInt(p[3]);
            u.email = p[0] + "@nimbusworks.example";
            u.role = p[0].equals("admin") ? Role.ADMIN : Role.EMPLOYEE;
            // every demo person gets their own salt: the same password still gives a different hash
            u.passwordHash = Passwords.hash(p[0].equals("admin") ? "admin123" : "member123");
            u.createdAt = created;
            store.users.put(u.id, u);
        }
    }

    private static String uid(DataStore store, String username) { return store.userByName(username).id; }

    private static void series(BookingService svc, DataStore store, String who, String title, String room, LocalDate day,
                               String from, String to, String repeat, int count, String people) {
        List<String> ids = new ArrayList<>();
        for (String p : people.split(",")) ids.add(uid(store, p));
        Map<String, Object> body = Json.obj("roomId", room, "title", title,
                "start", Rules.at(day, LocalTime.parse(from)), "end", Rules.at(day, LocalTime.parse(to)),
                "people", ids, "repeat", repeat, "count", count, "skipTaken", true, "peopleOk", true);
        safe(() -> svc.create(store.userByName(who), body));
    }

    // ------------------------------------------------------------------ one made-up meeting

    private static final int[] HOUR_WEIGHT = { 2, 5, 8, 9, 4, 3, 7, 8, 7, 5, 3, 1 };   // 08:00 ... 19:00

    private static void adHoc(BookingService svc, DataStore store, Random rnd, List<User> people, LocalDate d) {
        User org = people.get(rnd.nextInt(people.size()));
        if (org.admin() && rnd.nextInt(4) > 0) return;
        int sum = 0;
        for (int w : HOUR_WEIGHT) sum += w;
        int pick = rnd.nextInt(sum), hour = 8;
        for (int i = 0; i < HOUR_WEIGHT.length; i++) { pick -= HOUR_WEIGHT[i]; if (pick < 0) { hour = 8 + i; break; } }
        int minute = rnd.nextInt(4) * 15;
        int[] lengths = { 30, 30, 45, 60, 60, 60, 90, 120 };
        int len = lengths[rnd.nextInt(lengths.length)];
        long start = Rules.at(d, LocalTime.of(hour, minute));
        long end = start + len * Rules.MINUTE;
        if (end > Rules.closes(d)) return;
        // invite people, mostly from the same team
        List<String> invited = new ArrayList<>();
        int n = rnd.nextInt(10) < 3 ? 1 : 1 + rnd.nextInt(rnd.nextInt(10) < 2 ? 12 : 5);
        for (int i = 0; i < n * 3 && invited.size() < n; i++) {
            User u = people.get(rnd.nextInt(people.size()));
            if (u == org || invited.contains(u.id) || u.admin()) continue;
            if (!u.team.equals(org.team) && rnd.nextInt(10) < 6) continue;
            invited.add(u.id);
        }
        String[] titles = TITLES.getOrDefault(org.team, new String[] { "Meeting" });
        boolean priv = rnd.nextInt(100) < 7;
        String title = priv ? PRIVATE[rnd.nextInt(PRIVATE.length)] : titles[rnd.nextInt(titles.length)];
        int guests = title.startsWith("Client") || title.startsWith("Interview") || title.startsWith("Partner") ? 1 + rnd.nextInt(3) : 0;
        List<String> needs = new ArrayList<>();
        if (title.contains("call") || title.contains("Client") || rnd.nextInt(10) < 2) needs.add("VIDEO_CALL");
        if (title.contains("Tech talk") || title.contains("Training")) needs.add("PROJECTOR");
        int headcount = 1 + invited.size() + guests;
        // most people take the best room the app suggests; some go straight for their favourite room (and may clash)
        String roomId;
        if (rnd.nextInt(10) < 3) {
            List<Room> fit = new ArrayList<>();
            for (Room r : store.rooms.values()) if (r.capacity >= headcount && !r.needsApproval && Math.abs(r.floor - org.floor) <= 1) fit.add(r);
            if (fit.isEmpty()) return;
            roomId = fit.get(rnd.nextInt(Math.min(3, fit.size()))).id;
        } else {
            List<Map<String, Object>> opts = svc.finder().rooms(start, end, headcount, needs, org, null);
            roomId = null;
            for (Map<String, Object> o : opts) if ((Boolean) o.get("usable")) { roomId = (String) o.get("roomId"); break; }
            if (roomId == null) return;
            if (store.rooms.get(roomId).needsApproval && headcount < 12) return;
        }
        Map<String, Object> body = Json.obj("roomId", roomId, "title", title, "start", start, "end", end, "people", invited,
                "guests", guests, "needs", needs, "privateTitle", priv, "peopleOk", true);
        try {
            svc.create(org, body);
        } catch (Problem p) {
            // refused (taken, or over the weekly limit): some people join the waitlist instead
            if (p.status == 409 && rnd.nextInt(10) < 3) safe(() -> svc.waitFor(org, body));
        }
    }

    // ------------------------------------------------------------------ playing one day

    private static List<Booking> dayBookings(DataStore store, LocalDate d) {
        List<Booking> out = new ArrayList<>();
        long from = Rules.opens(d), to = Rules.closes(d);
        for (Booking b : store.bookings.values()) if (b.start >= from && b.start < to) out.add(b);
        out.sort(Comparator.comparingLong(b -> b.start));
        return out;
    }

    private record Ev(long at, String kind, String bookingId) { }

    /** Check-ins, no-shows, early ends and extensions, in time order, up to `until` (the real now). */
    private static void playDay(BookingService svc, DataStore store, Random rnd, LocalDate d, long until) {
        List<Ev> evs = new ArrayList<>();
        for (Booking b : dayBookings(store, d)) {
            if (b.status != Status.CONFIRMED) continue;
            int roll = rnd.nextInt(100);
            boolean series = b.seriesId != null;
            if (roll < (series ? 6 : 11)) continue;   // nobody comes: the background job gives the room back
            evs.add(new Ev(b.start - rnd.nextInt(11) * Rules.MINUTE + (rnd.nextInt(10) < 2 ? 4 * Rules.MINUTE : 0), "IN", b.id));
            if (b.minutes() >= 45 && rnd.nextInt(100) < 14) {
                long slots = (b.minutes() - 15) / 15;
                evs.add(new Ev(b.start + (1 + rnd.nextInt((int) Math.max(1, slots - 1))) * 15 * Rules.MINUTE + 3 * Rules.MINUTE, "END", b.id));
            } else if (rnd.nextInt(100) < 5) {
                evs.add(new Ev(b.end - 5 * Rules.MINUTE, "EXT", b.id));
            }
        }
        evs.add(new Ev(Rules.closes(d) + 30 * Rules.MINUTE, "TICK", null));
        evs.sort(Comparator.comparingLong(Ev::at));
        for (Ev e : evs) {
            if (e.at() > until) break;
            Clock.fix(Instant.ofEpochMilli(e.at()));
            svc.tick();
            Booking b = e.bookingId() == null ? null : store.bookings.get(e.bookingId());
            if (b == null) continue;
            User someone = store.users.get(b.people.isEmpty() || rnd.nextBoolean() ? b.organiserId : b.people.get(rnd.nextInt(b.people.size())));
            User org = store.users.get(b.organiserId);
            switch (e.kind()) {
                case "IN" -> safe(() -> svc.checkIn(someone, b.id));
                case "END" -> safe(() -> svc.endNow(someone, b.id));
                case "EXT" -> safe(() -> svc.extend(org, b.id, 15));
                default -> { }
            }
        }
    }

    // ------------------------------------------------------------------ things to try in the demo

    private static void demoScenes(DataStore store, BookingService svc, LocalDate today, long now) {
        User priya = store.userByName("priya"), arjun = store.userByName("arjun"), neha = store.userByName("neha");
        LocalDate next = Rules.nextOpenDay(today.plusDays(1));
        List<String> team = new ArrayList<>();
        for (String p : new String[] { "rahul", "aman", "sneha" }) team.add(uid(store, p));

        // 1. Priya has a meeting starting at the next quarter hour today (if the office is open) – ready to check in
        long soon = Rules.ceilStep(now + 2 * Rules.MINUTE);
        if (Rules.openDay(today) && soon >= Rules.opens(today) && soon + 45 * Rules.MINUTE <= Rules.closes(today)) {
            for (String room : new String[] { "R-8", "R-7", "R-2", "R-10" }) {
                Map<String, Object> b = Json.obj("roomId", room, "title", "Sprint review", "start", soon, "end", soon + 45 * Rules.MINUTE,
                        "people", team, "needs", List.of(), "peopleOk", true);
                if (safe(() -> svc.create(priya, b))) break;
            }
        }
        // 2. Damodar is taken on the next working day 11:00-12:00 (Arjun's client demo): try to book it as Priya
        long s11 = Rules.at(next, LocalTime.of(11, 0));
        clearRoom(store, svc, "R-8", s11, s11 + 60 * Rules.MINUTE);
        safe(() -> svc.create(arjun, Json.obj("roomId", "R-8", "title", "Client demo – Ranchi Smart City", "start", s11,
                "end", s11 + 60 * Rules.MINUTE, "people", List.of(uid(store, "kavita"), uid(store, "sana")), "guests", 3,
                "needs", List.of("VIDEO_CALL"), "peopleOk", true)));
        // 3. Karo 15:00-16:00 is Neha's; Priya waits for it. Cancel it as Neha and Priya gets the room.
        long s15 = Rules.at(next, LocalTime.of(15, 0));
        clearRoom(store, svc, "R-6", s15, s15 + 60 * Rules.MINUTE);
        safe(() -> svc.create(neha, Json.obj("roomId", "R-6", "title", "Wireframe walkthrough", "start", s15, "end", s15 + 60 * Rules.MINUTE,
                "people", List.of(uid(store, "farhan")), "peopleOk", true)));
        safe(() -> svc.waitFor(priya, Json.obj("roomId", "R-6", "title", "API design discussion", "start", s15, "end", s15 + 60 * Rules.MINUTE,
                "people", team)));
        // 4. Requests for the boardroom and the training hall, waiting for the facility manager
        LocalDate later = Rules.nextOpenDay(next.plusDays(2));
        long qbr = Rules.at(later, LocalTime.of(14, 0));
        clearRoom(store, svc, "R-11", qbr, qbr + 120 * Rules.MINUTE);
        safe(() -> svc.create(arjun, Json.obj("roomId", "R-11", "title", "Quarterly business review", "start", qbr, "end", qbr + 120 * Rules.MINUTE,
                "people", List.of(uid(store, "alok"), uid(store, "sushmita"), uid(store, "kavita"), uid(store, "manoj"), uid(store, "rakesh")),
                "guests", 4, "needs", List.of("PROJECTOR", "VIDEO_CALL"), "peopleOk", true)));
        LocalDate later2 = Rules.nextOpenDay(later.plusDays(1));
        long tr = Rules.at(later2, LocalTime.of(10, 0));
        clearRoom(store, svc, "R-12", tr, tr + 180 * Rules.MINUTE);
        List<String> eng = new ArrayList<>();
        for (User u : store.users.values()) if (u.team.equals("Engineering") && !u.username.equals("priya")) eng.add(u.id);
        safe(() -> svc.create(priya, Json.obj("roomId", "R-12", "title", "Secure coding workshop", "start", tr, "end", tr + 180 * Rules.MINUTE,
                "people", eng, "needs", List.of("PROJECTOR"), "peopleOk", true)));
        // 5. Koel is closed for carpet cleaning on the day after the next working day
        LocalDate clean = Rules.nextOpenDay(next.plusDays(1));
        safe(() -> svc.block(store.userByName("admin"), Json.obj("roomId", "R-5", "reason", "Carpet cleaning",
                "start", Rules.at(clean, LocalTime.of(8, 0)), "end", Rules.at(clean, LocalTime.of(13, 0))), false));
    }

    /** Makes a demo slot free (cancels whatever the made-up history put there). */
    private static void clearRoom(DataStore store, BookingService svc, String roomId, long s, long e) {
        Room r = store.rooms.get(roomId);
        long buf = r.bufferMin * Rules.MINUTE;
        for (Booking b : new ArrayList<>(store.bookings.values()))
            if (b.roomId.equals(roomId) && b.status.open() && b.start < e + buf && s < b.end + buf)
                safe(() -> svc.cancel(store.userByName("admin"), b.id, "one", "Room needed for a company event"));
    }

    private interface Step { Object run(); }

    private static boolean safe(Step s) {
        try {
            s.run();
            return true;
        } catch (Problem p) {
            return false;
        }
    }
}
