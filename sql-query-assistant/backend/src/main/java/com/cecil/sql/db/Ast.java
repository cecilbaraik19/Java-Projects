package com.cecil.sql.db;

import java.util.List;

/**
 * Step 2 output: the "abstract syntax tree" – the query as Java objects instead of text.
 * A SELECT becomes one {@link Select}; every value or condition inside it is an {@link Expr}.
 */
public final class Ast {
    private Ast() {}

    public sealed interface Expr permits Literal, ColumnRef, Binary, Unary, Func, InList, Between, IsNull, Like, Case {}

    /** A fixed value: 8, 'Ranchi', NULL. */
    public record Literal(Object value) implements Expr {}
    /** A column: cgpa or s.cgpa. table may be null. */
    public record ColumnRef(String table, String column, int pos) implements Expr {}
    /** a + b, a = b, a AND b ... */
    public record Binary(String op, Expr left, Expr right) implements Expr {}
    /** NOT a, -a */
    public record Unary(String op, Expr expr) implements Expr {}
    /** COUNT(*), SUM(x), ROUND(x, 1) ... star = COUNT(*). */
    public record Func(String name, List<Expr> args, boolean distinct, boolean star) implements Expr {
        public boolean aggregate() { return SqlExecutor.AGGREGATES.contains(name); }
    }
    public record InList(Expr expr, List<Expr> values, boolean not) implements Expr {}
    public record Between(Expr expr, Expr low, Expr high, boolean not) implements Expr {}
    public record IsNull(Expr expr, boolean not) implements Expr {}
    public record Like(Expr expr, Expr pattern, boolean not) implements Expr {}
    public record Case(List<Expr> whens, List<Expr> thens, Expr otherwise) implements Expr {}

    /** One item after SELECT. star = "*" or "s.*" (starTable). */
    public record SelectItem(Expr expr, String alias, boolean star, String starTable) {}
    public record TableRef(String table, String alias, int pos) {}
    public record Join(TableRef table, boolean left, Expr on) {}
    public record OrderItem(Expr expr, boolean desc) {}

    public record Select(boolean distinct, List<SelectItem> items, TableRef from, List<Join> joins, Expr where,
                         List<Expr> groupBy, Expr having, List<OrderItem> orderBy, Long limit, Long offset) {}

    /** True if the expression contains COUNT/SUM/AVG/MIN/MAX anywhere. */
    public static boolean hasAggregate(Expr e) {
        if (e == null) return false;
        if (e instanceof Func f) return f.aggregate() || f.args().stream().anyMatch(Ast::hasAggregate);
        if (e instanceof Binary b) return hasAggregate(b.left()) || hasAggregate(b.right());
        if (e instanceof Unary u) return hasAggregate(u.expr());
        if (e instanceof InList in) return hasAggregate(in.expr()) || in.values().stream().anyMatch(Ast::hasAggregate);
        if (e instanceof Between bt) return hasAggregate(bt.expr()) || hasAggregate(bt.low()) || hasAggregate(bt.high());
        if (e instanceof IsNull n) return hasAggregate(n.expr());
        if (e instanceof Like l) return hasAggregate(l.expr()) || hasAggregate(l.pattern());
        if (e instanceof Case c) return c.whens().stream().anyMatch(Ast::hasAggregate)
                || c.thens().stream().anyMatch(Ast::hasAggregate) || hasAggregate(c.otherwise());
        return false;
    }

    /** Writes an expression back as SQL text (used for column headings and explanations). */
    public static String text(Expr e) {
        if (e == null) return "";
        if (e instanceof Literal l) {
            Object v = l.value();
            if (v == null) return "NULL";
            if (v instanceof String s) return "'" + s.replace("'", "''") + "'";
            if (v instanceof Boolean b) return b ? "TRUE" : "FALSE";
            return SqlExecutor.formatNumber(v);
        }
        if (e instanceof ColumnRef c) return c.table() == null ? c.column() : c.table() + "." + c.column();
        if (e instanceof Binary b) return text(b.left()) + " " + b.op() + " " + text(b.right());
        if (e instanceof Unary u) return u.op().equals("NOT") ? "NOT " + text(u.expr()) : u.op() + text(u.expr());
        if (e instanceof Func f) return f.name() + "(" + (f.star() ? "*" : (f.distinct() ? "DISTINCT " : "")
                + String.join(", ", f.args().stream().map(Ast::text).toList())) + ")";
        if (e instanceof InList in) return text(in.expr()) + (in.not() ? " NOT IN (" : " IN (")
                + String.join(", ", in.values().stream().map(Ast::text).toList()) + ")";
        if (e instanceof Between bt) return text(bt.expr()) + (bt.not() ? " NOT BETWEEN " : " BETWEEN ")
                + text(bt.low()) + " AND " + text(bt.high());
        if (e instanceof IsNull n) return text(n.expr()) + (n.not() ? " IS NOT NULL" : " IS NULL");
        if (e instanceof Like l) return text(l.expr()) + (l.not() ? " NOT LIKE " : " LIKE ") + text(l.pattern());
        Case c = (Case) e;
        StringBuilder sb = new StringBuilder("CASE");
        for (int i = 0; i < c.whens().size(); i++)
            sb.append(" WHEN ").append(text(c.whens().get(i))).append(" THEN ").append(text(c.thens().get(i)));
        if (c.otherwise() != null) sb.append(" ELSE ").append(text(c.otherwise()));
        return sb.append(" END").toString();
    }
}
