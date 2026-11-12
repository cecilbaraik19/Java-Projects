package com.cecil.sikho.service;

import com.cecil.sikho.match.Geo;
import com.cecil.sikho.match.Matcher;
import com.cecil.sikho.match.Trust;
import com.cecil.sikho.model.Circle;
import com.cecil.sikho.model.Exchange;
import com.cecil.sikho.model.Listing;
import com.cecil.sikho.model.Message;
import com.cecil.sikho.model.Review;
import com.cecil.sikho.model.Skill;
import com.cecil.sikho.model.User;
import com.cecil.sikho.store.DataStore;
import com.cecil.sikho.util.Clock;
import com.cecil.sikho.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns records into the safe JSON the website gets. This is the one place that decides what other people may see:
 *   - never the password hash or the exact home position (only the locality and a distance rounded up to 0.5 km);
 *   - the phone number only for a partner whose exchange has been accepted.
 */
public class Views {
    private final DataStore store;

    public Views(DataStore store) { this.store = store; }

    /** A member as other people see them. viewer may be null. */
    public Map<String, Object> member(User u, User viewer, Trust trust) {
        Trust.Score t = trust.of(u.id);
        Map<String, Object> m = Json.obj("id", u.id, "name", u.name, "firstName", u.firstName(), "initials", u.initials(),
                "locality", store.localityName(u.localityId), "localityId", u.localityId,
                "joined", u.createdAt, "languages", u.languages, "bio", u.bio, "role", u.role,
                "trust", t.toJson());
        if (viewer != null && !viewer.id.equals(u.id)) {
            m.put("km", Geo.shown(Geo.km(viewer.lat, viewer.lng, u.lat, u.lng)));
            m.put("blockedByMe", viewer.blocked.contains(u.id));
        }
        return m;
    }

    public Map<String, Object> skill(Skill s) {
        return s == null ? null : Json.obj("id", s.id, "name", s.name, "category", s.category);
    }

    /** A card on the board, with its owner. */
    public Map<String, Object> listing(Listing l, User viewer, Trust trust) {
        User owner = store.users.get(l.userId);
        Map<String, Object> m = Json.obj("id", l.id, "type", l.type, "title", l.title, "details", l.details,
                "level", l.level, "mode", l.mode, "place", l.place, "active", l.active, "createdAt", l.createdAt,
                "skill", skill(store.skills.get(l.skillId)), "mine", viewer != null && viewer.id.equals(l.userId));
        if (owner != null) m.put("owner", member(owner, viewer, trust));
        return m;
    }

    public Map<String, Object> result(Matcher.Result r, User viewer, Trust trust) {
        Map<String, Object> m = listing(r.listing(), viewer, trust);
        m.put("score", r.score());
        m.put("parts", r.parts());
        m.put("sharedSlots", r.sharedSlots());
        return m;
    }

    /** An exchange as one of its two people (or a moderator) sees it. */
    public Map<String, Object> exchange(Exchange e, User viewer, Trust trust, boolean full) {
        User teacher = store.users.get(e.teacherId), learner = store.users.get(e.learnerId);
        boolean iTeach = viewer.id.equals(e.teacherId);
        Map<String, Object> m = Json.obj("id", e.id, "status", e.status, "hours", e.hours, "at", e.at, "place", e.place,
                "skill", skill(store.skills.get(e.skillId)), "listingId", e.listingId,
                "createdAt", e.createdAt, "updatedAt", e.updatedAt, "acceptedAt", e.acceptedAt, "closedAt", e.closedAt,
                "teacher", teacher == null ? null : member(teacher, viewer, trust),
                "learner", learner == null ? null : member(learner, viewer, trust),
                "myRole", iTeach ? "TEACHER" : viewer.id.equals(e.learnerId) ? "LEARNER" : "MODERATOR",
                "initiatorId", e.initiatorId, "teacherDone", e.teacherDone, "learnerDone", e.learnerDone,
                "cancelledBy", e.cancelledBy, "reason", e.reason, "lateCancel", e.lateCancel, "disputeBy", e.disputeBy,
                "disputeResult", e.disputeResult, "learnerReviewed", e.learnerReviewed, "teacherReviewed", e.teacherReviewed,
                "started", Clock.millis() >= e.at, "unread", 0);
        if (full) {
            boolean contactOk = e.status == Exchange.Status.ACCEPTED || e.status == Exchange.Status.COMPLETED
                    || e.status == Exchange.Status.DISPUTED;
            if (contactOk && e.involves(viewer.id)) {
                User partner = store.users.get(e.partnerOf(viewer.id));
                m.put("partnerPhone", partner == null ? null : partner.phone);
            }
            List<Object> msgs = new ArrayList<>();
            for (Message x : e.messages) {
                msgs.add(Json.obj("from", x.fromId, "fromName", x.fromId == null ? "Sikho-Sikhao" : store.userName(x.fromId),
                        "mine", viewer.id.equals(x.fromId), "text", x.text, "at", x.at, "flag", x.flag, "advice", Safety.advice(x.flag)));
            }
            m.put("messages", msgs);
            List<Object> revs = new ArrayList<>();
            for (Review r : store.reviews.values()) if (e.id.equals(r.exchangeId)) revs.add(review(r));
            m.put("reviews", revs);
            m.put("actions", ExchangeService.actionsFor(e, viewer, Clock.millis()));
        }
        return m;
    }

    public Map<String, Object> review(Review r) {
        return Json.obj("id", r.id, "fromId", r.fromId, "from", store.userName(r.fromId), "toId", r.toId,
                "to", store.userName(r.toId), "aboutTeacher", r.aboutTeacher, "stars", r.stars, "text", r.text,
                "tags", r.tags, "at", r.at, "skill", exSkill(r.exchangeId));
    }

    private String exSkill(String exchangeId) {
        Exchange e = store.exchanges.get(exchangeId);
        return e == null ? null : store.skillName(e.skillId);
    }

    public Map<String, Object> circle(Circle c, User viewer, Trust trust) {
        User host = store.users.get(c.hostId);
        List<Object> people = new ArrayList<>();
        for (String a : c.attendees) {
            User u = store.users.get(a);
            if (u != null) people.add(Json.obj("id", u.id, "name", u.name, "initials", u.initials()));
        }
        Map<String, Object> m = Json.obj("id", c.id, "title", c.title, "details", c.details, "place", c.place,
                "locality", store.localityName(c.localityId), "localityId", c.localityId, "at", c.at, "hours", c.hours,
                "seats", c.seats, "taken", c.attendees.size(), "attendees", people, "status", c.status,
                "skill", skill(store.skills.get(c.skillId)), "host", host == null ? null : member(host, viewer, trust),
                "iHost", viewer.id.equals(c.hostId), "iJoined", c.attendees.contains(viewer.id),
                "started", Clock.millis() >= c.at);
        return m;
    }
}
