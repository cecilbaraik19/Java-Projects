package com.cecil.sql.ai;

import com.cecil.sql.db.Database;
import com.cecil.sql.db.Table;
import com.cecil.sql.db.TableSchema;

import java.util.*;

/**
 * Works out the JOINs. Tables are points of a graph and every foreign key is a line between two of them:
 *
 *   departments ── students ── enrollments ── courses ── faculty
 *                     │
 *                fee_payments
 *
 * A shortest-path search (Dijkstra) finds the chain of lines from the main table to each table the question
 * needs. Every line costs 1; passing THROUGH a plain look-up table (one with no foreign keys of its own, like
 * departments) costs a little more, so "students" and "courses" are linked through enrollments (the courses a
 * student takes) rather than through departments (the courses of the student's department).
 * The search starts from all tables already joined, so two needs share the same joins.
 */
public final class JoinPlanner {
    private JoinPlanner() {}

    /** One JOIN: "JOIN to ON to.toColumn = from.fromColumn". */
    public record Edge(TableSchema from, String fromColumn, TableSchema to, String toColumn) {}

    static final double LOOKUP_PENALTY = 0.25;

    private static List<Edge> neighbours(Database db, TableSchema t) {
        List<Edge> l = new ArrayList<>();
        for (TableSchema.ForeignKey fk : t.foreignKeys) {          // child -> parent
            Table p = db.table(fk.refTable());
            if (p != null) l.add(new Edge(t, fk.column(), p.schema, fk.refColumn()));
        }
        for (Table other : db.tables())                            // parent -> child
            for (TableSchema.ForeignKey fk : other.schema.foreignKeys)
                if (fk.refTable().equalsIgnoreCase(t.name)) l.add(new Edge(t, fk.refColumn(), other.schema, fk.column()));
        return l;
    }

    /** The tables on the shortest path from a to b (both included), or null if they are not connected. */
    public static List<TableSchema> path(Database db, TableSchema a, TableSchema b) {
        if (a == b) return List.of(a);
        List<Edge> edges = search(db, Set.of(a), b);
        if (edges == null) return null;
        List<TableSchema> l = new ArrayList<>();
        l.add(a);
        for (Edge e : edges) l.add(e.to());
        return l;
    }

    /** All JOINs needed so that every table in {@code needed} is reachable from {@code main}. */
    public static List<Edge> plan(Database db, TableSchema main, List<TableSchema> needed) {
        List<Edge> out = new ArrayList<>();
        Set<TableSchema> joined = new LinkedHashSet<>();
        joined.add(main);
        for (TableSchema t : needed) {
            if (joined.contains(t)) continue;
            List<Edge> p = search(db, joined, t);
            if (p == null) throw new IllegalStateException("Table " + t.name + " is not linked to " + main.name + ".");
            for (Edge e : p) if (joined.add(e.to())) out.add(e);
        }
        return out;
    }

    private static List<Edge> search(Database db, Set<TableSchema> starts, TableSchema goal) {
        Map<TableSchema, Double> dist = new HashMap<>();
        Map<TableSchema, Edge> cameFrom = new HashMap<>();
        PriorityQueue<Map.Entry<TableSchema, Double>> queue = new PriorityQueue<>(Map.Entry.comparingByValue());
        for (TableSchema s : starts) { dist.put(s, 0.0); queue.add(Map.entry(s, 0.0)); }
        while (!queue.isEmpty()) {
            Map.Entry<TableSchema, Double> top = queue.poll();
            TableSchema cur = top.getKey();
            if (top.getValue() > dist.get(cur)) continue;
            if (cur == goal) break;
            // leaving a look-up table that is not the start costs extra
            double extra = !starts.contains(cur) && cur.foreignKeys.isEmpty() ? LOOKUP_PENALTY : 0;
            for (Edge e : neighbours(db, cur)) {
                double d = dist.get(cur) + 1 + extra;
                if (d < dist.getOrDefault(e.to(), Double.MAX_VALUE)) {
                    dist.put(e.to(), d);
                    cameFrom.put(e.to(), e);
                    queue.add(Map.entry(e.to(), d));
                }
            }
        }
        if (!dist.containsKey(goal)) return null;
        LinkedList<Edge> path = new LinkedList<>();
        TableSchema at = goal;
        while (!starts.contains(at)) {
            Edge e = cameFrom.get(at);
            path.addFirst(e);
            at = e.from();
        }
        return path;
    }
}
