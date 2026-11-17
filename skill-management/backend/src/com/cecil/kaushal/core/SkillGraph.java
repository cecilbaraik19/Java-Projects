package com.cecil.kaushal.core;

import com.cecil.kaushal.model.Skill;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Prerequisites between skills form a DIRECTED ACYCLIC GRAPH (DAG): an arrow from Kubernetes to Docker means
 * "learn Docker first". Loops ("A needs B, B needs A") are refused - nobody could ever start.
 *
 *  - wouldLoop: depth-first search from the new prerequisite; if it can reach the skill, the new arrow closes a loop.
 *  - order (Kahn's algorithm): every skill after the skills it needs.
 *  - learningPath: the skills to learn for some targets INCLUDING missing prerequisites, prerequisites first.
 */
public final class SkillGraph {
    private SkillGraph() { }

    /** Would "skill needs prereq" create a loop? */
    public static boolean wouldLoop(Map<String, Skill> skills, String skillId, String prereqId) {
        if (skillId.equals(prereqId)) return true;
        Deque<String> stack = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        stack.push(prereqId);
        while (!stack.isEmpty()) {
            String s = stack.pop();
            if (s.equals(skillId)) return true;
            if (!seen.add(s)) continue;
            Skill k = skills.get(s);
            if (k != null) for (String p : k.prereqs.keySet()) stack.push(p);
        }
        return false;
    }

    /** Topological order (Kahn): prerequisites first. Throws if there is a loop (should never happen). */
    public static List<String> order(Map<String, Skill> skills) {
        Map<String, Integer> waiting = new HashMap<>();
        Map<String, List<String>> neededBy = new HashMap<>();
        for (Skill s : skills.values()) {
            int n = 0;
            for (String p : s.prereqs.keySet()) if (skills.containsKey(p)) {
                n++;
                neededBy.computeIfAbsent(p, x -> new ArrayList<>()).add(s.id);
            }
            waiting.put(s.id, n);
        }
        Deque<String> ready = new ArrayDeque<>();
        for (Skill s : skills.values()) if (waiting.get(s.id) == 0) ready.add(s.id);
        List<String> out = new ArrayList<>();
        while (!ready.isEmpty()) {
            String s = ready.poll();
            out.add(s);
            for (String n : neededBy.getOrDefault(s, List.of()))
                if (waiting.merge(n, -1, Integer::sum) == 0) ready.add(n);
        }
        if (out.size() != skills.size()) throw new IllegalStateException("The prerequisites have a loop");
        return out;
    }

    /**
     * What to learn to reach the targets (skillId -> level), with missing prerequisites added, in a safe order.
     * have = the person's levels now. Returns skillId -> level to reach, prerequisites first (DFS post-order).
     */
    public static LinkedHashMap<String, Integer> learningPath(Map<String, Skill> skills, Map<String, Integer> have,
                                                              Map<String, Integer> targets) {
        Map<String, Integer> need = new HashMap<>();
        // 1. work out the highest level needed of every skill (targets, and prerequisites of anything to learn)
        Deque<String> todo = new ArrayDeque<>(targets.keySet());
        targets.forEach((k, v) -> need.merge(k, v, Math::max));
        while (!todo.isEmpty()) {
            String s = todo.pop();
            Skill k = skills.get(s);
            if (k == null || have.getOrDefault(s, 0) >= need.get(s)) continue;
            for (Map.Entry<String, Integer> p : k.prereqs.entrySet()) {
                Integer before = need.get(p.getKey());
                if (before == null || before < p.getValue()) {
                    need.put(p.getKey(), p.getValue());
                    todo.push(p.getKey());
                }
            }
        }
        // 2. depth-first post-order = prerequisites before the skills that need them
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        Set<String> done = new HashSet<>();
        List<String> roots = new ArrayList<>(need.keySet());
        roots.sort(null);
        for (String r : roots) visit(r, skills, have, need, done, out);
        return out;
    }

    private static void visit(String s, Map<String, Skill> skills, Map<String, Integer> have, Map<String, Integer> need,
                              Set<String> done, LinkedHashMap<String, Integer> out) {
        if (!done.add(s)) return;
        Skill k = skills.get(s);
        if (k != null) for (String p : k.prereqs.keySet()) if (need.containsKey(p)) visit(p, skills, have, need, done, out);
        if (have.getOrDefault(s, 0) < need.get(s)) out.put(s, need.get(s));
    }
}
