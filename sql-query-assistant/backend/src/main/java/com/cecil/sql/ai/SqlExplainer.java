package com.cecil.sql.ai;

import com.cecil.sql.db.*;
import com.cecil.sql.db.Ast.*;

import java.util.*;

/**
 * Turns any SELECT (written by the engine, by Claude or by a person) into plain-English steps, in the order the
 * database actually runs them: FROM -> JOIN -> WHERE -> GROUP BY -> HAVING -> SELECT -> ORDER BY -> LIMIT.
 */
public final class SqlExplainer {
    private SqlExplainer() {}

    public record Step(String clause, String text) {}

    public static List<Step> explain(Database db, String sql) {
        Select s = SqlParser.parse(sql);
        Map<String, TableSchema> alias = new HashMap<>();
        List<Step> out = new ArrayList<>();
        TableSchema from = schema(db, s.from().table());
        put(alias, s.from(), from);
        out.add(new Step("FROM", "Start with the " + label(from, s.from().table()) + " table"
                + (from != null && db.table(from.name) != null ? " (" + db.table(from.name).size() + " rows)." : ".")));
        for (Join j : s.joins()) {
            TableSchema t = schema(db, j.table().table());
            put(alias, j.table(), t);
            out.add(new Step(j.left() ? "LEFT JOIN" : "JOIN", "Link each row to " + label(t, j.table().table())
                    + " where " + cond(j.on(), alias) + (j.left() ? " (keep rows that have no match)." : ".")));
        }
        if (s.where() != null) out.add(new Step("WHERE", "Keep only rows where " + cond(s.where(), alias) + "."));
        if (!s.groupBy().isEmpty()) {
            List<String> g = new ArrayList<>();
            for (Expr e : s.groupBy()) g.add(expr(e, alias));
            out.add(new Step("GROUP BY", "Put rows with the same " + String.join(" and ", g) + " into one group."));
        }
        if (s.having() != null) out.add(new Step("HAVING", "Keep only groups where " + cond(s.having(), alias) + "."));
        List<String> cols = new ArrayList<>();
        for (SelectItem it : s.items()) {
            if (it.star()) cols.add(it.starTable() == null ? "every column" : "every column of " + it.starTable());
            else cols.add(expr(it.expr(), alias) + (it.alias() != null ? " (shown as \"" + it.alias() + "\")" : ""));
        }
        out.add(new Step("SELECT", (s.distinct() ? "Show each different " : "Show ") + String.join(", ", cols)
                + (s.distinct() ? " once (no repeats)." : ".")));
        if (!s.orderBy().isEmpty()) {
            List<String> o = new ArrayList<>();
            for (OrderItem it : s.orderBy()) o.add(expr(it.expr(), alias) + (it.desc() ? " (highest first)" : " (lowest first)"));
            out.add(new Step("ORDER BY", "Sort by " + String.join(", then ", o) + "."));
        }
        if (s.limit() != null)
            out.add(new Step("LIMIT", s.limit() == 1 ? "Keep only the first row." : "Keep only the first " + s.limit() + " rows."
                    + (s.offset() != null ? " Skip the first " + s.offset() + "." : "")));
        return out;
    }

    private static TableSchema schema(Database db, String name) {
        Table t = db.table(name);
        return t == null ? null : t.schema;
    }

    private static void put(Map<String, TableSchema> alias, TableRef r, TableSchema t) {
        if (t == null) return;
        alias.put(r.table().toLowerCase(), t);
        if (r.alias() != null) alias.put(r.alias().toLowerCase(), t);
    }

    private static String label(TableSchema t, String fallback) { return t == null ? fallback : t.name; }

    static String cond(Expr e, Map<String, TableSchema> alias) {
        if (e instanceof Binary b && (b.op().equals("AND") || b.op().equals("OR")))
            return cond(b.left(), alias) + (b.op().equals("AND") ? " and " : " or ") + cond(b.right(), alias);
        if (e instanceof Unary u && u.op().equals("NOT")) return "not (" + cond(u.expr(), alias) + ")";
        if (e instanceof Binary b) {
            String op = switch (b.op()) {
                case "=" -> "is";
                case "!=" -> "is not";
                case ">" -> "is more than";
                case ">=" -> "is at least";
                case "<" -> "is less than";
                case "<=" -> "is at most";
                default -> b.op();
            };
            return expr(b.left(), alias) + " " + op + " " + expr(b.right(), alias);
        }
        if (e instanceof InList in) return expr(in.expr(), alias) + (in.not() ? " is none of " : " is one of ")
                + String.join(", ", in.values().stream().map(v -> expr(v, alias)).toList());
        if (e instanceof Between bt) return expr(bt.expr(), alias) + (bt.not() ? " is not between " : " is between ")
                + expr(bt.low(), alias) + " and " + expr(bt.high(), alias);
        if (e instanceof IsNull n) return expr(n.expr(), alias) + (n.not() ? " is filled in" : " is empty");
        if (e instanceof Like l && l.pattern() instanceof Literal lit && lit.value() instanceof String p) {
            String core = p.replace("%", "");
            String how = p.startsWith("%") && p.endsWith("%") ? "contains" : p.endsWith("%") ? "starts with" : p.startsWith("%") ? "ends with" : "matches";
            return expr(l.expr(), alias) + (l.not() ? " does not " + how.replace("s ", " ").replace("contains", "contain") : " " + how) + " \"" + core.trim() + "\"";
        }
        return expr(e, alias);
    }

    static String expr(Expr e, Map<String, TableSchema> alias) {
        if (e instanceof ColumnRef c) {
            TableSchema t = c.table() == null ? null : alias.get(c.table().toLowerCase());
            Column col = null;
            if (t != null) col = t.column(c.column());
            else for (TableSchema ts : new LinkedHashSet<>(alias.values())) if (ts.column(c.column()) != null) { col = ts.column(c.column()); t = ts; break; }
            String name = col != null ? col.label : c.column();
            return t != null && alias.size() > 2 ? name + " of " + t.name : name;
        }
        if (e instanceof Func f) {
            String inner = f.star() ? "rows" : String.join(", ", f.args().stream().map(a -> expr(a, alias)).toList());
            return switch (f.name()) {
                case "COUNT" -> f.star() ? "the number of rows" : "the number of " + (f.distinct() ? "different " : "") + inner;
                case "SUM" -> "the total of " + inner;
                case "AVG" -> "the average of " + inner;
                case "MAX" -> "the highest " + inner;
                case "MIN" -> "the lowest " + inner;
                case "ROUND" -> f.args().isEmpty() ? "ROUND()" : expr(f.args().get(0), alias) + " (rounded)";
                default -> f.name() + "(" + inner + ")";
            };
        }
        if (e instanceof Literal l) return l.value() instanceof String s ? "\"" + s + "\"" : Ast.text(e);
        return Ast.text(e);
    }
}
