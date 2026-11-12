package com.cecil.sikho.service;

import com.cecil.sikho.ledger.TimeBank;
import com.cecil.sikho.match.Geo;
import com.cecil.sikho.match.Matcher;
import com.cecil.sikho.match.SwapFinder;
import com.cecil.sikho.match.Trust;
import com.cecil.sikho.model.Circle;
import com.cecil.sikho.model.Exchange;
import com.cecil.sikho.model.Exchange.Status;
import com.cecil.sikho.model.LedgerEntry;
import com.cecil.sikho.model.Listing;
import com.cecil.sikho.model.Locality;
import com.cecil.sikho.model.Note;
import com.cecil.sikho.model.Review;
import com.cecil.sikho.model.Skill;
import com.cecil.sikho.model.User;
import com.cecil.sikho.store.DataStore;
import com.cecil.sikho.util.Clock;
import com.cecil.sikho.util.Json;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The read-only pages: home, member profiles, wallet, the locality map and the community numbers. */
public class InsightService {
    private static final long DAY = 86_400_000L;

    private final DataStore store;
    private final TimeBank bank;
    private final Matcher matcher;
    private final SwapFinder swaps;
    private final Views views;

    public InsightService(DataStore store, TimeBank bank, Matcher matcher, SwapFinder swaps, Views views) {
        this.store = store;
        this.bank = bank;
        this.matcher = matcher;
        this.swaps = swaps;
        this.views = views;
    }

    /** Credits waiting in escrow because of this member: open exchanges where they learn + seats in open circles. */
    int heldBy(String userId) {
        int held = 0;
        for (Exchange x : store.exchanges.values()) if (x.isOpen() && userId.equals(x.learnerId)) held += bank.heldFor(x.id);
        for (Circle c : store.circles.values()) if (c.status == Circle.Status.OPEN && c.attendees.contains(userId)) held++;
        return held;
    }

    private int released(String ref) {
        int n = 0;
        for (LedgerEntry e : store.ledger) if ("RELEASE".equals(e.kind) && ref.equals(e.ref)) n += e.amount;
        return n;
    }

    public Map<String, Object> me(User me) {
        synchronized (store) {
            Map<String, Object> m = me.selfView();
            m.put("balance", bank.balance(me.id));
            m.put("locality", store.localityName(me.localityId));
            long unread = store.notes.values().stream().filter(n -> me.id.equals(n.userId) && !n.read).count();
            m.put("unread", unread);
            return m;
        }
    }

    // ------------------------------------------------------------------ home

    public Map<String, Object> home(User me) {
        synchronized (store) {
            long now = Clock.millis();
            Trust trust = new Trust(store);
            List<Object> waiting = new ArrayList<>(), upcoming = new ArrayList<>(), toConfirm = new ArrayList<>(), toReview = new ArrayList<>();
            List<Exchange> mine = new ArrayList<>();
            for (Exchange e : store.exchanges.values()) if (e.involves(me.id)) mine.add(e);
            mine.sort(Comparator.comparingLong(e -> e.at));
            for (Exchange e : mine) {
                if (e.status == Status.REQUESTED && !me.id.equals(e.initiatorId) && e.at > now) waiting.add(views.exchange(e, me, trust, false));
                if (e.status == Status.ACCEPTED && e.at > now) upcoming.add(views.exchange(e, me, trust, false));
                if (e.status == Status.ACCEPTED && e.at <= now && !(me.id.equals(e.teacherId) ? e.teacherDone : e.learnerDone))
                    toConfirm.add(views.exchange(e, me, trust, false));
                if (e.status == Status.COMPLETED && now - e.closedAt <= 30 * DAY
                        && !(me.id.equals(e.learnerId) ? e.learnerReviewed : e.teacherReviewed)) toReview.add(views.exchange(e, me, trust, false));
            }
            List<Map<String, Object>> suggestions = matcher.suggestionsFor(me, r -> views.result(r, me, trust));

            // people nearby who want what I can teach
            List<Object> demand = new ArrayList<>();
            for (Listing o : store.listings.values()) {
                if (!o.active || o.type != Listing.Type.OFFER || !o.userId.equals(me.id)) continue;
                List<Object> who = new ArrayList<>();
                int count = 0;
                for (Listing w : store.listings.values()) {
                    if (!w.active || w.type != Listing.Type.WANT || !w.skillId.equals(o.skillId) || w.userId.equals(me.id)) continue;
                    User u = store.users.get(w.userId);
                    if (!store.canAct(u) || Matcher.blocked(me, u)) continue;
                    if (Geo.km(me.lat, me.lng, u.lat, u.lng) > me.maxKm && o.mode == Listing.Mode.IN_PERSON) continue;
                    count++;
                    if (who.size() < 3) who.add(views.listing(w, me, trust));
                }
                demand.add(Json.obj("offerId", o.id, "skill", store.skillName(o.skillId), "count", count, "wants", who));
            }

            List<Object> circlesSoon = new ArrayList<>();
            List<Circle> cs = new ArrayList<>(store.circles.values());
            cs.sort(Comparator.comparingLong(c -> c.at));
            for (Circle c : cs) {
                if (c.status != Circle.Status.OPEN || c.at <= now || circlesSoon.size() >= 3) continue;
                Locality l = store.localities.get(c.localityId);
                if (l != null && Geo.km(me.lat, me.lng, l.lat, l.lng) <= me.maxKm + 1) circlesSoon.add(views.circle(c, me, trust));
            }

            List<Object> notes = new ArrayList<>();
            List<Note> ns = new ArrayList<>();
            for (Note n : store.notes.values()) if (me.id.equals(n.userId)) ns.add(n);
            for (int i = ns.size() - 1; i >= 0 && notes.size() < 8; i--) notes.add(ns.get(i).toJson());

            Map<String, Object> sw = swaps.find(me.id);
            long myCards = store.listings.values().stream().filter(l -> l.active && l.userId.equals(me.id)).count();
            int hoursMonth = 0;
            for (Exchange e : store.exchanges.values()) if (e.status == Status.COMPLETED && now - e.closedAt <= 30 * DAY) hoursMonth += e.hours;
            return Json.obj("me", me(me), "trust", trust.of(me.id).toJson(), "held", heldBy(me.id),
                    "waiting", waiting, "upcoming", upcoming, "toConfirm", toConfirm, "toReview", toReview,
                    "suggestions", suggestions, "demand", demand, "circles", circlesSoon, "notes", notes,
                    "swaps", sw, "myCards", myCards,
                    "community", Json.obj("members", store.users.values().stream().filter(store::canAct).count(), "hoursMonth", hoursMonth));
        }
    }

    // ------------------------------------------------------------------ member profile

    public Map<String, Object> profile(User viewer, String id) {
        synchronized (store) {
            User u = store.users.get(id);
            if (u == null || (!store.canAct(u) && !viewer.isModerator())) throw Problem.notFound("Member");
            Trust trust = new Trust(store);
            Map<String, Object> m = views.member(u, viewer, trust);
            List<Object> offers = new ArrayList<>(), wants = new ArrayList<>(), reviews = new ArrayList<>();
            for (Listing l : store.listings.values()) {
                if (!l.userId.equals(u.id) || (!l.active && !viewer.id.equals(u.id))) continue;
                (l.type == Listing.Type.OFFER ? offers : wants).add(views.listing(l, viewer, trust));
            }
            List<Review> rs = new ArrayList<>();
            for (Review r : store.reviews.values()) if (u.id.equals(r.toId)) rs.add(r);
            rs.sort(Comparator.comparingLong((Review r) -> r.at).reversed());
            for (Review r : rs) if (reviews.size() < 12) reviews.add(views.review(r));
            int taught = 0, learned = 0, hosted = 0;
            for (Exchange e : store.exchanges.values()) {
                if (e.status != Status.COMPLETED) continue;
                if (u.id.equals(e.teacherId)) taught += e.hours;
                if (u.id.equals(e.learnerId)) learned += e.hours;
            }
            for (Circle c : store.circles.values()) if (c.status == Circle.Status.DONE && u.id.equals(c.hostId)) hosted++;
            Trust.Score t = trust.of(u.id);
            List<Object> badges = new ArrayList<>();
            if (taught >= 20) badges.add(Json.obj("name", "Neighbourhood hero", "why", taught + " hours taught"));
            else if (taught >= 5) badges.add(Json.obj("name", "Helping hand", "why", taught + " hours taught"));
            if (learned >= 10) badges.add(Json.obj("name", "Lifelong learner", "why", learned + " hours learned"));
            if (hosted >= 1) badges.add(Json.obj("name", "Circle host", "why", hosted + " circle" + (hosted == 1 ? "" : "s") + " hosted"));
            if (t.reviews() >= 5 && t.rating() >= 4.5) badges.add(Json.obj("name", "Trusted teacher", "why", String.format("%.1f★ from %d reviews", t.rating(), t.reviews())));
            if (Clock.millis() - u.createdAt < 30 * DAY) badges.add(Json.obj("name", "New neighbour", "why", "joined this month"));
            m.put("offers", offers);
            m.put("wants", wants);
            m.put("reviews", reviews);
            m.put("stats", Json.obj("taught", taught, "learned", learned, "hosted", hosted));
            m.put("badges", badges);
            m.put("availability", u.availability);
            m.put("isMe", viewer.id.equals(u.id));
            m.put("maxKm", u.maxKm);
            if (viewer.isModerator()) {
                m.put("mod", Json.obj("username", u.username, "warnings", u.warnings, "suspended", u.suspended,
                        "suspendReason", u.suspendReason, "balance", bank.balance(u.id)));
            }
            return m;
        }
    }

    // ------------------------------------------------------------------ wallet

    public Map<String, Object> wallet(User me) {
        synchronized (store) {
            int earned = 0, spent = 0, gifts = 0;
            long dayAgo = Clock.millis() - DAY;
            for (LedgerEntry e : store.ledger) {
                if ("RELEASE".equals(e.kind) && me.id.equals(e.to)) earned += e.amount;
                if ("GIFT".equals(e.kind) && me.id.equals(e.from) && e.at > dayAgo) gifts += e.amount;
            }
            for (Exchange x : store.exchanges.values()) if (me.id.equals(x.learnerId)) spent += released(x.id);
            for (Circle c : store.circles.values()) if (c.status == Circle.Status.DONE && c.attendees.contains(me.id)) spent++;
            return Json.obj("balance", bank.balance(me.id), "held", heldBy(me.id), "earned", earned, "spent", spent,
                    "giftsToday", gifts, "giftLimit", 5, "statement", bank.statement(me.id, 150), "welcome", TimeBank.WELCOME);
        }
    }

    // ------------------------------------------------------------------ map

    public Map<String, Object> map(User me, String category) {
        synchronized (store) {
            List<Object> locs = new ArrayList<>();
            for (Locality l : store.localities.values()) {
                int members = 0, offers = 0, wants = 0;
                for (User u : store.users.values()) if (store.canAct(u) && l.id.equals(u.localityId)) members++;
                for (Listing x : store.listings.values()) {
                    if (!x.active) continue;
                    User u = store.users.get(x.userId);
                    if (!store.canAct(u) || !l.id.equals(u.localityId)) continue;
                    Skill s = store.skills.get(x.skillId);
                    if (category != null && !category.isBlank() && (s == null || !category.equalsIgnoreCase(s.category))) continue;
                    if (x.type == Listing.Type.OFFER) offers++; else wants++;
                }
                locs.add(Json.obj("id", l.id, "name", l.name, "lat", l.lat, "lng", l.lng, "members", members, "offers", offers,
                        "wants", wants, "km", Geo.shown(Geo.km(me.lat, me.lng, l.lat, l.lng)), "mine", l.id.equals(me.localityId)));
            }
            // my own home point is sent to me only (never to others), so the map can draw my travel circle
            return Json.obj("localities", locs, "me", Json.obj("lat", me.lat, "lng", me.lng, "maxKm", me.maxKm));
        }
    }

    // ------------------------------------------------------------------ community numbers

    public Map<String, Object> community(User viewer) {
        synchronized (store) {
            long now = Clock.millis();
            Trust trust = new Trust(store);
            // hours exchanged per week, last 12 weeks (weeks start on Monday)
            LocalDate monday = Clock.today().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            long[] weekStart = new long[12];
            for (int i = 0; i < 12; i++) weekStart[i] = monday.minusWeeks(11 - i).atStartOfDay(Clock.ZONE).toInstant().toEpochMilli();
            int[] weekHours = new int[12], weekSessions = new int[12];
            Map<String, Integer> byCategory = new LinkedHashMap<>();
            Map<String, int[]> byLocality = new LinkedHashMap<>();   // members, offers, wants, hours
            for (Locality l : store.localities.values()) byLocality.put(l.id, new int[4]);
            Map<String, Integer> taught = new HashMap<>();
            int totalHours = 0, completed = 0, cancelled = 0, late = 0;
            for (Exchange e : store.exchanges.values()) {
                if (e.status == Status.COMPLETED) {
                    completed++;
                    totalHours += e.hours;
                    for (int i = 11; i >= 0; i--) if (e.closedAt >= weekStart[i]) { weekHours[i] += e.hours; weekSessions[i]++; break; }
                    Skill s = store.skills.get(e.skillId);
                    if (s != null) byCategory.merge(s.category, e.hours, Integer::sum);
                    taught.merge(e.teacherId, e.hours, Integer::sum);
                    User t = store.users.get(e.teacherId);
                    if (t != null && byLocality.containsKey(t.localityId)) byLocality.get(t.localityId)[3] += e.hours;
                }
                if (e.status == Status.CANCELLED || e.status == Status.DECLINED || e.status == Status.EXPIRED) cancelled++;
                if (e.lateCancel) late++;
            }
            List<Object> weeks = new ArrayList<>();
            for (int i = 0; i < 12; i++) weeks.add(Json.obj("week", monday.minusWeeks(11 - i).toString(), "hours", weekHours[i], "sessions", weekSessions[i]));

            // demand vs supply per skill
            Map<String, int[]> perSkill = new LinkedHashMap<>();
            for (Listing l : store.listings.values()) {
                if (!l.active || !store.canAct(store.users.get(l.userId))) continue;
                int[] c = perSkill.computeIfAbsent(l.skillId, k -> new int[2]);
                if (l.type == Listing.Type.OFFER) c[0]++; else c[1]++;
                User u = store.users.get(l.userId);
                if (byLocality.containsKey(u.localityId)) byLocality.get(u.localityId)[l.type == Listing.Type.OFFER ? 1 : 2]++;
            }
            for (User u : store.users.values()) if (store.canAct(u) && byLocality.containsKey(u.localityId)) byLocality.get(u.localityId)[0]++;
            List<Map<String, Object>> gaps = new ArrayList<>();
            for (Map.Entry<String, int[]> e : perSkill.entrySet()) {
                Skill s = store.skills.get(e.getKey());
                gaps.add(Json.obj("skill", s == null ? "?" : s.name, "category", s == null ? "" : s.category,
                        "offers", e.getValue()[0], "wants", e.getValue()[1], "gap", e.getValue()[1] - e.getValue()[0]));
            }
            gaps.sort(Comparator.comparingInt((Map<String, Object> g) -> ((Number) g.get("gap")).intValue()).reversed()
                    .thenComparing(g -> String.valueOf(g.get("skill"))));
            List<Map<String, Object>> needed = new ArrayList<>(gaps.subList(0, Math.min(10, gaps.size())));
            List<Map<String, Object>> plenty = new ArrayList<>();
            for (int i = gaps.size() - 1; i >= 0 && plenty.size() < 5; i--) if (((Number) gaps.get(i).get("gap")).intValue() < 0) plenty.add(gaps.get(i));

            List<Object> localities = new ArrayList<>();
            for (Map.Entry<String, int[]> e : byLocality.entrySet()) {
                int[] v = e.getValue();
                localities.add(Json.obj("name", store.localityName(e.getKey()), "members", v[0], "offers", v[1], "wants", v[2], "hours", v[3]));
            }
            List<Map.Entry<String, Integer>> top = new ArrayList<>(taught.entrySet());
            top.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
            List<Object> heroes = new ArrayList<>();
            for (Map.Entry<String, Integer> e : top) {
                User u = store.users.get(e.getKey());
                if (u == null || !store.canAct(u) || heroes.size() >= 5) continue;
                heroes.add(Json.obj("id", u.id, "name", u.name, "initials", u.initials(), "locality", store.localityName(u.localityId),
                        "hours", e.getValue(), "rating", Math.round(trust.of(u.id).rating() * 10) / 10.0));
            }
            Map<String, Object> ledger = bank.summary();
            int issued = ((Number) ledger.get("issued")).intValue();
            int last30 = 0;
            for (Exchange e : store.exchanges.values()) if (e.status == Status.COMPLETED && now - e.closedAt <= 30 * DAY) last30 += e.hours;
            Map<String, Object> sw = swaps.find(null);
            long circlesDone = store.circles.values().stream().filter(c -> c.status == Circle.Status.DONE).count();
            int circlePeople = 0;
            for (Circle c : store.circles.values()) if (c.status == Circle.Status.DONE) circlePeople += c.attendees.size();
            return Json.obj(
                    "totals", Json.obj("members", store.users.values().stream().filter(store::canAct).count(),
                            "offers", perSkill.values().stream().mapToInt(v -> v[0]).sum(),
                            "wants", perSkill.values().stream().mapToInt(v -> v[1]).sum(),
                            "hours", totalHours, "sessions", completed, "circles", circlesDone, "circlePeople", circlePeople,
                            "rating", Math.round(trust.communityMean() * 100) / 100.0,
                            "completionRate", completed + cancelled == 0 ? 0 : Math.round(100.0 * completed / (completed + cancelled)),
                            "lateCancels", late),
                    "weeks", weeks, "byCategory", byCategory, "needed", needed, "plenty", plenty, "localities", localities,
                    "heroes", heroes,
                    "credits", Json.obj("issued", issued, "inMembers", ledger.get("inMembers"), "inEscrow", ledger.get("inEscrow"),
                            "membersAtZero", ledger.get("membersAtZero"),
                            "velocity", issued == 0 ? 0 : Math.round(100.0 * last30 / issued) / 100.0, "hours30", last30),
                    "swaps", Json.obj("pairs", sw.get("pairCount"), "circles", sw.get("circleCount"), "arrows", sw.get("arrows")));
        }
    }
}
