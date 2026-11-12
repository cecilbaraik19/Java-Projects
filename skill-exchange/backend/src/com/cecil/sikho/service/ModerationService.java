package com.cecil.sikho.service;

import com.cecil.sikho.ledger.TimeBank;
import com.cecil.sikho.model.Circle;
import com.cecil.sikho.model.Exchange;
import com.cecil.sikho.model.Message;
import com.cecil.sikho.model.Report;
import com.cecil.sikho.model.Skill;
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
 * Keeping the community safe: reports from members, warnings, pausing (suspending) an account, credit corrections
 * and the skill catalogue. Only moderators can do these things, except sending a report.
 */
public class ModerationService {
    public static final Set<String> REASONS = Set.of("NO_SHOW", "MONEY", "RUDE", "UNSAFE", "FAKE", "SPAM", "OTHER");

    private final DataStore store;
    private final TimeBank bank;
    private final AuthService auth;
    private final CircleService circles;

    public ModerationService(DataStore store, TimeBank bank, AuthService auth, CircleService circles) {
        this.store = store;
        this.bank = bank;
        this.auth = auth;
        this.circles = circles;
    }

    static void mod(User u) {
        if (!u.isModerator()) throw Problem.forbidden("Only moderators can do this.");
    }

    public Report report(User me, Map<String, Object> body) {
        String reason = Json.str(body, "reason");
        String text = Text.clean(Json.str(body, "text"), 800);
        if (reason == null || !REASONS.contains(reason)) throw Problem.bad("Choose a reason.");
        if (text == null) throw Problem.bad("Tell the moderators what happened.");
        synchronized (store) {
            User target = store.users.get(Json.str(body, "targetId"));
            if (target == null) throw Problem.notFound("Member");
            if (target.id.equals(me.id)) throw Problem.bad("You cannot report yourself.");
            String exId = Json.str(body, "exchangeId");
            if (exId != null) {
                Exchange e = store.exchanges.get(exId);
                if (e == null || !e.involves(me.id) || !e.involves(target.id)) throw Problem.bad("That exchange is not between you two.");
            }
            for (Report r : store.reports.values())
                if ("OPEN".equals(r.status) && r.reporterId.equals(me.id) && r.targetId.equals(target.id))
                    throw Problem.conflict("You already reported " + target.firstName() + ". A moderator will look at it soon.");
            Report r = new Report();
            r.id = store.nextId("RP", 101);
            r.reporterId = me.id;
            r.targetId = target.id;
            r.exchangeId = exId;
            r.reason = reason;
            r.text = text;
            r.createdAt = Clock.millis();
            store.reports.put(r.id, r);
            store.log(me.id, "report.create", target.id, reason);
            for (User u : store.users.values()) if (u.isModerator()) store.notify(u.id, "New report about " + target.name + " (" + reason + ").", "moderation");
            store.save("reports", "notes", "audit");
            return r;
        }
    }

    /** DISMISS (nothing wrong), WARN (a warning is recorded and sent) or SUSPEND (account paused). */
    public Report resolve(User me, String id, String action, String note) {
        mod(me);
        String n = Text.clean(note, 300);
        if (n == null) throw Problem.bad("Write a short note about the decision.");
        synchronized (store) {
            Report r = store.reports.get(id);
            if (r == null) throw Problem.notFound("Report");
            if (!"OPEN".equals(r.status)) throw Problem.conflict("This report is already closed.");
            User target = store.users.get(r.targetId);
            switch (action == null ? "" : action) {
                case "DISMISS" -> { }
                case "WARN" -> {
                    target.warnings++;
                    store.notify(target.id, "A moderator sent you a warning: " + n + " Please follow the community rules.", "home");
                }
                case "SUSPEND" -> suspendNow(me, target, n);
                default -> throw Problem.bad("Choose DISMISS, WARN or SUSPEND.");
            }
            r.status = "RESOLVED";
            r.action = action;
            r.note = n;
            r.resolvedBy = me.id;
            r.resolvedAt = Clock.millis();
            store.log(me.id, "report.resolve", r.id, action + ": " + n);
            store.notify(r.reporterId, "Thank you. The moderators looked at your report about " + target.firstName() + ".", "home");
            store.save("reports", "users", "exchanges", "circles", "ledger", "notes", "audit");
            return r;
        }
    }

    public User suspend(User me, String userId, String reason) {
        mod(me);
        String n = Text.clean(reason, 300);
        if (n == null) throw Problem.bad("Write the reason (the member will see it).");
        synchronized (store) {
            User u = store.users.get(userId);
            if (u == null) throw Problem.notFound("Member");
            if (u.suspended) throw Problem.conflict("Already paused.");
            suspendNow(me, u, n);
            store.save("users", "exchanges", "circles", "ledger", "notes", "audit");
            return u;
        }
    }

    /**
     * Pauses an account: the person is signed out, their cards disappear from the board, their open exchanges are
     * cancelled (credits back to the learners), the circles they host are cancelled and they leave circles they joined.
     */
    void suspendNow(User me, User u, String reason) {
        if (u.id.equals(me.id)) throw Problem.bad("You cannot pause your own account.");
        if (u.isModerator()) throw Problem.bad("Remove the moderator role first.");
        u.suspended = true;
        u.suspendReason = reason;
        auth.endSessionsOf(u.id);
        long now = Clock.millis();
        for (Exchange e : store.exchanges.values()) {
            if (!e.involves(u.id) || !e.isOpen()) continue;
            int held = bank.heldFor(e.id);
            if (held > 0) bank.post(TimeBank.ESCROW, e.learnerId, held, "REFUND", e.id, "Member paused by a moderator");
            e.status = Exchange.Status.CANCELLED;
            e.cancelledBy = me.id;
            e.reason = "A moderator paused one member's account.";
            e.closedAt = now;
            e.updatedAt = now;
            e.messages.add(new Message(null, "This exchange was cancelled by a moderator. Any held credits went back to the learner.", now, null));
            store.notify(e.partnerOf(u.id), "Your " + store.skillName(e.skillId) + " exchange was cancelled by a moderator.", "exchange/" + e.id);
        }
        for (Circle c : store.circles.values()) {
            if (c.status != Circle.Status.OPEN) continue;
            if (c.hostId.equals(u.id)) circles.cancelNow(c, me.id, "the host's account was paused");
            else if (c.attendees.remove(u.id)) bank.post(TimeBank.ESCROW, u.id, 1, "REFUND", c.id, "Member paused");
        }
        store.log(me.id, "member.suspend", u.id, reason);
    }

    public User reinstate(User me, String userId) {
        mod(me);
        synchronized (store) {
            User u = store.users.get(userId);
            if (u == null) throw Problem.notFound("Member");
            if (!u.suspended) throw Problem.conflict("This account is not paused.");
            u.suspended = false;
            u.suspendReason = null;
            store.log(me.id, "member.reinstate", u.id, null);
            store.notify(u.id, "Welcome back. Your account is active again.", "home");
            store.save("users", "notes", "audit");
            return u;
        }
    }

    public User setRole(User me, String userId, String role) {
        mod(me);
        synchronized (store) {
            User u = store.users.get(userId);
            if (u == null) throw Problem.notFound("Member");
            com.cecil.sikho.model.Role r = com.cecil.sikho.model.Role.parse(role);
            if (r == null) throw Problem.bad("Unknown role.");
            if (u.id.equals(me.id) && r != com.cecil.sikho.model.Role.MODERATOR) throw Problem.bad("You cannot remove your own moderator role.");
            if (u.suspended) throw Problem.bad("Reinstate the account first.");
            u.role = r;
            store.log(me.id, "member.role", u.id, r.label);
            store.save("users", "audit");
            return u;
        }
    }

    /** A correction by a moderator (for example after a technical problem). Positive = from the bank to the member. */
    public Map<String, Object> adjust(User me, String userId, int amount, String reason) {
        mod(me);
        String n = Text.clean(reason, 200);
        if (n == null) throw Problem.bad("Write the reason (it is kept in the ledger for ever).");
        if (amount == 0 || Math.abs(amount) > 10) throw Problem.bad("Correct by 1 to 10 credits (up or down).");
        synchronized (store) {
            User u = store.users.get(userId);
            if (u == null) throw Problem.notFound("Member");
            if (amount > 0) bank.post(TimeBank.BANK, u.id, amount, "ADJUST", null, n);
            else bank.post(u.id, TimeBank.BANK, -amount, "ADJUST", null, n);
            store.log(me.id, "credit.adjust", u.id, amount + ": " + n);
            store.notify(u.id, "A moderator " + (amount > 0 ? "added " : "removed ") + Math.abs(amount) + " credit(s): " + n, "wallet");
            store.save("ledger", "notes", "audit");
            return Json.obj("balance", bank.balance(u.id));
        }
    }

    public Skill addSkill(User me, Map<String, Object> body) {
        mod(me);
        String name = Text.clean(Json.str(body, "name"), 40);
        String category = Text.clean(Json.str(body, "category"), 30);
        if (name == null || category == null) throw Problem.bad("Give the skill a name and a category.");
        synchronized (store) {
            for (Skill s : store.skills.values()) if (s.name.equalsIgnoreCase(name)) throw Problem.conflict(name + " is already in the catalogue.");
            List<String> tags = new ArrayList<>();
            for (String t : (Json.str(body, "tags", "")).split(",")) {
                String c = Text.clean(t, 20);
                if (c != null) tags.add(c.toLowerCase());
            }
            Skill s = new Skill(store.nextId("S", 201), name, category, tags);
            store.skills.put(s.id, s);
            store.log(me.id, "skill.add", s.id, name + " (" + category + ")");
            store.save("skills", "audit");
            return s;
        }
    }
}
