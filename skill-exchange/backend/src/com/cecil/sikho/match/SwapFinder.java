package com.cecil.sikho.match;

import com.cecil.sikho.model.Listing;
import com.cecil.sikho.model.User;
import com.cecil.sikho.store.DataStore;
import com.cecil.sikho.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds swaps: groups of neighbours who can all teach each other, so nobody has to "spend" credits.
 *
 * It builds a directed graph:  an arrow T → L means "T can teach L" (T offers a skill that L wants, and they are
 * close enough: within both people's travel limits, or the offer can be done online).
 *
 *   Direct swap  (a 2-cycle):   A → B and B → A               - "I teach you guitar, you teach me Excel"
 *   Swap circle  (a 3-cycle):   A → B,  B → C  and  C → A      - nobody is paired, but everyone teaches once
 *                                                                and learns once.
 *
 * Each cycle is listed once: it is written starting from its smallest member id (A < B and A < C).
 * With n members and e arrows the search is O(n · d²) where d is the largest number of arrows out of one member.
 */
public class SwapFinder {
    private final DataStore store;

    public SwapFinder(DataStore store) { this.store = store; }

    /** One arrow: who teaches whom, which skill, using which offer card. */
    public record Edge(String teacher, String learner, String skillId, String offerId, String wantId, double km) { }

    /** All arrows: teacher id -> (learner id -> the best edge). */
    public Map<String, Map<String, Edge>> graph() {
        synchronized (store) {
            Map<String, List<Listing>> offers = new LinkedHashMap<>(), wants = new LinkedHashMap<>();
            for (Listing l : store.listings.values()) {
                if (!l.active || !store.canAct(store.users.get(l.userId))) continue;
                (l.type == Listing.Type.OFFER ? offers : wants).computeIfAbsent(l.skillId, k -> new ArrayList<>()).add(l);
            }
            Map<String, Map<String, Edge>> g = new LinkedHashMap<>();
            for (Map.Entry<String, List<Listing>> e : offers.entrySet()) {
                for (Listing o : e.getValue()) {
                    for (Listing w : wants.getOrDefault(e.getKey(), List.of())) {
                        if (o.userId.equals(w.userId)) continue;
                        User t = store.users.get(o.userId), l = store.users.get(w.userId);
                        if (Matcher.blocked(t, l)) continue;
                        double km = Geo.km(t.lat, t.lng, l.lat, l.lng);
                        boolean online = o.mode != Listing.Mode.IN_PERSON && w.mode != Listing.Mode.IN_PERSON;
                        if (!online && km > Math.min(t.maxKm, l.maxKm)) continue;
                        g.computeIfAbsent(t.id, k -> new LinkedHashMap<>()).putIfAbsent(l.id, new Edge(t.id, l.id, o.skillId, o.id, w.id, km));
                    }
                }
            }
            return g;
        }
    }

    /** Direct swaps and 3-person circles. onlyFor = a member id to keep only their cycles (null = all). */
    public Map<String, Object> find(String onlyFor) {
        Map<String, Map<String, Edge>> g = graph();
        List<List<Edge>> pairs = new ArrayList<>(), triangles = new ArrayList<>();
        for (String a : g.keySet()) {
            for (Edge ab : g.get(a).values()) {
                String b = ab.learner();
                // pair: A → B and B → A, listed once with A < B
                if (a.compareTo(b) < 0) {
                    Edge ba = g.getOrDefault(b, Map.of()).get(a);
                    if (ba != null) pairs.add(List.of(ab, ba));
                }
                // triangle: A → B → C → A, listed once with A the smallest id
                if (a.compareTo(b) > 0) continue;
                for (Edge bc : g.getOrDefault(b, Map.of()).values()) {
                    String c = bc.learner();
                    if (c.equals(a) || a.compareTo(c) > 0) continue;
                    Edge ca = g.getOrDefault(c, Map.of()).get(a);
                    if (ca != null) triangles.add(List.of(ab, bc, ca));
                }
            }
        }
        if (onlyFor != null) {
            pairs.removeIf(cy -> cy.stream().noneMatch(e -> e.teacher().equals(onlyFor)));
            triangles.removeIf(cy -> cy.stream().noneMatch(e -> e.teacher().equals(onlyFor)));
        }
        int edges = 0;
        for (Map<String, Edge> m : g.values()) edges += m.size();
        return Json.obj("pairs", view(pairs, onlyFor), "circles", view(triangles, onlyFor),
                "pairCount", pairs.size(), "circleCount", triangles.size(), "arrows", edges);
    }

    /** Each cycle as a list of steps, rotated so that "me" comes first when onlyFor is set. */
    private List<Object> view(List<List<Edge>> cycles, String me) {
        List<Object> out = new ArrayList<>();
        synchronized (store) {
            for (List<Edge> cy : cycles) {
                List<Edge> c = new ArrayList<>(cy);
                if (me != null) while (!c.get(0).teacher().equals(me)) c.add(c.remove(0));
                List<Object> steps = new ArrayList<>();
                double loopKm = 0;
                for (Edge e : c) {
                    loopKm += e.km();
                    steps.add(Json.obj("teacherId", e.teacher(), "teacher", store.userName(e.teacher()),
                            "learnerId", e.learner(), "learner", store.userName(e.learner()),
                            "skillId", e.skillId(), "skill", store.skillName(e.skillId()), "offerId", e.offerId(),
                            "km", Geo.shown(e.km())));
                }
                out.add(Json.obj("steps", steps, "loopKm", Geo.shown(loopKm)));
            }
        }
        return out;
    }
}
