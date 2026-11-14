package com.cecil.kaksh.service;

import com.cecil.kaksh.core.Recurrence;
import com.cecil.kaksh.core.RoomCalendar;
import com.cecil.kaksh.core.Rules;
import com.cecil.kaksh.core.SlotFinder;
import com.cecil.kaksh.model.Block;
import com.cecil.kaksh.model.Booking;
import com.cecil.kaksh.model.Room;
import com.cecil.kaksh.model.Series;
import com.cecil.kaksh.model.Status;
import com.cecil.kaksh.model.User;
import com.cecil.kaksh.model.WaitEntry;
import com.cecil.kaksh.store.DataStore;
import com.cecil.kaksh.util.Clock;
import com.cecil.kaksh.util.Json;
import com.cecil.kaksh.util.Text;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything that changes bookings. The main rule of the whole project lives here:
 *
 *     A ROOM CAN NEVER HAVE TWO BOOKINGS THAT OVERLAP (counting its buffer minutes).
 *
 * How that is guaranteed:
 *   1. Every change runs inside  synchronized (store)  – one change at a time, so two people pressing "Book" at the
 *      same moment are handled one after the other; the second one sees the first booking and gets a clash.
 *   2. Inside that lock: CHECK (RoomCalendar.clashes) and then WRITE. Nobody can slip in between check and write.
 *   3. A repeating meeting is checked date by date BEFORE anything is saved: either all dates are booked, or
 *      (if the person agreed) only the free ones – never a half-done series by accident.
 *   4. An edit sends the version it saw. If somebody else changed the booking meanwhile, the edit is refused
 *      (optimistic locking) instead of silently overwriting.
 *
 * A clash is answered with HTTP 409 and helpful data: what is in the way, the nearest free times in the same room
 * and other rooms that fit – so the person can fix it with one click.
 */
public class BookingService {
    private final DataStore store;
    private final RoomCalendar cal;
    private final SlotFinder finder;

    public BookingService(DataStore store, RoomCalendar cal, SlotFinder finder) {
        this.store = store;
        this.cal = cal;
        this.finder = finder;
    }

    public RoomCalendar calendar() { return cal; }

    public SlotFinder finder() { return finder; }

    // ------------------------------------------------------------------ reading a request

    /** A booking request as typed into the form. */
    public record Req(String roomId, String title, long start, long end, List<String> people, int guests,
                      List<String> needs, boolean privateTitle, String repeat, int count) {
        int headcount() { return 1 + people.size() + guests; }
    }

    public Req read(User who, Map<String, Object> b) {
        String title = Text.clean(Json.str(b, "title"), 80);
        if (Text.blank(title)) title = "Meeting";
        long start = Json.num(b, "start", 0), end = Json.num(b, "end", 0);
        Set<String> people = new LinkedHashSet<>();
        synchronized (store) {
            for (String p : Json.strings(b, "people")) {
                User u = store.users.get(p);
                if (u == null || !u.active) throw Problem.bad("One of the invited people is not in the office list.");
                if (!p.equals(who.id)) people.add(p);
            }
        }
        int guests = (int) Json.num(b, "guests", 0);
        if (guests < 0 || guests > 40) throw Problem.bad("Visitors: 0 to 40.");
        if (people.size() + guests + 1 > Rules.MAX_PEOPLE) throw Problem.bad("At most " + Rules.MAX_PEOPLE + " people in one meeting.");
        List<String> needs = new ArrayList<>();
        for (String n : Json.strings(b, "needs")) {
            if (!Room.EQUIPMENT.contains(n)) throw Problem.bad("Unknown equipment: " + Text.clean(n, 20));
            if (!needs.contains(n)) needs.add(n);
        }
        String repeat = Json.str(b, "repeat", "NONE");
        if (!Recurrence.KINDS.contains(repeat)) throw Problem.bad("Repeat must be none, daily, weekdays or weekly.");
        int count = repeat.equals("NONE") ? 1 : (int) Json.num(b, "count", 1);
        if (count < 1 || count > Rules.MAX_REPEAT) throw Problem.bad("A repeating meeting can have 2 to " + Rules.MAX_REPEAT + " dates.");
        return new Req(Json.str(b, "roomId"), title, start, end, new ArrayList<>(people), guests, needs,
                Json.bool(b, "privateTitle", false), repeat, count);
    }

    private Room room(String id) {
        Room r = id == null ? null : store.rooms.get(id);
        if (r == null) throw Problem.notFound("Room");
        return r;
    }

    private Booking booking(String id) {
        Booking b = id == null ? null : store.bookings.get(id);
        if (b == null) throw Problem.notFound("Booking");
        return b;
    }

    // ------------------------------------------------------------------ checking (no saving)

    /**
     * Checks a request without saving anything. The website calls this while the person is still typing, so the
     * form can say "free" or show what is in the way and the suggestions.
     */
    public Map<String, Object> check(User who, Req q, String ignoreId) {
        synchronized (store) {
            Room r = room(q.roomId());
            List<Map<String, Object>> occ = new ArrayList<>();
            int free = 0;
            Map<String, Object> firstBad = null;
            for (Recurrence.Slot s : Recurrence.expand(q.start(), q.end(), q.repeat(), q.count())) {
                String ruleProblem = null;
                try { Rules.checkTime(s.start(), s.end(), who); } catch (Problem p) { ruleProblem = p.getMessage(); }
                List<RoomCalendar.Busy> clashes = cal.clashes(r.id, s.start(), s.end(), r.bufferMin * Rules.MINUTE, ignoreId);
                boolean ok = ruleProblem == null && clashes.isEmpty() && r.active && r.capacity >= q.headcount();
                Map<String, Object> o = Json.obj("start", s.start(), "end", s.end(), "free", ok, "rule", ruleProblem,
                        "clash", clashes.isEmpty() ? null : describe(clashes.get(0), who));
                occ.add(o);
                if (ok) free++;
                else if (firstBad == null) firstBad = o;
            }
            List<String> problems = new ArrayList<>();
            if (!r.active) problems.add(r.name + " is not available for booking at the moment.");
            if (r.capacity < q.headcount())
                problems.add(r.name + " has " + r.capacity + " seats – your meeting has " + q.headcount() + " people.");
            for (String n : q.needs()) if (!r.has(n)) problems.add(r.name + " has no " + n.toLowerCase().replace('_', ' ') + ".");
            // people: anyone invited (and the organiser) already in another meeting then?
            List<String> everyone = new ArrayList<>(q.people());
            everyone.add(0, who.id);
            List<Map<String, Object>> peopleClashes = new ArrayList<>();
            for (Map<String, Object> o : occ) peopleClashes.addAll(finder.peopleClashes(everyone, (Long) o.get("start"), (Long) o.get("end"), ignoreId));
            Map<String, Object> quota = quota(who, q, ignoreId, free);
            // suggestions, if anything is wrong with the (first) date
            Map<String, Object> alt = null;
            Map<String, Object> target = firstBad != null ? firstBad : (!problems.isEmpty() || !peopleClashes.isEmpty() ? occ.get(0) : null);
            if (target != null) {
                long s = (Long) target.get("start"), e = (Long) target.get("end");
                boolean timeOk = target.get("rule") == null;
                alt = Json.obj(
                        "times", r.active ? finder.nearestTimes(r, s, e, everyone, who, ignoreId, 5) : List.of(),
                        "rooms", timeOk ? usable(finder.rooms(s, e, q.headcount(), q.needs(), who, ignoreId), r.id, 4) : List.of());
            }
            boolean ok = free == occ.size() && problems.isEmpty();
            return Json.obj("ok", ok, "roomId", r.id, "roomName", r.name, "approval", r.needsApproval && !who.admin(),
                    "occurrences", occ, "freeCount", free, "problems", problems, "peopleClashes", peopleClashes,
                    "quota", quota, "alternatives", alt);
        }
    }

    private static List<Map<String, Object>> usable(List<Map<String, Object>> rooms, String notThis, int max) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rooms) if ((Boolean) r.get("usable") && !r.get("roomId").equals(notThis) && out.size() < max) out.add(r);
        return out;
    }

    /** What is in the way, told so that a private title stays private. */
    private Map<String, Object> describe(RoomCalendar.Busy busy, User viewer) {
        if (busy.kind().equals("BLOCK")) {
            Block bl = store.blocks.get(busy.id());
            return Json.obj("kind", "BLOCK", "start", busy.start(), "end", busy.end(),
                    "label", "Closed" + (bl == null || Text.blank(bl.reason) ? "" : ": " + bl.reason));
        }
        Booking b = store.bookings.get(busy.id());
        boolean see = b != null && (!b.privateTitle || b.involves(viewer.id) || viewer.admin());
        return Json.obj("kind", "BOOKING", "bookingId", b == null ? null : b.id, "start", busy.start(), "end", busy.end(),
                "label", b == null ? "Booked" : (see ? b.title : "Private meeting"),
                "by", b == null ? null : store.userName(b.organiserId), "pending", b != null && b.status == Status.PENDING);
    }

    /** Fair use: hours this person organises in the week of the meeting (Monday to Sunday). */
    private Map<String, Object> quota(User who, Req q, String ignoreId, int newDates) {
        LocalDate d = Rules.dayOf(q.start());
        LocalDate mon = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        long from = Rules.at(mon, java.time.LocalTime.MIDNIGHT), to = Rules.at(mon.plusDays(7), java.time.LocalTime.MIDNIGHT);
        long used = usedMinutes(who.id, from, to, ignoreId);
        long add = 0;
        for (Recurrence.Slot s : Recurrence.expand(q.start(), q.end(), q.repeat(), q.count()))
            if (s.start() >= from && s.start() < to) add += (s.end() - s.start()) / Rules.MINUTE;
        boolean ok = who.admin() || used + add <= Rules.WEEK_QUOTA_MIN;
        return Json.obj("usedMin", used, "addMin", add, "limitMin", Rules.WEEK_QUOTA_MIN, "ok", ok, "exempt", who.admin());
    }

    private long usedMinutes(String userId, long from, long to, String ignoreId) {
        long used = 0;
        for (Booking b : store.bookings.values()) {
            if (!userId.equals(b.organiserId) || b.id.equals(ignoreId) || b.start < from || b.start >= to) continue;
            if (b.status.open() || b.status == Status.COMPLETED) used += b.minutes();
        }
        return used;
    }

    private void countStopped(int n) {
        store.meta.put("stats.clashesStopped", Json.num(store.meta, "stats.clashesStopped", 0) + n);
    }

    // ------------------------------------------------------------------ booking

    /**
     * Books a room (one date or a repeating series).
     *   skipTaken      – for a series: book the free dates and skip the taken ones (otherwise: all or nothing)
     *   peopleOk       – the organiser saw that some invited people are busy and wants to book anyway
     */
    public List<Booking> create(User who, Map<String, Object> body) {
        Req q = read(who, body);
        boolean skipTaken = Json.bool(body, "skipTaken", false);
        boolean peopleOk = Json.bool(body, "peopleOk", false);
        synchronized (store) {
            Map<String, Object> chk = check(who, q, null);
            @SuppressWarnings("unchecked") List<Map<String, Object>> occ = (List<Map<String, Object>>) chk.get("occurrences");
            @SuppressWarnings("unchecked") List<String> problems = (List<String>) chk.get("problems");
            int free = (Integer) chk.get("freeCount");
            if (!problems.isEmpty()) throw new Problem(409, problems.get(0)).with(Json.obj("kind", "ROOM", "check", chk));
            if (free == 0 || (free < occ.size() && !skipTaken)) {
                countStopped(1);
                store.save();
                String msg;
                Map<String, Object> bad = null;
                for (Map<String, Object> o : occ) if (!(Boolean) o.get("free")) { bad = o; break; }
                if (bad != null && bad.get("rule") != null) msg = (String) bad.get("rule");
                else if (occ.size() == 1) msg = "That time is already taken in " + chk.get("roomName") + ". Pick a suggestion below.";
                else msg = (occ.size() - free) + " of the " + occ.size() + " dates are taken. Book only the free ones, or change the time.";
                throw new Problem(409, msg).with(Json.obj("kind", "ROOM", "check", chk));
            }
            if (!(Boolean) ((Map<?, ?>) chk.get("quota")).get("ok"))
                throw new Problem(409, "This would take you over the fair-use limit of " + (Rules.WEEK_QUOTA_MIN / 60)
                        + " hours of meetings a week.").with(Json.obj("kind", "QUOTA", "check", chk));
            if (!((List<?>) chk.get("peopleClashes")).isEmpty() && !peopleOk)
                throw new Problem(409, "Some people are already in another meeting then.").with(Json.obj("kind", "PEOPLE", "check", chk));

            Room r = room(q.roomId());
            long now = Clock.millis();
            Series series = null;
            if (q.count() > 1) {
                series = new Series();
                series.id = store.nextId("S", 101);
                series.organiserId = who.id;
                series.roomId = r.id;
                series.title = q.title();
                series.repeat = q.repeat();
                series.count = q.count();
                series.createdAt = now;
                store.series.put(series.id, series);
            }
            List<Booking> made = new ArrayList<>();
            for (Map<String, Object> o : occ) {
                long s = (Long) o.get("start"), e = (Long) o.get("end");
                if (!(Boolean) o.get("free")) {
                    if (series != null) series.skipped.add(Rules.dayOf(s).toString());
                    continue;
                }
                Booking b = new Booking();
                b.id = store.nextId("B", 1001);
                b.roomId = r.id;
                b.organiserId = who.id;
                b.title = q.title();
                b.start = s;
                b.end = e;
                b.people = new ArrayList<>(q.people());
                b.guests = q.guests();
                b.needs = new ArrayList<>(q.needs());
                b.privateTitle = q.privateTitle();
                b.status = r.needsApproval && !who.admin() ? Status.PENDING : Status.CONFIRMED;
                b.seriesId = series == null ? null : series.id;
                b.createdAt = now;
                b.note(now, who.id, b.status == Status.PENDING ? "Requested (waits for approval)" : "Booked");
                store.bookings.put(b.id, b);
                cal.add(b);
                if (series != null) series.bookingIds.add(b.id);
                made.add(b);
            }
            Booking first = made.get(0);
            String when = Format.when(first.start, first.end) + (made.size() > 1 ? " (+" + (made.size() - 1) + " more dates)" : "");
            for (String p : first.people)
                store.notify(p, store.userName(who.id) + " invited you: " + first.title + ", " + r.name + ", " + when, "booking/" + first.id);
            if (first.status == Status.PENDING)
                for (User a : store.users.values())
                    if (a.admin() && a.active) store.notify(a.id, store.userName(who.id) + " asks for " + r.name + ": " + when, "approvals");
            store.log(who.id, made.size() > 1 ? "booking.series" : "booking.create", first.id,
                    r.name + ", " + when + (series != null && !series.skipped.isEmpty() ? ", skipped " + series.skipped.size() : ""));
            store.save("bookings", "series", "notes", "audit");
            return made;
        }
    }

    // ------------------------------------------------------------------ changing

    private void mustOwn(User who, Booking b) {
        if (!who.id.equals(b.organiserId) && !who.admin()) throw Problem.forbidden("Only the organiser can change this booking.");
    }

    /** Move / change a booking that has not started. The version must match (optimistic locking). */
    public Booking update(User who, String id, Map<String, Object> body) {
        synchronized (store) {
            Booking b = booking(id);
            mustOwn(who, b);
            int seen = (int) Json.num(body, "version", -1);
            if (seen != b.version)
                throw Problem.conflict("Somebody changed this booking a moment ago. Please look at the new version and try again.")
                        .with(Json.obj("kind", "VERSION", "version", b.version));
            if (b.status != Status.CONFIRMED && b.status != Status.PENDING) throw Problem.bad("Only a booking that has not started can be changed.");
            if (b.start <= Clock.millis()) throw Problem.bad("This meeting has already started.");
            Map<String, Object> merged = Json.obj("roomId", b.roomId, "title", b.title, "start", b.start, "end", b.end,
                    "people", b.people, "guests", b.guests, "needs", b.needs, "privateTitle", b.privateTitle);
            merged.putAll(body);
            merged.put("repeat", "NONE");
            User organiser = store.users.get(b.organiserId);
            Req q = read(organiser == null ? who : organiser, merged);
            Map<String, Object> chk = check(organiser == null ? who : organiser, q, b.id);
            if (!(Boolean) chk.get("ok")) {
                countStopped(1);
                @SuppressWarnings("unchecked") List<String> problems = (List<String>) chk.get("problems");
                @SuppressWarnings("unchecked") List<Map<String, Object>> occ = (List<Map<String, Object>>) chk.get("occurrences");
                String msg = !problems.isEmpty() ? problems.get(0)
                        : occ.get(0).get("rule") != null ? (String) occ.get(0).get("rule") : "That time is already taken. Pick a suggestion below.";
                throw new Problem(409, msg).with(Json.obj("kind", "ROOM", "check", chk));
            }
            if (!(Boolean) ((Map<?, ?>) chk.get("quota")).get("ok"))
                throw new Problem(409, "This would go over the weekly fair-use limit.").with(Json.obj("kind", "QUOTA", "check", chk));
            if (!((List<?>) chk.get("peopleClashes")).isEmpty() && !Json.bool(body, "peopleOk", false))
                throw new Problem(409, "Some people are already in another meeting then.").with(Json.obj("kind", "PEOPLE", "check", chk));
            String oldRoom = b.roomId;
            long oldStart = b.start, oldEnd = b.end;
            boolean moved = !oldRoom.equals(q.roomId()) || oldStart != q.start() || oldEnd != q.end();
            List<String> before = new ArrayList<>(b.people);
            cal.remove(b.roomId, b.start, b.id);
            b.roomId = q.roomId();
            b.title = q.title();
            b.start = q.start();
            b.end = q.end();
            b.people = new ArrayList<>(q.people());
            b.guests = q.guests();
            b.needs = new ArrayList<>(q.needs());
            b.privateTitle = q.privateTitle();
            Room r = room(b.roomId);
            if (!oldRoom.equals(b.roomId)) b.status = r.needsApproval && !who.admin() ? Status.PENDING : Status.CONFIRMED;
            b.version++;
            cal.add(b);
            long now = Clock.millis();
            b.note(now, who.id, moved ? "Moved to " + r.name + ", " + Format.when(b.start, b.end) : "Details changed");
            for (String p : b.people)
                store.notify(p, (before.contains(p) ? "Changed: " : store.userName(b.organiserId) + " invited you: ") + b.title + ", "
                        + r.name + ", " + Format.when(b.start, b.end), "booking/" + b.id);
            for (String p : before) if (!b.people.contains(p)) store.notify(p, "You were taken off: " + b.title, null);
            store.log(who.id, "booking.update", b.id, moved ? "to " + r.name + ", " + Format.when(b.start, b.end) : "details");
            if (moved) giveToWaitlist(oldRoom, oldStart, oldEnd);
            store.save("bookings", "waitlist", "notes", "audit");
            return b;
        }
    }

    /** Cancel one date, or this date and all later dates of the series. */
    public List<Booking> cancel(User who, String id, String scope, String reason) {
        synchronized (store) {
            Booking b = booking(id);
            mustOwn(who, b);
            if (!b.status.open() || b.status == Status.CHECKED_IN) throw Problem.bad("Only a booking that has not started can be cancelled. Use \"End now\" instead.");
            List<Booking> targets = new ArrayList<>();
            if ("series".equals(scope) && b.seriesId != null) {
                Series s = store.series.get(b.seriesId);
                if (s != null) for (String bid : s.bookingIds) {
                    Booking x = store.bookings.get(bid);
                    if (x != null && x.start >= b.start && (x.status == Status.CONFIRMED || x.status == Status.PENDING)) targets.add(x);
                }
            } else targets.add(b);
            String why = Text.clean(reason, 160);
            long now = Clock.millis();
            for (Booking x : targets) {
                cal.remove(x.roomId, x.start, x.id);
                x.status = Status.CANCELLED;
                x.reason = why == null ? "" : why;
                x.version++;
                x.note(now, who.id, "Cancelled" + (Text.blank(why) ? "" : ": " + why));
                if (x == targets.get(0)) for (String p : x.people)
                    store.notify(p, "Cancelled: " + x.title + ", " + Format.when(x.start, x.end) + (targets.size() > 1 ? " and " + (targets.size() - 1) + " later dates" : ""), "booking/" + x.id);
                if (!who.id.equals(x.organiserId) && x == targets.get(0))
                    store.notify(x.organiserId, "The facility manager cancelled " + x.title + (Text.blank(why) ? "" : ": " + why), "booking/" + x.id);
            }
            store.log(who.id, "booking.cancel", b.id, targets.size() + " date" + (targets.size() == 1 ? "" : "s"));
            for (Booking x : targets) giveToWaitlist(x.roomId, x.start, x.end);
            store.save("bookings", "waitlist", "notes", "audit");
            return targets;
        }
    }

    /** "We are here": opens 10 minutes before the start, until 15 minutes after. Organiser or anyone invited. */
    public Booking checkIn(User who, String id) {
        synchronized (store) {
            Booking b = booking(id);
            if (!b.involves(who.id) && !who.admin()) throw Problem.forbidden("Only people in this meeting can check in.");
            if (b.status == Status.CHECKED_IN) return b;
            if (b.status == Status.PENDING) throw Problem.bad("This booking is still waiting for approval.");
            if (b.status != Status.CONFIRMED) throw Problem.bad("This booking is " + b.status.name().toLowerCase().replace('_', ' ') + ".");
            long now = Clock.millis();
            if (now < b.start - Rules.CHECKIN_EARLY_MIN * Rules.MINUTE)
                throw Problem.bad("Check-in opens " + Rules.CHECKIN_EARLY_MIN + " minutes before the start.");
            if (now > b.start + Rules.NO_SHOW_MIN * Rules.MINUTE) throw Problem.bad("Too late – the room was given back.");
            b.status = Status.CHECKED_IN;
            b.checkedInAt = now;
            b.version++;
            b.note(now, who.id, "Checked in");
            store.log(who.id, "booking.checkin", b.id, store.roomName(b.roomId));
            store.save("bookings", "audit");
            return b;
        }
    }

    /** Finished early: the rest of the time is given back (and offered to the waitlist). */
    public Booking endNow(User who, String id) {
        synchronized (store) {
            Booking b = booking(id);
            if (!b.involves(who.id) && !who.admin()) throw Problem.forbidden("Only people in this meeting can end it.");
            if (b.status != Status.CHECKED_IN) throw Problem.bad("Only a meeting that is going on can be ended.");
            long now = Clock.millis();
            long newEnd = Math.max(b.start + Rules.STEP_MIN * Rules.MINUTE, Rules.ceilStep(now));
            if (newEnd >= b.end) newEnd = b.end;
            long oldEnd = b.end;
            cal.remove(b.roomId, b.start, b.id);
            b.end = newEnd;
            b.status = Status.COMPLETED;
            b.version++;
            cal.add(b);
            b.note(now, who.id, "Ended early, " + ((oldEnd - newEnd) / Rules.MINUTE) + " min given back");
            store.log(who.id, "booking.end", b.id, ((oldEnd - newEnd) / Rules.MINUTE) + " min given back");
            store.meta.put("stats.minutesGivenBack", Json.num(store.meta, "stats.minutesGivenBack", 0) + (oldEnd - newEnd) / Rules.MINUTE);
            giveToWaitlist(b.roomId, newEnd, oldEnd);
            store.save("bookings", "waitlist", "notes", "audit");
            return b;
        }
    }

    /** Need more time? Only if the room is free right after (with its buffer). */
    public Booking extend(User who, String id, int minutes) {
        synchronized (store) {
            Booking b = booking(id);
            mustOwn(who, b);
            if (b.status != Status.CHECKED_IN && b.status != Status.CONFIRMED) throw Problem.bad("This booking cannot be made longer.");
            if (minutes != 15 && minutes != 30 && minutes != 60) throw Problem.bad("Extend by 15, 30 or 60 minutes.");
            long newEnd = b.end + minutes * Rules.MINUTE;
            User organiser = store.users.get(b.organiserId);
            Rules.checkTime(Math.max(b.start, Rules.floorStep(Clock.millis())) , newEnd, organiser);
            if ((newEnd - b.start) / Rules.MINUTE > (organiser != null && organiser.admin() ? Rules.MAX_MIN_ADMIN : Rules.MAX_MIN))
                throw Problem.bad("That would make the meeting too long.");
            Room r = room(b.roomId);
            List<RoomCalendar.Busy> clash = cal.clashes(r.id, b.start, newEnd, r.bufferMin * Rules.MINUTE, b.id);
            if (!clash.isEmpty()) {
                countStopped(1);
                store.save();
                Map<String, Object> d = describe(clash.get(0), who);
                throw Problem.conflict("Cannot extend: the room is needed at " + Format.time((Long) d.get("start"))
                        + " (" + d.get("label") + ") and needs " + r.bufferMin + " min to get ready.");
            }
            cal.remove(b.roomId, b.start, b.id);
            b.end = newEnd;
            b.version++;
            cal.add(b);
            b.note(Clock.millis(), who.id, "Extended by " + minutes + " min");
            store.log(who.id, "booking.extend", b.id, "+" + minutes + " min");
            store.save("bookings", "audit");
            return b;
        }
    }

    // ------------------------------------------------------------------ approvals (facility manager)

    public Booking decide(User admin, String id, boolean approve, String note) {
        if (!admin.admin()) throw Problem.forbidden("Only the facility manager can approve bookings.");
        synchronized (store) {
            Booking b = booking(id);
            if (b.status != Status.PENDING) throw Problem.bad("This booking is not waiting for approval.");
            long now = Clock.millis();
            String why = Text.clean(note, 160);
            b.decidedBy = admin.id;
            b.reason = why == null ? "" : why;
            b.version++;
            if (approve) {
                b.status = Status.CONFIRMED;
                b.note(now, admin.id, "Approved" + (Text.blank(why) ? "" : ": " + why));
                store.notify(b.organiserId, "Approved: " + b.title + " in " + store.roomName(b.roomId) + ", " + Format.when(b.start, b.end), "booking/" + b.id);
                for (String p : b.people) store.notify(p, "Confirmed: " + b.title + ", " + Format.when(b.start, b.end), "booking/" + b.id);
            } else {
                cal.remove(b.roomId, b.start, b.id);
                b.status = Status.DECLINED;
                b.note(now, admin.id, "Declined" + (Text.blank(why) ? "" : ": " + why));
                store.notify(b.organiserId, "Not approved: " + b.title + " in " + store.roomName(b.roomId) + (Text.blank(why) ? "" : " – " + why), "booking/" + b.id);
                giveToWaitlist(b.roomId, b.start, b.end);
            }
            store.log(admin.id, approve ? "booking.approve" : "booking.decline", b.id, why);
            store.save("bookings", "waitlist", "notes", "audit");
            return b;
        }
    }

    // ------------------------------------------------------------------ waitlist

    /** Join the waitlist for a taken time. If the time is actually free, the person should simply book it. */
    public WaitEntry waitFor(User who, Map<String, Object> body) {
        Req q = read(who, body);
        synchronized (store) {
            Room r = room(q.roomId());
            Rules.checkTime(q.start(), q.end(), who);
            if (r.capacity < q.headcount()) throw Problem.bad(r.name + " is too small for " + q.headcount() + " people.");
            if (cal.free(r, q.start(), q.end(), null)) throw Problem.bad("That time is free – you can book it straight away.");
            for (WaitEntry w : store.waitlist.values())
                if (w.status.equals("WAITING") && w.userId.equals(who.id) && w.roomId.equals(r.id) && w.start == q.start())
                    throw Problem.conflict("You are already waiting for this time.");
            WaitEntry w = new WaitEntry();
            w.id = store.nextId("W", 101);
            w.roomId = r.id;
            w.userId = who.id;
            w.title = q.title();
            w.start = q.start();
            w.end = q.end();
            w.people = new ArrayList<>(q.people());
            w.guests = q.guests();
            w.createdAt = Clock.millis();
            store.waitlist.put(w.id, w);
            int place = 0;
            for (WaitEntry x : store.waitlist.values())
                if (x.status.equals("WAITING") && x.roomId.equals(r.id) && x.start < w.end && w.start < x.end) place++;
            store.log(who.id, "wait.join", w.id, r.name + ", " + Format.when(w.start, w.end) + ", place " + place);
            store.save("waitlist", "audit");
            return w;
        }
    }

    public WaitEntry leaveWait(User who, String id) {
        synchronized (store) {
            WaitEntry w = store.waitlist.get(id);
            if (w == null || (!w.userId.equals(who.id) && !who.admin())) throw Problem.notFound("Waitlist entry");
            if (!w.status.equals("WAITING")) throw Problem.bad("You are not waiting for this any more.");
            w.status = "CANCELLED";
            store.log(who.id, "wait.leave", w.id, null);
            store.save("waitlist", "audit");
            return w;
        }
    }

    /**
     * Some time in a room became free (cancel, decline, no-show, end early, moved). Offer it to the waitlist:
     * oldest entry first; the room is booked for them straight away if it is free, still in the future and
     * within their fair-use limit. Must be called while holding the store lock.
     */
    void giveToWaitlist(String roomId, long from, long to) {
        Room r = store.rooms.get(roomId);
        if (r == null || !r.active) return;
        long now = Clock.millis();
        List<WaitEntry> waiting = new ArrayList<>();
        for (WaitEntry w : store.waitlist.values())
            if (w.status.equals("WAITING") && w.roomId.equals(roomId) && w.start < to + r.bufferMin * Rules.MINUTE
                    && from < w.end + r.bufferMin * Rules.MINUTE) waiting.add(w);
        waiting.sort(Comparator.comparingLong((WaitEntry w) -> w.createdAt).thenComparing(w -> w.id));
        for (WaitEntry w : waiting) {
            if (w.start <= now) { w.status = "EXPIRED"; continue; }
            User u = store.users.get(w.userId);
            if (u == null || !u.active) { w.status = "CANCELLED"; continue; }
            if (!cal.free(r, w.start, w.end, null)) continue;
            Req q = new Req(r.id, w.title, w.start, w.end, w.people, w.guests, List.of(), false, "NONE", 1);
            if (!(Boolean) quota(u, q, null, 1).get("ok")) continue;
            Booking b = new Booking();
            b.id = store.nextId("B", 1001);
            b.roomId = r.id;
            b.organiserId = u.id;
            b.title = w.title;
            b.start = w.start;
            b.end = w.end;
            b.people = new ArrayList<>(w.people);
            b.guests = w.guests;
            b.status = r.needsApproval && !u.admin() ? Status.PENDING : Status.CONFIRMED;
            b.createdAt = now;
            b.note(now, null, "Booked from the waitlist");
            store.bookings.put(b.id, b);
            cal.add(b);
            w.status = "BOOKED";
            w.bookingId = b.id;
            store.meta.put("stats.waitlistWins", Json.num(store.meta, "stats.waitlistWins", 0) + 1);
            store.notify(u.id, "Good news! " + r.name + " became free and is now yours: " + b.title + ", " + Format.when(b.start, b.end), "booking/" + b.id);
            for (String p : b.people) store.notify(p, store.userName(u.id) + " invited you: " + b.title + ", " + r.name + ", " + Format.when(b.start, b.end), "booking/" + b.id);
            store.log(u.id, "wait.booked", b.id, r.name + ", " + Format.when(b.start, b.end));
        }
    }

    // ------------------------------------------------------------------ maintenance blocks (facility manager)

    /**
     * Close a room for a while. Bookings in that time are MOVED to the best free room that fits (same time, enough
     * seats, the same equipment, no approval needed); if there is none they are cancelled. Everyone is told.
     * With dryRun = true nothing changes: the website shows the plan first.
     */
    public Map<String, Object> block(User admin, Map<String, Object> body, boolean dryRun) {
        if (!admin.admin()) throw Problem.forbidden("Only the facility manager can close rooms.");
        String reason = Text.clean(Json.str(body, "reason"), 100);
        if (Text.blank(reason)) throw Problem.bad("Write why the room is closed (for example \"AC repair\").");
        long start = Json.num(body, "start", 0), end = Json.num(body, "end", 0);
        if (end <= start) throw Problem.bad("The end must be after the start.");
        if (end - start > 14L * 24 * 60 * Rules.MINUTE) throw Problem.bad("Close a room for at most 14 days at a time.");
        if (end < Clock.millis()) throw Problem.bad("That time is already over.");
        synchronized (store) {
            Room r = room(Json.str(body, "roomId"));
            for (Block x : store.blocks.values())
                if (x.active && x.roomId.equals(r.id) && x.start < end && start < x.end) throw Problem.conflict(r.name + " is already closed for part of that time.");
            List<Booking> hit = new ArrayList<>();
            for (RoomCalendar.Busy busy : cal.clashes(r.id, start, end, 0, null)) {
                Booking b = store.bookings.get(busy.id());
                if (b != null && b.status.open()) hit.add(b);
            }
            hit.sort(Comparator.comparingLong(b -> b.start));
            List<Map<String, Object>> plan = new ArrayList<>();
            // plan the moves one by one; a planned move also takes its new room (so two moves do not pick the same slot)
            List<Booking> tempMoved = new ArrayList<>();
            List<String> tempRoomBefore = new ArrayList<>();
            for (Booking b : hit) cal.remove(b.roomId, b.start, b.id);
            for (Booking b : hit) {
                User org = store.users.get(b.organiserId);
                String target = null;
                for (Map<String, Object> opt : finder.rooms(b.start, b.end, b.headcount(), b.needs, org, b.id)) {
                    Room o = store.rooms.get((String) opt.get("roomId"));
                    if ((Boolean) opt.get("usable") && !o.id.equals(r.id) && !o.needsApproval) { target = o.id; break; }
                }
                plan.add(Json.obj("bookingId", b.id, "title", b.title, "start", b.start, "end", b.end, "organiser", store.userName(b.organiserId),
                        "action", target == null ? "CANCEL" : "MOVE", "to", target == null ? null : store.roomName(target)));
                tempRoomBefore.add(b.roomId);
                if (target != null) { b.roomId = target; cal.add(b); tempMoved.add(b); }
            }
            if (dryRun) {
                // undo the planning: put everything back exactly as it was
                for (Booking b : tempMoved) cal.remove(b.roomId, b.start, b.id);
                for (int i = 0; i < hit.size(); i++) { hit.get(i).roomId = tempRoomBefore.get(i); cal.add(hit.get(i)); }
                return Json.obj("room", r.name, "start", start, "end", end, "plan", plan);
            }
            Block bl = new Block();
            bl.id = store.nextId("K", 101);
            bl.roomId = r.id;
            bl.start = start;
            bl.end = end;
            bl.reason = reason;
            bl.byId = admin.id;
            bl.createdAt = Clock.millis();
            store.blocks.put(bl.id, bl);
            cal.add(bl);
            long now = Clock.millis();
            for (Booking b : hit) {
                b.version++;
                if (tempMoved.contains(b)) {
                    b.note(now, admin.id, "Moved from " + r.name + " (closed: " + reason + ") to " + store.roomName(b.roomId));
                    String msg = r.name + " is closed (" + reason + "). Your meeting " + b.title + " moved to " + store.roomName(b.roomId) + ", " + Format.when(b.start, b.end);
                    store.notify(b.organiserId, msg, "booking/" + b.id);
                    for (String p : b.people) store.notify(p, msg, "booking/" + b.id);
                } else {
                    b.status = Status.CANCELLED;
                    b.reason = "Room closed: " + reason;
                    b.note(now, admin.id, "Cancelled – room closed (" + reason + "), no other room was free");
                    String msg = r.name + " is closed (" + reason + ") and no other room was free: " + b.title + ", " + Format.when(b.start, b.end) + " is cancelled.";
                    store.notify(b.organiserId, msg, "booking/" + b.id);
                    for (String p : b.people) store.notify(p, msg, "booking/" + b.id);
                }
            }
            store.log(admin.id, "room.close", r.id, reason + ", " + Format.when(start, end) + ", " + hit.size() + " booking(s) moved or cancelled");
            store.save("blocks", "bookings", "notes", "audit");
            return Json.obj("room", r.name, "start", start, "end", end, "plan", plan, "blockId", bl.id);
        }
    }

    public Block unblock(User admin, String id) {
        if (!admin.admin()) throw Problem.forbidden("Only the facility manager can open rooms.");
        synchronized (store) {
            Block bl = store.blocks.get(id);
            if (bl == null || !bl.active) throw Problem.notFound("Closing");
            cal.remove(bl.roomId, bl.start, bl.id);
            bl.active = false;
            store.log(admin.id, "room.reopen", bl.roomId, bl.reason);
            giveToWaitlist(bl.roomId, bl.start, bl.end);
            store.save("blocks", "bookings", "waitlist", "notes", "audit");
            return bl;
        }
    }

    /** Room settings (facility manager): seats, buffer, approval, open/closed for booking, note. */
    public Room updateRoom(User admin, String id, Map<String, Object> body) {
        if (!admin.admin()) throw Problem.forbidden("Only the facility manager can change rooms.");
        synchronized (store) {
            Room r = room(id);
            int cap = (int) Json.num(body, "capacity", r.capacity);
            int buf = (int) Json.num(body, "bufferMin", r.bufferMin);
            if (cap < 1 || cap > 200) throw Problem.bad("Seats: 1 to 200.");
            if (buf < 0 || buf > 60 || buf % 5 != 0) throw Problem.bad("Buffer: 0 to 60 minutes, in steps of 5.");
            r.capacity = cap;
            r.bufferMin = buf;
            r.needsApproval = Json.bool(body, "needsApproval", r.needsApproval);
            r.active = Json.bool(body, "active", r.active);
            String note = Text.clean(Json.str(body, "note"), 140);
            if (note != null) r.note = note;
            List<String> eq = new ArrayList<>();
            if (body.containsKey("equipment")) {
                for (String n : Json.strings(body, "equipment")) if (Room.EQUIPMENT.contains(n) && !eq.contains(n)) eq.add(n);
                r.equipment = eq;
            }
            store.log(admin.id, "room.update", r.id, r.capacity + " seats, buffer " + r.bufferMin + " min" + (r.needsApproval ? ", approval" : "") + (r.active ? "" : ", not bookable"));
            store.save("rooms", "audit");
            return r;
        }
    }

    // ------------------------------------------------------------------ the background job (every minute)

    /**
     *   PENDING whose start has come         -> EXPIRED  (nobody approved it in time)
     *   CONFIRMED, start + 15 min, no check-in -> RELEASED (a no-show: the room is given back)
     *   CHECKED_IN whose end has come         -> COMPLETED
     *   WAITING whose start has come          -> EXPIRED
     * Returns how many bookings changed.
     */
    public int tick() {
        synchronized (store) {
            long now = Clock.millis();
            int changed = 0;
            List<long[]> freed = new ArrayList<>();
            List<String> freedRooms = new ArrayList<>();
            for (Booking b : store.bookings.values()) {
                if (b.status == Status.PENDING && now >= b.start) {
                    cal.remove(b.roomId, b.start, b.id);
                    b.status = Status.EXPIRED;
                    b.version++;
                    b.note(now, null, "Not approved in time");
                    store.notify(b.organiserId, "Not approved in time: " + b.title + ", " + Format.when(b.start, b.end), "booking/" + b.id);
                    changed++;
                } else if (b.status == Status.CONFIRMED && now >= b.start + Rules.NO_SHOW_MIN * Rules.MINUTE) {
                    cal.remove(b.roomId, b.start, b.id);
                    b.status = Status.RELEASED;
                    b.version++;
                    b.note(now, null, "Nobody checked in – room given back");
                    store.notify(b.organiserId, "Nobody checked in to " + b.title + " in " + store.roomName(b.roomId) + ", so the room was given back.", "booking/" + b.id);
                    store.meta.put("stats.minutesGivenBack", Json.num(store.meta, "stats.minutesGivenBack", 0) + Math.max(0, (b.end - now) / Rules.MINUTE));
                    freed.add(new long[] { now, b.end });
                    freedRooms.add(b.roomId);
                    changed++;
                } else if (b.status == Status.CHECKED_IN && now >= b.end) {
                    b.status = Status.COMPLETED;
                    b.version++;
                    changed++;
                }
            }
            for (int i = 0; i < freed.size(); i++) giveToWaitlist(freedRooms.get(i), freed.get(i)[0], freed.get(i)[1]);
            for (WaitEntry w : store.waitlist.values()) if (w.status.equals("WAITING") && w.start <= now) { w.status = "EXPIRED"; changed++; }
            if (changed > 0) store.save("bookings", "waitlist", "notes");
            return changed;
        }
    }
}
