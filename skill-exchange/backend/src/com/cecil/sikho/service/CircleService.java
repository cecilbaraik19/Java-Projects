package com.cecil.sikho.service;

import com.cecil.sikho.ledger.TimeBank;
import com.cecil.sikho.match.Matcher;
import com.cecil.sikho.model.Circle;
import com.cecil.sikho.model.Locality;
import com.cecil.sikho.model.Skill;
import com.cecil.sikho.model.User;
import com.cecil.sikho.store.DataStore;
import com.cecil.sikho.util.Clock;
import com.cecil.sikho.util.Json;
import com.cecil.sikho.util.Text;

import java.util.Map;

/**
 * Skill circles: small group sessions in a public place (a community hall, a park, the library).
 * Joining costs 1 credit (held in escrow). When the host marks the circle done, the host gets them all.
 * Leaving before the start, or the host cancelling, gives the credit back. A circle the host never closes is
 * cancelled automatically 3 days after its time.
 */
public class CircleService {
    public static final long DAY = 24 * 3_600_000L;

    private final DataStore store;
    private final TimeBank bank;

    public CircleService(DataStore store, TimeBank bank) {
        this.store = store;
        this.bank = bank;
    }

    public Circle create(User me, Map<String, Object> body) {
        String title = Text.clean(Json.str(body, "title"), 80);
        String place = Text.clean(Json.str(body, "place"), 120);
        long at = Json.num(body, "at", 0);
        int hours = (int) Json.num(body, "hours", 2);
        int seats = (int) Json.num(body, "seats", 8);
        long now = Clock.millis();
        if (title == null) throw Problem.bad("Give the circle a title.");
        if (place == null) throw Problem.bad("Say where it happens (a public place: hall, library, park ...).");
        if (at < now + 3_600_000L || at > now + 60 * DAY) throw Problem.bad("Pick a time between 1 hour and 60 days from now.");
        if (hours < 1 || hours > 4) throw Problem.bad("A circle lasts 1 to 4 hours.");
        if (seats < 2 || seats > 20) throw Problem.bad("2 to 20 seats.");
        synchronized (store) {
            if (!store.canAct(me)) throw Problem.forbidden("Your account cannot host circles right now.");
            Skill s = store.skills.get(Json.str(body, "skillId"));
            if (s == null) throw Problem.bad("Choose a skill.");
            Locality loc = store.localities.get(Json.str(body, "localityId", me.localityId));
            if (loc == null) throw Problem.bad("Choose a locality.");
            long hosting = store.circles.values().stream().filter(c -> c.status == Circle.Status.OPEN && c.hostId.equals(me.id)).count();
            if (hosting >= 3) throw Problem.bad("You already host 3 upcoming circles.");
            Circle c = new Circle();
            c.id = store.nextId("C", 101);
            c.hostId = me.id;
            c.skillId = s.id;
            c.title = title;
            c.details = Text.clean(Json.str(body, "details"), 600);
            c.place = place;
            c.localityId = loc.id;
            c.at = at;
            c.hours = hours;
            c.seats = seats;
            c.createdAt = now;
            store.circles.put(c.id, c);
            store.log(me.id, "circle.create", c.id, s.name + " at " + place);
            store.save("circles", "audit");
            return c;
        }
    }

    private Circle get(String id) {
        Circle c = store.circles.get(id);
        if (c == null) throw Problem.notFound("Circle");
        return c;
    }

    public Circle join(User me, String id) {
        synchronized (store) {
            if (!store.canAct(me)) throw Problem.forbidden("Your account cannot join circles right now.");
            Circle c = get(id);
            if (c.status != Circle.Status.OPEN || Clock.millis() >= c.at) throw Problem.conflict("This circle is closed.");
            if (c.hostId.equals(me.id)) throw Problem.bad("You are the host.");
            if (c.attendees.contains(me.id)) throw Problem.conflict("You already joined.");
            if (c.attendees.size() >= c.seats) throw Problem.conflict("The circle is full.");
            User host = store.users.get(c.hostId);
            if (Matcher.blocked(me, host)) throw Problem.forbidden("You cannot join this circle.");
            bank.post(me.id, TimeBank.ESCROW, 1, "HOLD", c.id, "Seat in circle: " + c.title);
            c.attendees.add(me.id);
            store.log(me.id, "circle.join", c.id, null);
            store.notify(c.hostId, me.firstName() + " joined your circle \"" + c.title + "\" (" + c.attendees.size() + "/" + c.seats + ").", "circles");
            store.save("circles", "ledger", "notes", "audit");
            return c;
        }
    }

    public Circle leave(User me, String id) {
        synchronized (store) {
            Circle c = get(id);
            if (!c.attendees.contains(me.id)) throw Problem.bad("You have not joined this circle.");
            if (c.status != Circle.Status.OPEN || Clock.millis() >= c.at) throw Problem.conflict("The circle has started; you can no longer leave.");
            c.attendees.remove(me.id);
            bank.post(TimeBank.ESCROW, me.id, 1, "REFUND", c.id, "Left circle: " + c.title);
            store.log(me.id, "circle.leave", c.id, null);
            store.save("circles", "ledger", "audit");
            return c;
        }
    }

    /** The host (or a moderator) calls it off: everybody gets their credit back. */
    public Circle cancel(User me, String id, String reason) {
        synchronized (store) {
            Circle c = get(id);
            if (!c.hostId.equals(me.id) && !me.isModerator()) throw Problem.forbidden("Only the host can cancel this circle.");
            if (c.status != Circle.Status.OPEN) throw Problem.conflict("This circle is already closed.");
            cancelNow(c, me.id, Text.clean(reason, 200));
            store.save("circles", "ledger", "notes", "audit");
            return c;
        }
    }

    void cancelNow(Circle c, String by, String reason) {
        for (String a : c.attendees) {
            bank.post(TimeBank.ESCROW, a, 1, "REFUND", c.id, "Circle cancelled: " + c.title);
            store.notify(a, "The circle \"" + c.title + "\" was cancelled" + (reason == null ? "" : ": " + reason) + ". Your credit is back.", "circles");
        }
        c.status = Circle.Status.CANCELLED;
        c.closedAt = Clock.millis();
        store.log(by, "circle.cancel", c.id, reason);
    }

    /** The host says it happened: escrow pays the host one credit per person who came. */
    public Circle complete(User me, String id) {
        synchronized (store) {
            Circle c = get(id);
            if (!c.hostId.equals(me.id)) throw Problem.forbidden("Only the host can close this circle.");
            if (c.status != Circle.Status.OPEN) throw Problem.conflict("This circle is already closed.");
            if (Clock.millis() < c.at) throw Problem.bad("The circle has not started yet.");
            int held = bank.heldFor(c.id);
            if (held > 0) bank.post(TimeBank.ESCROW, c.hostId, held, "RELEASE", c.id, "Hosted circle: " + c.title);
            c.status = Circle.Status.DONE;
            c.closedAt = Clock.millis();
            for (String a : c.attendees) store.notify(a, "Thanks for joining \"" + c.title + "\"!", "circles");
            store.log(me.id, "circle.done", c.id, held + " credit(s) to the host");
            store.save("circles", "ledger", "notes", "audit");
            return c;
        }
    }

    /** Background job: circles nobody closed 3 days after their time are cancelled and refunded. */
    public int tick() {
        synchronized (store) {
            int n = 0;
            long now = Clock.millis();
            for (Circle c : store.circles.values()) {
                if (c.status == Circle.Status.OPEN && now - c.at >= 3 * DAY) {
                    cancelNow(c, null, "the host did not confirm it happened");
                    n++;
                }
            }
            if (n > 0) store.save("circles", "ledger", "notes", "audit");
            return n;
        }
    }

    public void expectedEscrow(Map<String, Integer> out) {
        synchronized (store) {
            for (Circle c : store.circles.values())
                if (c.status == Circle.Status.OPEN && !c.attendees.isEmpty()) out.put(c.id, c.attendees.size());
        }
    }
}
