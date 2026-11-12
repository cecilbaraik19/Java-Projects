package com.cecil.sikho.match;

import com.cecil.sikho.model.Listing;
import com.cecil.sikho.model.Skill;
import com.cecil.sikho.model.User;
import com.cecil.sikho.store.DataStore;
import com.cecil.sikho.util.Json;
import com.cecil.sikho.util.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the best neighbours to learn from (or to teach). Every candidate card gets a match score from 0 to 100:
 *
 *   score = 40% skill fit + 25% nearness + 15% shared free time + 15% trust + 5% level fit
 *
 *   skill fit    1.0 same skill · 0.4 same category · otherwise the TF-IDF cosine of the words (search box)
 *   nearness     e^(−km / 3): 1.0 next door, 0.72 at 1 km, 0.37 at 3 km, 0.19 at 5 km. Online cards get 0.7.
 *   free time    shared slots ÷ the smaller of the two sets of slots (MON-EVE, SAT-MORN ...)
 *   trust        60% rating (Bayesian average, scaled 1..5 → 0..1) + 40% reliability
 *   level fit    a teacher above the learner's level fits best
 *
 * In-person cards farther away than the searcher's travel limit are left out. Blocked members never appear.
 * Every score comes with its parts, so the website can explain why a card was suggested.
 */
public class Matcher {
    public static final double W_SKILL = 0.40, W_NEAR = 0.25, W_TIME = 0.15, W_TRUST = 0.15, W_LEVEL = 0.05;
    public static final double DIST_SCALE_KM = 3.0;
    public static final double ONLINE_NEAR = 0.7;

    private final DataStore store;

    public Matcher(DataStore store) { this.store = store; }

    /** What the searcher is looking for. Any part may be null. */
    public record Want(String skillId, String query, Listing.Level learnerLevel) { }

    public record Result(Listing listing, double score, Map<String, Object> parts, double km, List<String> sharedSlots) { }

    /** The ranked cards plus any spelling corrections made to the search words ("gitar" → "guitar"). */
    public record Found(List<Result> results, Map<String, String> corrected) { }

    /** The text of a card used for search: its title, details, the skill name, category and tags. */
    public String textOf(Listing l) {
        Skill s = store.skills.get(l.skillId);
        return (l.title == null ? "" : l.title + " " + l.title) + " " + (l.details == null ? "" : l.details) + " "
                + (s == null ? "" : s.name + " " + s.name + " " + s.category + " " + String.join(" ", s.tags));
    }

    public double km(User a, User b) { return Geo.km(a.lat, a.lng, b.lat, b.lng); }

    /** True when the two members have blocked each other (either way). */
    public static boolean blocked(User a, User b) { return a.blocked.contains(b.id) || b.blocked.contains(a.id); }

    /**
     * Ranks cards of one type (OFFER = people who teach, WANT = people who want to learn) for this member.
     * maxKm 0 = use the member's own travel limit. mode null = any.
     */
    public Found search(User me, Listing.Type type, Want want, String category, double maxKm, Listing.Mode mode, int limit) {
        synchronized (store) {
            Trust trust = new Trust(store);
            Map<String, Double> text = Map.of();
            Map<String, String> corrected = Map.of();
            List<Listing> pool = new ArrayList<>();
            for (Listing l : store.listings.values()) {
                if (!l.active || l.type != type || l.userId.equals(me.id)) continue;
                User owner = store.users.get(l.userId);
                if (!store.canAct(owner) || blocked(me, owner)) continue;
                Skill s = store.skills.get(l.skillId);
                if (category != null && !category.isBlank() && (s == null || !category.equalsIgnoreCase(s.category))) continue;
                if (mode == Listing.Mode.ONLINE && l.mode == Listing.Mode.IN_PERSON) continue;
                if (mode == Listing.Mode.IN_PERSON && l.mode == Listing.Mode.ONLINE) continue;
                pool.add(l);
            }
            if (want != null && want.query() != null && !Text.words(want.query()).isEmpty()) {
                Map<String, String> docs = new LinkedHashMap<>();
                for (Listing l : pool) docs.put(l.id, textOf(l));
                SearchIndex idx = new SearchIndex(docs);
                SearchIndex.Query q = idx.prepare(want.query());
                text = idx.scores(q);
                corrected = q.corrected();
            }
            double limitKm = maxKm > 0 ? maxKm : me.maxKm;
            List<Result> out = new ArrayList<>();
            for (Listing l : pool) {
                User owner = store.users.get(l.userId);
                double km = km(me, owner);
                if (l.mode == Listing.Mode.IN_PERSON && km > limitKm) continue;
                if (want != null && want.query() != null && !Text.words(want.query()).isEmpty() && !text.containsKey(l.id)
                        && (want.skillId() == null || !want.skillId().equals(l.skillId))) continue;
                out.add(score(me, owner, l, want, text.getOrDefault(l.id, 0.0), km, trust));
            }
            out.sort(Comparator.comparingDouble(Result::score).reversed().thenComparing(r -> r.listing().id));
            return new Found(out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out, corrected);
        }
    }

    public Result score(User me, User owner, Listing l, Want want, double textCos, double km, Trust trust) {
        // 1. skill fit
        double skill = 0;
        String skillWhy = "matches your search words";
        if (want != null && want.skillId() != null) {
            Skill a = store.skills.get(want.skillId()), b = store.skills.get(l.skillId);
            if (want.skillId().equals(l.skillId)) { skill = 1.0; skillWhy = "the exact skill"; }
            else if (a != null && b != null && a.category.equals(b.category)) { skill = 0.4; skillWhy = "same category (" + a.category + ")"; }
        }
        double fromText = Math.min(1.0, textCos * 1.6);
        if (fromText > skill) { skill = fromText; skillWhy = "matches your search words"; }
        if (want == null || (want.skillId() == null && (want.query() == null || want.query().isBlank()))) { skill = 0.6; skillWhy = "browsing"; }

        // 2. nearness
        double near = Math.exp(-km / DIST_SCALE_KM);
        if (l.mode == Listing.Mode.ONLINE) near = ONLINE_NEAR;
        else if (l.mode == Listing.Mode.EITHER) near = Math.max(near, ONLINE_NEAR);

        // 3. shared free time
        List<String> shared = new ArrayList<>();
        Set<String> mine = new HashSet<>(me.availability);
        for (String s : owner.availability) if (mine.contains(s)) shared.add(s);
        int smaller = Math.min(me.availability.size(), owner.availability.size());
        double time = smaller == 0 ? 0 : Math.min(1.0, (double) shared.size() / smaller);

        // 4. trust
        Trust.Score t = trust.of(owner.id);
        double trustPart = 0.6 * ((t.rating() - 1) / 4.0) + 0.4 * t.reliability();

        // 5. level fit (only when the card is an offer and we know the learner's level)
        double level = 0.8;
        if (want != null && want.learnerLevel() != null && l.type == Listing.Type.OFFER) level = levelFit(l.level, want.learnerLevel());

        double total = W_SKILL * skill + W_NEAR * near + W_TIME * time + W_TRUST * trustPart + W_LEVEL * level;
        Map<String, Object> parts = Json.obj(
                "skill", pct(skill), "skillWhy", skillWhy,
                "near", pct(near), "nearWhy", l.mode == Listing.Mode.ONLINE ? "online" : Geo.shown(km) + " km away",
                "time", pct(time), "timeWhy", shared.isEmpty() ? "no shared free time yet" : shared.size() + " shared time slot" + (shared.size() == 1 ? "" : "s"),
                "trust", pct(trustPart), "trustWhy", String.format("%.1f★ · %d%% reliable", t.rating(), Math.round(t.reliability() * 100)),
                "level", pct(level));
        return new Result(l, Math.round(total * 1000) / 10.0, parts, km, shared);
    }

    static double levelFit(Listing.Level teacher, Listing.Level learner) {
        int gap = teacher.ordinal() - learner.ordinal();
        if (gap >= 1) return 1.0;
        if (gap == 0) return teacher == Listing.Level.EXPERT ? 0.9 : 0.6;
        return 0.2;
    }

    private static long pct(double v) { return Math.round(v * 100); }

    /** For each of my "want" cards, the best 3 teachers nearby. */
    public List<Map<String, Object>> suggestionsFor(User me, java.util.function.Function<Result, Map<String, Object>> view) {
        synchronized (store) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Listing w : store.listings.values()) {
                if (!w.active || w.type != Listing.Type.WANT || !w.userId.equals(me.id)) continue;
                List<Result> r = search(me, Listing.Type.OFFER, new Want(w.skillId, null, w.level), null, 0, null, 12).results();
                List<Map<String, Object>> cards = new ArrayList<>();
                Set<String> owners = new HashSet<>();
                for (Result x : r) {
                    // one card per teacher, at most 3, and only good matches
                    if (x.score() >= 45 && cards.size() < 3 && owners.add(x.listing().userId)) cards.add(view.apply(x));
                }
                out.add(Json.obj("want", Json.obj("id", w.id, "title", w.title, "skill", store.skillName(w.skillId)), "matches", cards));
            }
            return out;
        }
    }
}
