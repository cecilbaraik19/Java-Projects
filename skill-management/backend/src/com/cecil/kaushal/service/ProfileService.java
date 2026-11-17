package com.cecil.kaushal.service;

import com.cecil.kaushal.core.Levels;
import com.cecil.kaushal.model.Cert;
import com.cecil.kaushal.model.Claim;
import com.cecil.kaushal.model.ClaimStatus;
import com.cecil.kaushal.model.Endorsement;
import com.cecil.kaushal.model.Goal;
import com.cecil.kaushal.model.Skill;
import com.cecil.kaushal.model.User;
import com.cecil.kaushal.store.DataStore;
import com.cecil.kaushal.util.Clock;
import com.cecil.kaushal.util.Json;
import com.cecil.kaushal.util.Text;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * A person's skill profile: claim a level -> someone checks it (verify / adjust / send back) -> it counts.
 * Also "I used it this month" (stops decay), endorsements by colleagues, and certificates with expiry reminders.
 */
public class ProfileService {
    public static final int MAX_CLAIMS = 60;
    public static final int ENDORSE_PER_WEEK = 15;
    public static final long WEEK = 7L * 24 * 3_600_000;

    private final DataStore store;

    public ProfileService(DataStore store) { this.store = store; }

    // ------------------------------------------------------------------ claims

    /** Add or change my claim for a skill. A new or higher claim must be checked again by someone else. */
    public Claim claim(User u, Map<String, Object> b) {
        synchronized (store) {
            Skill s = store.skills.get(Json.str(b, "skillId"));
            if (s == null || !s.active) throw Problem.bad("Pick a skill from the catalogue.");
            int level = (int) Json.num(b, "level", 0);
            if (level < 1 || level > 5) throw Problem.bad("Pick a level from 1 (Aware) to 5 (Expert).");
            String lastUsed = month(Json.str(b, "lastUsed"));
            String evidence = Text.block(Json.str(b, "evidence"), 400);
            if (level >= 4 && (evidence == null || evidence.length() < 15))
                throw Problem.bad("For level " + level + " (" + Levels.name(level) + ") write a short example of your work (15+ characters).");
            Claim c = store.claim(u.id, s.id);
            long now = Clock.millis();
            boolean recheck;
            if (c == null) {
                if (store.claimsOf(u.id).size() >= MAX_CLAIMS) throw Problem.bad("At most " + MAX_CLAIMS + " skills per person.");
                c = new Claim();
                c.id = store.nextId("C", 1001);
                c.userId = u.id;
                c.skillId = s.id;
                store.claims.put(c.id, c);
                recheck = true;
            } else {
                recheck = level != c.verified || c.status != ClaimStatus.VERIFIED;
            }
            c.claimed = level;
            c.lastUsed = lastUsed;
            c.evidence = evidence;
            if (recheck) {
                c.status = ClaimStatus.CLAIMED;
                c.claimedAt = now;
                c.event(now, u.id, "claimed", level, null);
                if (u.managerId != null)
                    store.notify(u.managerId, u.name + " claims " + s.name + " at level " + level + " (" + Levels.name(level) + ") - please check.", "#/verify");
            } else {
                c.event(now, u.id, "updated", level, null);
            }
            store.log(u.id, "claim", c.id, s.name + " " + level);
            store.save("claims", "notes", "audit");
            return c;
        }
    }

    /** Used by the CSV import: same as claim(), without the checks for the person's own input. */
    Claim claimAs(User u, Skill s, int level, String lastUsed, String evidence) {
        return claim(u, Json.obj("skillId", s.id, "level", level, "lastUsed", lastUsed,
                "evidence", level >= 4 ? evidence + " (level " + level + ")" : evidence));
    }

    /** "I used this skill this month" - resets the decay clock. */
    public Claim used(User u, String claimId) {
        synchronized (store) {
            Claim c = own(u, claimId);
            c.lastUsed = YearMonth.from(Clock.today()).toString();
            c.event(Clock.millis(), u.id, "used", c.verified, null);
            store.save("claims");
            return c;
        }
    }

    public void remove(User u, String claimId) {
        synchronized (store) {
            Claim c = store.claims.get(claimId);
            if (c == null) throw Problem.notFound("Skill");
            if (!c.userId.equals(u.id) && !u.admin()) throw Problem.forbidden("You can only remove your own skills.");
            store.claims.remove(claimId);
            store.endorsements.values().removeIf(e -> e.toId.equals(c.userId) && e.skillId.equals(c.skillId));
            store.log(u.id, "claim.remove", claimId, store.skills.get(c.skillId).name);
            store.save("claims", "endorsements", "audit");
        }
    }

    /** Confirm a claim at a level (the same as claimed, or a different one with a reason). */
    public Claim verify(User by, String claimId, int level, String note) {
        synchronized (store) {
            Claim c = store.claims.get(claimId);
            if (c == null) throw Problem.notFound("Skill claim");
            Access.Right r = Access.verify(store, by, c);
            if (r == null) throw Problem.forbidden(by.id.equals(c.userId)
                    ? "You cannot verify your own skill - someone else has to (four eyes)."
                    : "Only the person's manager, HR or an assessor (level 4+ in this skill) can verify it.");
            if (c.status != ClaimStatus.CLAIMED) throw Problem.conflict("This claim is not waiting for a check.");
            if (level < 1 || level > 5) throw Problem.bad("Pick a level from 1 to 5.");
            if (level > r.maxLevel()) throw Problem.forbidden("As an assessor you can confirm up to level " + r.maxLevel() + " (your own level).");
            note = Text.block(note, 300);
            if (level != c.claimed && (note == null || note.length() < 5))
                throw Problem.bad("You chose a different level than claimed - write a short reason for " + store.users.get(c.userId).name + ".");
            long now = Clock.millis();
            c.verified = level;
            c.status = ClaimStatus.VERIFIED;
            c.verifiedAt = now;
            c.verifiedBy = by.id;
            c.note = note;
            c.event(now, by.id, level == c.claimed ? "verified" : "adjusted", level, note);
            Skill s = store.skills.get(c.skillId);
            store.notify(c.userId, by.name + (level == c.claimed ? " verified your " : " checked your ") + s.name + " at level " + level
                    + (level == c.claimed ? "." : " (you claimed " + c.claimed + ")."), "#/me");
            // a learning goal for this skill is reached
            for (Goal g : store.goals.values())
                if (g.userId.equals(c.userId) && g.skillId.equals(c.skillId) && "OPEN".equals(g.status) && level >= g.target) {
                    g.status = "DONE";
                    g.doneAt = now;
                }
            store.log(by.id, "claim.verify", c.id, s.name + " " + c.claimed + "->" + level);
            store.save("claims", "goals", "notes", "audit");
            return c;
        }
    }

    /** Send a claim back with a question (e.g. "add an example"). */
    public Claim sendBack(User by, String claimId, String note) {
        synchronized (store) {
            Claim c = store.claims.get(claimId);
            if (c == null) throw Problem.notFound("Skill claim");
            if (Access.verify(store, by, c) == null) throw Problem.forbidden("You cannot check this claim.");
            if (c.status != ClaimStatus.CLAIMED) throw Problem.conflict("This claim is not waiting for a check.");
            note = Text.block(note, 300);
            if (note == null || note.length() < 5) throw Problem.bad("Say what is missing, so they can fix it.");
            c.status = ClaimStatus.RETURNED;
            c.note = note;
            c.event(Clock.millis(), by.id, "returned", c.claimed, note);
            store.notify(c.userId, by.name + " has a question about your " + store.skills.get(c.skillId).name + ": " + note, "#/me");
            store.log(by.id, "claim.return", c.id, note);
            store.save("claims", "notes", "audit");
            return c;
        }
    }

    /** Claims this person may check, oldest first. */
    public List<Map<String, Object>> queue(User by) {
        synchronized (store) {
            List<Claim> list = new ArrayList<>();
            for (Claim c : store.claims.values()) if (c.status == ClaimStatus.CLAIMED && Access.verify(store, by, c) != null) list.add(c);
            list.sort(Comparator.comparingLong(c -> c.claimedAt));
            List<Map<String, Object>> out = new ArrayList<>();
            for (Claim c : list) {
                Access.Right r = Access.verify(store, by, c);
                out.add(Json.obj("claim", c, "why", r.why(), "maxLevel", r.maxLevel()));
            }
            return out;
        }
    }

    private Claim own(User u, String claimId) {
        Claim c = store.claims.get(claimId);
        if (c == null) throw Problem.notFound("Skill");
        if (!c.userId.equals(u.id)) throw Problem.forbidden("This is not your skill.");
        return c;
    }

    static String month(String s) {
        if (Text.blank(s)) return YearMonth.from(Clock.today()).toString();
        try {
            YearMonth m = YearMonth.parse(s.trim().length() > 7 ? s.trim().substring(0, 7) : s.trim());
            if (m.isAfter(YearMonth.from(Clock.today()))) throw Problem.bad("'Last used' cannot be in the future.");
            if (m.getYear() < 1980) throw Problem.bad("'Last used' looks too old.");
            return m.toString();
        } catch (DateTimeParseException e) {
            throw Problem.bad("'Last used' is a month like 2026-11.");
        }
    }

    // ------------------------------------------------------------------ endorsements

    public Endorsement endorse(User from, String toId, String skillId, String comment) {
        synchronized (store) {
            User to = store.users.get(toId);
            if (to == null || !to.active) throw Problem.notFound("Person");
            if (to.id.equals(from.id)) throw Problem.bad("You cannot endorse yourself.");
            Claim c = store.claim(to.id, skillId);
            if (c == null || c.status == ClaimStatus.RETURNED) throw Problem.bad(to.name + " has not listed this skill.");
            for (Endorsement e : store.endorsements.values())
                if (e.fromId.equals(from.id) && e.toId.equals(toId) && e.skillId.equals(skillId))
                    throw Problem.conflict("You already endorsed " + to.name + " for this skill.");
            long now = Clock.millis();
            int week = 0;
            for (Endorsement e : store.endorsements.values()) if (e.fromId.equals(from.id) && e.at > now - WEEK) week++;
            if (week >= ENDORSE_PER_WEEK) throw new Problem(429, "At most " + ENDORSE_PER_WEEK + " endorsements a week - make each one count.");
            Endorsement e = new Endorsement();
            e.id = store.nextId("E", 1);
            e.fromId = from.id;
            e.toId = toId;
            e.skillId = skillId;
            e.comment = Text.clean(comment, 160);
            e.at = now;
            store.endorsements.put(e.id, e);
            store.notify(toId, from.name + " endorsed your " + store.skills.get(skillId).name + (Text.blank(e.comment) ? "." : ": \"" + e.comment + "\""), "#/me");
            store.log(from.id, "endorse", toId, store.skills.get(skillId).name);
            store.save("endorsements", "notes", "audit");
            return e;
        }
    }

    public void unendorse(User from, String id) {
        synchronized (store) {
            Endorsement e = store.endorsements.get(id);
            if (e == null) throw Problem.notFound("Endorsement");
            if (!e.fromId.equals(from.id) && !from.admin()) throw Problem.forbidden("Not your endorsement.");
            store.endorsements.remove(id);
            store.save("endorsements");
        }
    }

    /**
     * Endorsement weight: an endorsement from someone who is good at the skill counts more.
     * weight = 1 + (endorser's effective level in that skill) / 2   -> 1.0 (does not have it) ... 3.5 (expert)
     */
    public static double weight(DataStore s, Endorsement e, LocalDate today) {
        Claim c = s.claim(e.fromId, e.skillId);
        int lv = c == null ? 0 : s.effective(c, today);
        return 1 + lv / 2.0;
    }

    // ------------------------------------------------------------------ certificates

    public Cert saveCert(User by, String id, Map<String, Object> b) {
        synchronized (store) {
            Cert c = id == null ? new Cert() : store.certs.get(id);
            if (c == null) throw Problem.notFound("Certificate");
            String owner = id == null ? Json.str(b, "userId", by.id) : c.userId;
            if (!owner.equals(by.id) && !by.admin()) throw Problem.forbidden("You can only add your own certificates.");
            if (!store.users.containsKey(owner)) throw Problem.notFound("Person");
            String name = Text.clean(Json.str(b, "name", c.name), 80);
            if (Text.blank(name)) throw Problem.bad("Write the certificate's name.");
            String skillId = Json.str(b, "skillId", c.skillId);
            if (Text.blank(skillId)) skillId = null;
            if (skillId != null && !store.skills.containsKey(skillId)) throw Problem.bad("Unknown skill.");
            LocalDate issued = date(Json.str(b, "issued", c.issued), "Issued");
            if (issued == null) throw Problem.bad("When was it issued?");
            if (issued.isAfter(Clock.today())) throw Problem.bad("'Issued' cannot be in the future.");
            String exRaw = b.containsKey("expires") ? Json.str(b, "expires") : c.expires;
            LocalDate expires = date(exRaw, "Expires");
            if (expires != null && !expires.isAfter(issued)) throw Problem.bad("'Expires' must be after 'Issued'.");
            if (id == null) {
                c.id = store.nextId("CERT", 1);
                c.userId = owner;
                c.addedAt = Clock.millis();
                store.certs.put(c.id, c);
            }
            if (expires == null || !String.valueOf(expires).equals(c.expires)) c.reminded.clear();   // renewed -> remind again later
            c.name = name;
            c.issuer = Text.clean(Json.str(b, "issuer", c.issuer), 60);
            c.skillId = skillId;
            c.issued = issued.toString();
            c.expires = expires == null ? null : expires.toString();
            store.log(by.id, id == null ? "cert.add" : "cert.edit", c.id, name);
            store.save("certs", "audit");
            return c;
        }
    }

    public void deleteCert(User by, String id) {
        synchronized (store) {
            Cert c = store.certs.get(id);
            if (c == null) throw Problem.notFound("Certificate");
            if (!c.userId.equals(by.id) && !by.admin()) throw Problem.forbidden("Not your certificate.");
            store.certs.remove(id);
            store.log(by.id, "cert.delete", id, c.name);
            store.save("certs", "audit");
        }
    }

    /** Background job: reminders 60 and 30 days before expiry, and on the day it expires (each once). */
    public int certReminders() {
        synchronized (store) {
            LocalDate today = Clock.today();
            int sent = 0;
            for (Cert c : store.certs.values()) {
                if (c.expires == null) continue;
                long left = c.daysLeft(today);
                String key = left < 0 ? "0" : left <= 30 ? "30" : left <= 60 ? "60" : null;
                if (key == null || c.reminded.contains(key)) continue;
                c.reminded.add(key);
                String text = left < 0 ? "Your certificate " + c.name + " has expired. Renew it or it stops counting."
                        : "Your certificate " + c.name + " expires in " + left + " day" + (left == 1 ? "" : "s") + " (" + c.expires + ").";
                store.notify(c.userId, text, "#/me");
                User u = store.users.get(c.userId);
                if (left < 0 && u != null && u.managerId != null)
                    store.notify(u.managerId, u.name + "'s certificate " + c.name + " has expired.", "#/people/" + u.id);
                sent++;
            }
            if (sent > 0) store.save("certs", "notes");
            return sent;
        }
    }

    static LocalDate date(String s, String what) {
        if (Text.blank(s)) return null;
        try { return LocalDate.parse(s.trim()); } catch (DateTimeParseException e) { throw Problem.bad(what + " is a date like 2026-11-17."); }
    }
}
