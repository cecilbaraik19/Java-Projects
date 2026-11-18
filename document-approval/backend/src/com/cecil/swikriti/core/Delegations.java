package com.cecil.swikriti.core;

import com.cecil.swikriti.model.Delegation;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Delegations form a DIRECTED GRAPH: an arrow A -> B means "B may approve for A".
 *
 *  - who(A): follow the arrows that are valid today (A -> B -> C ...) and collect everybody on the way.
 *    A chain stops at 5 steps and never visits a person twice.
 *  - loop check: a new arrow A -> B is refused if B can already reach A through arrows whose dates overlap
 *    (then the work would go round in a circle and nobody would be "really" responsible).
 *    This is a depth-first search on the graph.
 */
public final class Delegations {
    public static final int MAX_CHAIN = 5;

    private Delegations() { }

    /** person -> the delegates (in order of the chain) who may act for them today. */
    public static List<String> who(String userId, LocalDate day, String type, Collection<Delegation> all) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(userId);
        String cur = userId;
        for (int step = 0; step < MAX_CHAIN; step++) {
            String next = null;
            for (Delegation d : all) if (d.fromId.equals(cur) && d.covers(day, type)) { next = d.toId; break; }
            if (next == null || !seen.add(next)) break;
            out.add(next);
            cur = next;
        }
        return out;
    }

    /** Would adding `nd` make a circle with the delegations whose dates overlap it? */
    public static boolean wouldLoop(Delegation nd, Collection<Delegation> all) {
        if (nd.fromId.equals(nd.toId)) return true;
        Map<String, List<String>> arrows = new LinkedHashMap<>();
        for (Delegation d : all) {
            if (d.cancelled || !d.overlaps(nd.start, nd.end)) continue;
            arrows.computeIfAbsent(d.fromId, k -> new ArrayList<>()).add(d.toId);
        }
        // depth-first search from the new delegate: can we get back to the person who delegates?
        Deque<String> stack = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        stack.push(nd.toId);
        while (!stack.isEmpty()) {
            String p = stack.pop();
            if (p.equals(nd.fromId)) return true;
            if (!seen.add(p)) continue;
            for (String q : arrows.getOrDefault(p, List.of())) stack.push(q);
        }
        return false;
    }
}
