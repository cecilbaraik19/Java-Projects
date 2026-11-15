package com.cecil.sangam.core;

import com.cecil.sangam.model.Task;
import com.cecil.sangam.model.TaskStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tasks that wait for other tasks form a graph: an arrow from A to B means "B is blocked by A".
 * The graph must never have a loop (A waits for B, B waits for A = nobody can ever start).
 *
 *  - wouldLoop:     depth-first search before adding a new arrow
 *  - order:         Kahn's topological sort (a safe order to do the work in)
 *  - criticalPath:  the longest chain of unfinished work (by estimated hours) – the chain that decides
 *                   when the whole project can be finished
 */
public final class DependencyGraph {
    private DependencyGraph() { }

    /**
     * Would "task is blocked by blocker" make a loop? It does if the blocker already (directly or through others)
     * waits for task. We walk the blockedBy arrows starting from the blocker.
     */
    public static boolean wouldLoop(Map<String, Task> tasks, String taskId, String blockerId) {
        if (taskId.equals(blockerId)) return true;
        Deque<String> stack = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        stack.push(blockerId);
        while (!stack.isEmpty()) {
            String cur = stack.pop();
            if (cur.equals(taskId)) return true;
            if (!seen.add(cur)) continue;
            Task t = tasks.get(cur);
            if (t != null) for (String b : t.blockedBy) stack.push(b);
        }
        return false;
    }

    /** Is the task still waiting for an unfinished blocker? */
    public static boolean blocked(Map<String, Task> tasks, Task t) {
        for (String b : t.blockedBy) {
            Task x = tasks.get(b);
            if (x != null && x.status != TaskStatus.DONE) return true;
        }
        return false;
    }

    /** Kahn's algorithm: an order where every blocker comes before the tasks that wait for it. */
    public static List<Task> order(List<Task> list) {
        Map<String, Task> byId = new HashMap<>();
        for (Task t : list) byId.put(t.id, t);
        Map<String, Integer> waiting = new HashMap<>();
        Map<String, List<String>> unlocks = new HashMap<>();
        for (Task t : list) {
            int n = 0;
            for (String b : t.blockedBy) if (byId.containsKey(b)) { n++; unlocks.computeIfAbsent(b, k -> new ArrayList<>()).add(t.id); }
            waiting.put(t.id, n);
        }
        Deque<Task> ready = new ArrayDeque<>();
        for (Task t : list) if (waiting.get(t.id) == 0) ready.add(t);
        List<Task> out = new ArrayList<>();
        while (!ready.isEmpty()) {
            Task t = ready.poll();
            out.add(t);
            for (String next : unlocks.getOrDefault(t.id, List.of())) {
                int left = waiting.merge(next, -1, Integer::sum);
                if (left == 0) ready.add(byId.get(next));
            }
        }
        return out; // if there were a loop, the tasks in it would be missing (we never allow one)
    }

    /**
     * Longest chain of UNFINISHED tasks, counting estimated hours. Dynamic programming over the topological order:
     *   best[t] = estimate[t] + max(best[blocker]) over unfinished blockers.
     * Returns the chain (first task first); empty when nothing is left.
     */
    public static List<Task> criticalPath(List<Task> projectTasks) {
        List<Task> open = new ArrayList<>();
        for (Task t : projectTasks) if (t.status != TaskStatus.DONE) open.add(t);
        Map<String, Integer> best = new HashMap<>();
        Map<String, String> prev = new HashMap<>();
        Set<String> openIds = new HashSet<>();
        for (Task t : open) openIds.add(t.id);
        String end = null;
        int endVal = 0;
        for (Task t : order(open)) {
            int base = 0;
            String from = null;
            for (String b : t.blockedBy) {
                if (!openIds.contains(b)) continue;
                int v = best.getOrDefault(b, 0);
                if (v > base) { base = v; from = b; }
            }
            int v = base + Math.max(1, t.estimate);
            best.put(t.id, v);
            if (from != null) prev.put(t.id, from);
            if (v > endVal) { endVal = v; end = t.id; }
        }
        Map<String, Task> byId = new HashMap<>();
        for (Task t : open) byId.put(t.id, t);
        List<Task> chain = new ArrayList<>();
        for (String cur = end; cur != null; cur = prev.get(cur)) chain.add(byId.get(cur));
        Collections.reverse(chain);
        return chain;
    }
}
