package com.cecil.sikho.service;

import com.cecil.sikho.ledger.TimeBank;
import com.cecil.sikho.match.Matcher;
import com.cecil.sikho.model.Exchange;
import com.cecil.sikho.model.Exchange.Status;
import com.cecil.sikho.model.Listing;
import com.cecil.sikho.model.Message;
import com.cecil.sikho.model.Review;
import com.cecil.sikho.model.User;
import com.cecil.sikho.store.DataStore;
import com.cecil.sikho.util.Clock;
import com.cecil.sikho.util.Json;
import com.cecil.sikho.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything that happens to an exchange (a learning session between two neighbours), and the time credits
 * that move with it. See the picture in {@link Exchange}.
 */
public class ExchangeService {
    public static final long HOUR = 3_600_000L, DAY = 24 * HOUR;
    public static final int MAX_HOURS = 3;
    public static final long LATE_CANCEL_MS = DAY;          // cancelling an accepted session less than 24 h before = late
    public static final long AUTO_COMPLETE_MS = 3 * DAY;    // one "done" and 3 quiet days = completed
    public static final long UNCONFIRMED_MS = 7 * DAY;      // nobody said anything for 7 days = credits go back
    public static final int MAX_OPEN_PER_PAIR = 2;
    public static final Set<String> REVIEW_TAGS = Set.of("Patient", "Clear", "On time", "Friendly", "Well prepared",
            "Eager to learn", "Fun", "Knows a lot");

    private final DataStore store;
    private final TimeBank bank;

    public ExchangeService(DataStore store, TimeBank bank) {
        this.store = store;
        this.bank = bank;
    }

    // ------------------------------------------------------------------ starting an exchange

    /**
     * Sends a request from a card on the board.
     *  - On an OFFER card ("I can teach guitar") the signed-in member is the learner: their credits go to escrow now.
     *  - On a WANT card ("I want to learn guitar") the signed-in member offers to teach: the learner's credits are
     *    held when the learner accepts.
     */
    public Exchange request(User me, String listingId, Map<String, Object> body) {
        int hours = (int) Json.num(body, "hours", 1);
        long at = Json.num(body, "at", 0);
        String place = Text.clean(Json.str(body, "place"), 120);
        String text = Text.clean(Json.str(body, "message"), 1000);
        if (hours < 1 || hours > MAX_HOURS) throw Problem.bad("A session is 1 to " + MAX_HOURS + " hours.");
        long now = Clock.millis();
        if (at < now + HOUR) throw Problem.bad("Pick a time at least one hour from now.");
        if (at > now + 30 * DAY) throw Problem.bad("Pick a time in the next 30 days.");
        if (text == null) throw Problem.bad("Write a short hello: what you know already and what you hope to learn.");
        synchronized (store) {
            if (!store.canAct(me)) throw Problem.forbidden("Your account cannot start exchanges right now.");
            Listing l = store.listings.get(listingId);
            if (l == null || !l.active) throw Problem.notFound("Card");
            User owner = store.users.get(l.userId);
            if (!store.canAct(owner)) throw Problem.notFound("Card");
            if (owner.id.equals(me.id)) throw Problem.bad("This is your own card.");
            if (Matcher.blocked(me, owner)) throw Problem.forbidden("You cannot send requests to this member.");
            boolean iLearn = l.type == Listing.Type.OFFER;
            String teacherId = iLearn ? owner.id : me.id, learnerId = iLearn ? me.id : owner.id;
            int open = 0;
            for (Exchange e : store.exchanges.values())
                if (e.isOpen() && e.teacherId.equals(teacherId) && e.learnerId.equals(learnerId)) open++;
            if (open >= MAX_OPEN_PER_PAIR) throw Problem.conflict("You already have " + open + " open sessions with " + owner.firstName() + ". Finish one first.");
            Exchange e = new Exchange();
            e.id = store.nextId("X", 1001);
            e.listingId = l.id;
            e.skillId = l.skillId;
            e.teacherId = teacherId;
            e.learnerId = learnerId;
            e.initiatorId = me.id;
            e.hours = hours;
            e.at = at;
            e.place = place != null ? place : defaultPlace(l, store.users.get(teacherId));
            e.createdAt = now;
            e.updatedAt = now;
            if (iLearn) bank.post(me.id, TimeBank.ESCROW, hours, "HOLD", e.id, "Held for " + store.skillName(l.skillId) + " with " + owner.firstName());
            Safety.Checked c = Safety.check(text, false);
            e.messages.add(new Message(me.id, c.text(), now, c.flag()));
            store.exchanges.put(e.id, e);
            String what = store.skillName(l.skillId);
            store.notify(owner.id, iLearn ? me.firstName() + " asked you to teach " + what + " (" + hours + " h)."
                    : me.firstName() + " offered to teach you " + what + " (" + hours + " h).", "exchange/" + e.id);
            store.log(me.id, "exchange.request", e.id, what + ", " + hours + " h, " + (iLearn ? "as learner" : "as teacher"));
            store.save("exchanges", "ledger", "notes", "audit");
            return e;
        }
    }

    private String defaultPlace(Listing l, User teacher) {
        if (l.mode == Listing.Mode.ONLINE) return "Online (video call)";
        return switch (l.place) {
            case MY_PLACE -> l.type == Listing.Type.OFFER ? "At " + teacher.firstName() + "'s place, " + store.localityName(teacher.localityId) : "At the learner's place";
            case YOUR_PLACE -> l.type == Listing.Type.OFFER ? "At the learner's place" : "At " + teacher.firstName() + "'s place";
            case PUBLIC -> "Community library, " + store.localityName(teacher.localityId);
        };
    }

    private Exchange get(String id) {
        Exchange e = store.exchanges.get(id);
        if (e == null) throw Problem.notFound("Exchange");
        return e;
    }

    private Exchange mine(User me, String id) {
        Exchange e = get(id);
        if (!e.involves(me.id)) throw Problem.notFound("Exchange");
        return e;
    }

    /** Exchanges a member may open: their own, or any for a moderator. */
    public Exchange visible(User me, String id) {
        synchronized (store) {
            Exchange e = get(id);
            if (!e.involves(me.id) && !me.isModerator()) throw Problem.notFound("Exchange");
            return e;
        }
    }

    private void touch(Exchange e, String userId, String action, String detail) {
        e.updatedAt = Clock.millis();
        store.log(userId, action, e.id, detail);
    }

    private void system(Exchange e, String text) {
        e.messages.add(new Message(null, text, Clock.millis(), null));
    }

    // ------------------------------------------------------------------ answering

    public Exchange accept(User me, String id) {
        synchronized (store) {
            Exchange e = mine(me, id);
            if (e.status != Status.REQUESTED) throw Problem.conflict("This request is " + e.status.name().toLowerCase() + ".");
            if (me.id.equals(e.initiatorId)) throw Problem.bad("Wait for " + store.users.get(e.partnerOf(me.id)).firstName() + " to answer.");
            if (e.at <= Clock.millis()) throw Problem.bad("The proposed time has passed. Ask for a new time in a message, or decline.");
            if (me.id.equals(e.learnerId)) {
                // a teacher offered: the learner commits now
                bank.post(me.id, TimeBank.ESCROW, e.hours, "HOLD", e.id, "Held for " + store.skillName(e.skillId) + " with " + store.users.get(e.teacherId).firstName());
            }
            e.status = Status.ACCEPTED;
            e.acceptedAt = Clock.millis();
            system(e, "Accepted. You can now see each other's phone number. Meet at: " + e.place + ".");
            touch(e, me.id, "exchange.accept", null);
            store.notify(e.partnerOf(me.id), me.firstName() + " accepted: " + store.skillName(e.skillId) + " is on!", "exchange/" + e.id);
            store.save("exchanges", "ledger", "notes", "audit");
            return e;
        }
    }

    public Exchange decline(User me, String id, String reason) {
        synchronized (store) {
            Exchange e = mine(me, id);
            if (e.status != Status.REQUESTED) throw Problem.conflict("This request is " + e.status.name().toLowerCase() + ".");
            if (me.id.equals(e.initiatorId)) throw Problem.bad("You sent this request. Use Cancel instead.");
            close(e, Status.DECLINED, me.id, Text.clean(reason, 200));
            refund(e, "Request declined");
            touch(e, me.id, "exchange.decline", e.reason);
            store.notify(e.partnerOf(me.id), me.firstName() + " could not take your " + store.skillName(e.skillId) + " request"
                    + (e.reason == null ? "." : ": " + e.reason), "exchange/" + e.id);
            store.save("exchanges", "ledger", "notes", "audit");
            return e;
        }
    }

    /** Either person calls it off before the session starts. Less than 24 hours before an accepted session = late. */
    public Exchange cancel(User me, String id, String reason) {
        synchronized (store) {
            Exchange e = mine(me, id);
            if (e.status != Status.REQUESTED && e.status != Status.ACCEPTED) throw Problem.conflict("This exchange can no longer be cancelled.");
            long now = Clock.millis();
            if (e.status == Status.ACCEPTED && now >= e.at) throw Problem.bad("The session time has passed. Use \"It did not happen\" instead.");
            boolean late = e.status == Status.ACCEPTED && e.at - now < LATE_CANCEL_MS;
            close(e, Status.CANCELLED, me.id, Text.clean(reason, 200));
            e.lateCancel = late;
            refund(e, "Cancelled");
            touch(e, me.id, "exchange.cancel", (late ? "LATE " : "") + (e.reason == null ? "" : e.reason));
            store.notify(e.partnerOf(me.id), me.firstName() + " cancelled " + store.skillName(e.skillId)
                    + (e.reason == null ? "." : ": " + e.reason), "exchange/" + e.id);
            store.save("exchanges", "ledger", "notes", "audit");
            return e;
        }
    }

    private void close(Exchange e, Status s, String by, String reason) {
        e.status = s;
        e.cancelledBy = by;
        e.reason = reason;
        e.closedAt = Clock.millis();
    }

    /** Gives back whatever escrow holds for this exchange to the learner. */
    private void refund(Exchange e, String why) {
        int held = bank.heldFor(e.id);
        if (held > 0) bank.post(TimeBank.ESCROW, e.learnerId, held, "REFUND", e.id, why);
    }

    // ------------------------------------------------------------------ talking

    public Exchange message(User me, String id, String text) {
        String t = Text.clean(text, 1000);
        if (t == null) throw Problem.bad("Write a message first.");
        synchronized (store) {
            Exchange e = mine(me, id);
            User partner = store.users.get(e.partnerOf(me.id));
            if (Matcher.blocked(me, partner)) throw Problem.forbidden("You cannot message this member.");
            boolean recent = e.isOpen() || Clock.millis() - e.closedAt < 7 * DAY;
            if (!recent) throw Problem.bad("This exchange is closed. Start a new one to talk again.");
            boolean accepted = e.status == Status.ACCEPTED || e.status == Status.COMPLETED || e.status == Status.DISPUTED;
            Safety.Checked c = Safety.check(t, accepted);
            e.messages.add(new Message(me.id, c.text(), Clock.millis(), c.flag()));
            if (e.messages.size() > 200) e.messages.remove(0);
            e.updatedAt = Clock.millis();
            if ("MONEY".equals(c.flag())) store.log(me.id, "safety.money", e.id, "message mentions money");
            store.notify(partner.id, me.firstName() + ": " + (c.text().length() > 60 ? c.text().substring(0, 57) + "…" : c.text()), "exchange/" + e.id);
            store.save("exchanges", "notes", "audit");
            return e;
        }
    }

    // ------------------------------------------------------------------ after the session

    /** "It happened": both say so → the teacher is paid. One says so and 3 days pass → paid automatically. */
    public Exchange done(User me, String id) {
        synchronized (store) {
            Exchange e = mine(me, id);
            if (e.status != Status.ACCEPTED) throw Problem.conflict("Only an accepted session can be marked done.");
            if (Clock.millis() < e.at) throw Problem.bad("The session has not started yet.");
            if (me.id.equals(e.teacherId)) e.teacherDone = true; else e.learnerDone = true;
            if (e.firstDoneAt == 0) e.firstDoneAt = Clock.millis();
            touch(e, me.id, "exchange.done", null);
            if (e.teacherDone && e.learnerDone) complete(e, "Both confirmed");
            else {
                system(e, me.firstName() + " says the session happened. When you agree, press Done too (or it completes by itself in 3 days).");
                store.notify(e.partnerOf(me.id), me.firstName() + " marked " + store.skillName(e.skillId) + " as done. Please confirm.", "exchange/" + e.id);
            }
            store.save("exchanges", "ledger", "notes", "audit");
            return e;
        }
    }

    private void complete(Exchange e, String why) {
        int held = bank.heldFor(e.id);
        if (held > 0) bank.post(TimeBank.ESCROW, e.teacherId, held, "RELEASE", e.id, why + ": " + store.skillName(e.skillId));
        e.status = Status.COMPLETED;
        e.closedAt = Clock.millis();
        system(e, "Completed. " + held + " credit" + (held == 1 ? "" : "s") + " went to " + store.users.get(e.teacherId).firstName() + ". Please leave a review.");
        store.notify(e.teacherId, "You earned " + held + " time credit" + (held == 1 ? "" : "s") + " for teaching " + store.skillName(e.skillId) + ".", "exchange/" + e.id);
        store.notify(e.learnerId, "How was " + store.skillName(e.skillId) + " with " + store.users.get(e.teacherId).firstName() + "? Leave a review.", "exchange/" + e.id);
    }

    /** "It did not happen": the credits stay in escrow until a moderator decides. */
    public Exchange dispute(User me, String id, String text) {
        String t = Text.clean(text, 600);
        if (t == null) throw Problem.bad("Tell the moderators what happened.");
        synchronized (store) {
            Exchange e = mine(me, id);
            if (e.status != Status.ACCEPTED) throw Problem.conflict("Only an accepted session can be disputed.");
            if (Clock.millis() < e.at) throw Problem.bad("The session has not started yet. You can cancel it instead.");
            if (e.teacherDone && e.learnerDone) throw Problem.conflict("Both of you already confirmed it.");
            e.status = Status.DISPUTED;
            e.disputeBy = me.id;
            e.messages.add(new Message(me.id, "Dispute: " + t, Clock.millis(), "DISPUTE"));
            system(e, "A moderator will look at this. The credits stay in escrow until then.");
            touch(e, me.id, "exchange.dispute", t);
            store.notify(e.partnerOf(me.id), me.firstName() + " says the " + store.skillName(e.skillId) + " session did not happen. A moderator will help.", "exchange/" + e.id);
            for (User u : store.users.values()) if (u.isModerator()) store.notify(u.id, "New dispute on " + e.id + " (" + store.skillName(e.skillId) + ").", "moderation");
            store.save("exchanges", "notes", "audit");
            return e;
        }
    }

    /**
     * A moderator decides a dispute.
     *   TEACHER - the session happened (or the learner did not come): the teacher is paid.
     *   LEARNER - it did not happen because of the teacher: the learner gets the credits back.
     *   SPLIT   - both partly: half each (the teacher gets the smaller half).
     */
    public Exchange resolve(User mod, String id, String result, String note) {
        if (!mod.isModerator()) throw Problem.forbidden("Only moderators can decide disputes.");
        String n = Text.clean(note, 300);
        if (n == null) throw Problem.bad("Write a short note to explain the decision (both people will see it).");
        synchronized (store) {
            Exchange e = get(id);
            if (e.status != Status.DISPUTED) throw Problem.conflict("This exchange is not disputed.");
            int held = bank.heldFor(e.id);
            switch (result == null ? "" : result) {
                case "TEACHER" -> {
                    if (held > 0) bank.post(TimeBank.ESCROW, e.teacherId, held, "RELEASE", e.id, "Moderator decision");
                    e.status = Status.COMPLETED;
                    if (e.learnerId.equals(e.disputeBy)) e.faultOf = e.learnerId;
                }
                case "LEARNER" -> {
                    if (held > 0) bank.post(TimeBank.ESCROW, e.learnerId, held, "REFUND", e.id, "Moderator decision");
                    e.status = Status.CANCELLED;
                    e.faultOf = e.teacherId;
                }
                case "SPLIT" -> {
                    if (held < 2) throw Problem.bad("A 1-credit session cannot be split. Choose teacher or learner.");
                    bank.post(TimeBank.ESCROW, e.teacherId, held / 2, "RELEASE", e.id, "Moderator decision (split)");
                    bank.post(TimeBank.ESCROW, e.learnerId, held - held / 2, "REFUND", e.id, "Moderator decision (split)");
                    e.status = Status.COMPLETED;
                }
                default -> throw Problem.bad("Choose TEACHER, LEARNER or SPLIT.");
            }
            e.disputeResult = result;
            e.closedAt = Clock.millis();
            system(e, "Moderator decision: " + switch (result) {
                case "TEACHER" -> "the teacher keeps the credits.";
                case "LEARNER" -> "the credits go back to the learner.";
                default -> "the credits are split.";
            } + " Note: " + n);
            touch(e, mod.id, "dispute.resolve", result + ": " + n);
            store.notify(e.teacherId, "The moderators decided the dispute on " + e.id + ".", "exchange/" + e.id);
            store.notify(e.learnerId, "The moderators decided the dispute on " + e.id + ".", "exchange/" + e.id);
            store.save("exchanges", "ledger", "notes", "audit");
            return e;
        }
    }

    /** Stars and a few words, once per person, within 30 days of completion. */
    public Review review(User me, String id, Map<String, Object> body) {
        int stars = (int) Json.num(body, "stars", 0);
        if (stars < 1 || stars > 5) throw Problem.bad("Choose 1 to 5 stars.");
        String text = Text.clean(Json.str(body, "text"), 400);
        List<String> tags = new ArrayList<>();
        for (String t : Json.strings(body, "tags")) if (REVIEW_TAGS.contains(t) && !tags.contains(t)) tags.add(t);
        synchronized (store) {
            Exchange e = mine(me, id);
            if (e.status != Status.COMPLETED) throw Problem.conflict("You can review after the session is completed.");
            if (Clock.millis() - e.closedAt > 30 * DAY) throw Problem.bad("Reviews are open for 30 days after a session.");
            boolean iLearned = me.id.equals(e.learnerId);
            if (iLearned ? e.learnerReviewed : e.teacherReviewed) throw Problem.conflict("You already reviewed this session.");
            Review r = new Review();
            r.id = store.nextId("R", 1001);
            r.exchangeId = e.id;
            r.fromId = me.id;
            r.toId = e.partnerOf(me.id);
            r.aboutTeacher = iLearned;
            r.stars = stars;
            r.text = text;
            r.tags = tags;
            r.at = Clock.millis();
            store.reviews.put(r.id, r);
            if (iLearned) e.learnerReviewed = true; else e.teacherReviewed = true;
            store.log(me.id, "review", e.id, stars + " stars");
            store.notify(r.toId, me.firstName() + " gave you " + stars + "★" + (text == null ? "" : ": \"" + text + "\""), "profile/" + r.toId);
            store.save("exchanges", "reviews", "notes", "audit");
            return r;
        }
    }

    // ------------------------------------------------------------------ background job (every minute)

    /**
     * Keeps exchanges moving when people forget:
     *   - a request nobody answered before its time → EXPIRED, credits back;
     *   - one person said done and 3 days passed → COMPLETED, teacher paid;
     *   - nobody said anything 7 days after the session → NOT_CONFIRMED, credits back.
     * Returns how many exchanges changed.
     */
    public int tick() {
        synchronized (store) {
            long now = Clock.millis();
            int changed = 0;
            for (Exchange e : store.exchanges.values()) {
                int before = changed;
                if (e.status == Status.REQUESTED && e.at <= now) {
                    close(e, Status.EXPIRED, null, "No answer before the session time");
                    refund(e, "Request expired");
                    system(e, "This request expired because it was not answered in time. Any held credits went back.");
                    store.notify(e.initiatorId, "Your " + store.skillName(e.skillId) + " request expired without an answer.", "exchange/" + e.id);
                    changed++;
                } else if (e.status == Status.ACCEPTED && e.firstDoneAt > 0 && now - e.firstDoneAt >= AUTO_COMPLETE_MS) {
                    complete(e, "Completed automatically after 3 days");
                    changed++;
                } else if (e.status == Status.ACCEPTED && e.firstDoneAt == 0 && now - e.at >= UNCONFIRMED_MS) {
                    close(e, Status.NOT_CONFIRMED, null, "Nobody confirmed the session within 7 days");
                    refund(e, "Not confirmed");
                    system(e, "Nobody confirmed this session within 7 days, so the credits went back to the learner.");
                    changed++;
                }
                if (changed > before) e.updatedAt = now;
            }
            if (changed > 0) {
                store.log(null, "exchange.tick", "exchanges", changed + " updated");
                store.save("exchanges", "ledger", "notes", "audit");
            }
            return changed;
        }
    }

    /** How many credits escrow should hold for each open exchange (for the ledger check). */
    public void expectedEscrow(Map<String, Integer> out) {
        synchronized (store) {
            for (Exchange e : store.exchanges.values()) {
                boolean held = e.status == Status.ACCEPTED || e.status == Status.DISPUTED
                        || (e.status == Status.REQUESTED && e.learnerId.equals(e.initiatorId));
                if (held) out.put(e.id, e.hours);
            }
        }
    }

    /** The buttons the website shows for this person on this exchange. */
    public static List<String> actionsFor(Exchange e, User me, long now) {
        List<String> a = new ArrayList<>();
        boolean involved = e.involves(me.id);
        if (involved) {
            switch (e.status) {
                case REQUESTED -> {
                    if (me.id.equals(e.initiatorId)) a.add("cancel");
                    else if (e.at > now) { a.add("accept"); a.add("decline"); }
                    else a.add("decline");
                }
                case ACCEPTED -> {
                    if (now < e.at) a.add("cancel");
                    else {
                        boolean iDone = me.id.equals(e.teacherId) ? e.teacherDone : e.learnerDone;
                        if (!iDone) a.add("done");
                        if (!(e.teacherDone && e.learnerDone)) a.add("dispute");
                    }
                }
                case COMPLETED -> {
                    boolean reviewed = me.id.equals(e.learnerId) ? e.learnerReviewed : e.teacherReviewed;
                    if (!reviewed && now - e.closedAt <= 30 * DAY) a.add("review");
                }
                default -> { }
            }
            if (e.isOpen() || now - e.closedAt < 7 * DAY) a.add("message");
            a.add("report");
        }
        if (me.isModerator() && e.status == Status.DISPUTED) a.add("resolve");
        return a;
    }
}
