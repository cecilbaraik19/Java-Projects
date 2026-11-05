package com.cecil.sql.db;

import com.cecil.sql.db.Ast.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Step 3 of running SQL: actually runs the {@link Select} tree on the in-memory tables.
 *
 *   FROM + JOIN  -> build combined rows (hash join when the ON is "a = b", else nested loop)
 *   WHERE        -> keep rows where the condition is true
 *   GROUP BY     -> put rows into groups; COUNT / SUM / AVG / MIN / MAX work on each group
 *   HAVING       -> keep groups where the condition is true
 *   SELECT       -> work out each output column
 *   DISTINCT     -> drop repeated rows
 *   ORDER BY     -> sort        LIMIT / OFFSET -> cut
 *
 * Safety: read-only (the parser only allows SELECT), a cap on joined rows so a careless JOIN cannot
 * freeze the server, a cap on rows sent back, and private columns checked through {@link AccessPolicy}.
 */
public final class SqlExecutor {

    public static final Set<String> AGGREGATES = Set.of("COUNT", "SUM", "AVG", "MIN", "MAX");
    public static final int MAX_ROWS_RETURNED = 1000;
    public static final long MAX_ROWS_WORKED = 400_000;

    /** Decides whether the current user may read a column (Viewers cannot read salary, email ...). */
    public interface AccessPolicy {
        boolean canRead(TableSchema table, Column column);
        AccessPolicy ALL = (t, c) -> true;
        AccessPolicy NO_PRIVATE = (t, c) -> !c.sensitive;
    }

    private final Database db;
    private final AccessPolicy policy;

    public SqlExecutor(Database db, AccessPolicy policy) {
        this.db = db;
        this.policy = policy == null ? AccessPolicy.ALL : policy;
    }

    public QueryResult run(String sql) {
        long t0 = System.nanoTime();
        QueryResult r = run(SqlParser.parse(sql));
        r.millis = Math.max(0, (System.nanoTime() - t0) / 1_000_000);
        return r;
    }

    // ------------------------------------------------------------------ scope: which tables are in the query
    private record Binding(String alias, Table table, int offset) {}

    private final class Scope {
        final List<Binding> bindings = new ArrayList<>();
        int width = 0;
        final Map<ColumnRef, Integer> cache = new HashMap<>();

        void add(TableRef ref) {
            Table tb = db.table(ref.table());
            if (tb == null) throw new SqlException("There is no table called '" + ref.table() + "'." + suggestTable(ref.table()), ref.pos());
            String alias = (ref.alias() == null ? tb.schema.name : ref.alias()).toLowerCase();
            for (Binding b : bindings)
                if (b.alias.equals(alias)) throw new SqlException("The name '" + alias + "' is used twice. Give each table its own short name.", ref.pos());
            bindings.add(new Binding(alias, tb, width));
            width += tb.schema.columns.size();
        }

        int resolve(ColumnRef c) {
            Integer hit = cache.get(c);
            if (hit != null) return hit;
            int idx = find(c);
            cache.put(c, idx);
            return idx;
        }

        private int find(ColumnRef c) {
            if (c.table() != null) {
                Binding b = binding(c.table());
                if (b == null) throw new SqlException("'" + c.table() + "' is not a table in this query. Tables here: "
                        + String.join(", ", bindings.stream().map(x -> x.alias).toList()) + ".", c.pos());
                int i = b.table.schema.indexOf(c.column());
                if (i < 0) throw new SqlException("Table " + b.table.schema.name + " has no column '" + c.column() + "'."
                        + suggestColumn(c.column(), List.of(b)), c.pos());
                check(b, i, c);
                return b.offset + i;
            }
            int found = -1;
            Binding where = null;
            for (Binding b : bindings) {
                int i = b.table.schema.indexOf(c.column());
                if (i >= 0) {
                    if (found >= 0) throw new SqlException("Column '" + c.column() + "' is in both " + where.table.schema.name
                            + " and " + b.table.schema.name + ". Write " + where.alias + "." + c.column() + " or "
                            + b.alias + "." + c.column() + ".", c.pos());
                    found = b.offset + i;
                    where = b;
                }
            }
            if (found < 0) throw new SqlException("There is no column '" + c.column() + "'." + suggestColumn(c.column(), bindings), c.pos());
            check(where, found - where.offset, c);
            return found;
        }

        private void check(Binding b, int i, ColumnRef c) {
            Column col = b.table.schema.columns.get(i);
            if (!policy.canRead(b.table.schema, col))
                throw new SqlException("You do not have permission to read " + b.table.schema.name + "." + col.name
                        + " (a private column).", c.pos());
        }

        Binding binding(String name) {
            String n = name.toLowerCase();
            for (Binding b : bindings) if (b.alias.equals(n)) return b;
            for (Binding b : bindings) if (b.table.schema.name.equalsIgnoreCase(n)) return b;
            return null;
        }
    }

    // ------------------------------------------------------------------ running
    private long worked;

    public QueryResult run(Select s) {
        worked = 0;
        Scope scope = new Scope();
        scope.add(s.from());
        for (Join j : s.joins()) scope.add(j.table());

        // 1. FROM: copy the first table's rows into wide rows
        List<Object[]> rows = new ArrayList<>();
        Binding first = scope.bindings.get(0);
        for (Object[] r : first.table.rows()) {
            Object[] w = new Object[scope.width];
            System.arraycopy(r, 0, w, 0, r.length);
            rows.add(w);
        }
        worked += rows.size();

        // 2. JOINs
        for (int j = 0; j < s.joins().size(); j++) rows = join(scope, rows, scope.bindings.get(j + 1), s.joins().get(j));

        // 3. WHERE
        if (s.where() != null) {
            if (Ast.hasAggregate(s.where()))
                throw new SqlException("COUNT, SUM, AVG, MIN and MAX cannot be used in WHERE. Use HAVING after GROUP BY.");
            List<Object[]> kept = new ArrayList<>();
            for (Object[] r : rows) if (truthy(eval(s.where(), scope, r, null))) kept.add(r);
            rows = kept;
        }

        // 4. Work out SELECT items (expand *)
        List<SelectItem> items = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        for (SelectItem it : s.items()) {
            if (it.star()) {
                List<Binding> bs = it.starTable() == null ? scope.bindings : new ArrayList<>();
                if (it.starTable() != null) {
                    Binding b = scope.binding(it.starTable());
                    if (b == null) throw new SqlException("'" + it.starTable() + "' is not a table in this query.");
                    bs.add(b);
                }
                for (Binding b : bs)
                    for (Column c : b.table.schema.columns) {
                        if (!policy.canRead(b.table.schema, c)) continue;   // private columns are left out of *
                        items.add(new SelectItem(new ColumnRef(b.alias, c.name, -1), null, false, null));
                        headings.add(c.name);
                    }
            } else {
                items.add(it);
                headings.add(it.alias() != null ? it.alias()
                        : it.expr() instanceof ColumnRef c ? c.column() : Ast.text(it.expr()));
            }
        }
        makeUnique(headings);

        // ORDER BY may use a heading (alias) or a position (ORDER BY 2)
        List<OrderItem> order = new ArrayList<>();
        for (OrderItem o : s.orderBy()) order.add(new OrderItem(replaceAlias(o.expr(), s.items(), items, true), o.desc()));
        Expr having = s.having() == null ? null : replaceAlias(s.having(), s.items(), items, false);

        boolean grouped = !s.groupBy().isEmpty() || items.stream().anyMatch(i -> Ast.hasAggregate(i.expr()))
                || Ast.hasAggregate(having) || order.stream().anyMatch(o -> Ast.hasAggregate(o.expr()));
        if (having != null && !grouped) throw new SqlException("HAVING needs GROUP BY or COUNT / SUM / AVG / MIN / MAX.");

        // 5. GROUP BY (or one group of everything) and build output rows with their sort keys
        List<Object[]> out = new ArrayList<>();      // [outputValues..., sortKeys...]
        int nItems = items.size();
        if (grouped) {
            for (Expr g : s.groupBy())
                if (Ast.hasAggregate(g)) throw new SqlException("You cannot GROUP BY a COUNT / SUM / AVG / MIN / MAX.");
            Map<List<Object>, List<Object[]>> groups = new LinkedHashMap<>();
            if (s.groupBy().isEmpty()) groups.put(List.of(), rows);
            else for (Object[] r : rows) {
                List<Object> key = new ArrayList<>();
                for (Expr g : s.groupBy()) key.add(groupKey(eval(g, scope, r, null)));
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
            }
            Object[] empty = new Object[scope.width];
            for (List<Object[]> g : groups.values()) {
                Object[] firstRow = g.isEmpty() ? empty : g.get(0);
                if (having != null && !truthy(eval(having, scope, firstRow, g))) continue;
                Object[] o = new Object[nItems + order.size()];
                for (int i = 0; i < nItems; i++) o[i] = eval(items.get(i).expr(), scope, firstRow, g);
                for (int k = 0; k < order.size(); k++) o[nItems + k] = eval(order.get(k).expr(), scope, firstRow, g);
                out.add(o);
            }
        } else {
            for (Object[] r : rows) {
                Object[] o = new Object[nItems + order.size()];
                for (int i = 0; i < nItems; i++) o[i] = eval(items.get(i).expr(), scope, r, null);
                for (int k = 0; k < order.size(); k++) o[nItems + k] = eval(order.get(k).expr(), scope, r, null);
                out.add(o);
            }
        }

        // 6. DISTINCT
        if (s.distinct()) {
            Map<List<Object>, Object[]> seen = new LinkedHashMap<>();
            for (Object[] o : out) {
                List<Object> key = new ArrayList<>();
                for (int i = 0; i < nItems; i++) key.add(groupKey(o[i]));
                seen.putIfAbsent(key, o);
            }
            out = new ArrayList<>(seen.values());
        }

        // 7. ORDER BY (stable sort; empty values go last)
        if (!order.isEmpty()) {
            out.sort((a, b) -> {
                for (int k = 0; k < order.size(); k++) {
                    Object x = a[nItems + k], y = b[nItems + k];
                    int c;
                    if (x == null && y == null) c = 0;
                    else if (x == null) return 1;
                    else if (y == null) return -1;
                    else c = compare(x, y);
                    if (c != 0) return order.get(k).desc() ? -c : c;
                }
                return 0;
            });
        }

        // 8. OFFSET / LIMIT, then the display cap
        int from = s.offset() == null ? 0 : (int) Math.min(out.size(), s.offset());
        int to = s.limit() == null ? out.size() : (int) Math.min(out.size(), from + s.limit());
        List<Object[]> picked = out.subList(from, Math.max(from, to));
        boolean truncated = picked.size() > MAX_ROWS_RETURNED;
        List<List<Object>> result = new ArrayList<>();
        for (int i = 0; i < Math.min(picked.size(), MAX_ROWS_RETURNED); i++) {
            List<Object> row = new ArrayList<>(nItems);
            for (int c = 0; c < nItems; c++) row.add(clean(picked.get(i)[c]));
            result.add(row);
        }
        return new QueryResult(headings, result, picked.size(), truncated, worked);
    }

    private List<Object[]> join(Scope scope, List<Object[]> left, Binding right, Join j) {
        List<Object[]> out = new ArrayList<>();
        Table rt = right.table;
        int rw = rt.schema.columns.size();
        // Try a hash join: ON x = y where one side is in the new table and the other is not
        Integer leftIdx = null, rightIdx = null;
        if (j.on() instanceof Binary b && b.op().equals("=") && b.left() instanceof ColumnRef a && b.right() instanceof ColumnRef c) {
            int ia = scope.resolve(a), ic = scope.resolve(c);
            boolean aRight = ia >= right.offset && ia < right.offset + rw;
            boolean cRight = ic >= right.offset && ic < right.offset + rw;
            if (aRight && !cRight && ic < right.offset) { rightIdx = ia - right.offset; leftIdx = ic; }
            else if (cRight && !aRight && ia < right.offset) { rightIdx = ic - right.offset; leftIdx = ia; }
        }
        if (leftIdx != null) {
            Map<Object, List<Object[]>> index = new HashMap<>();
            for (Object[] r : rt.rows()) {
                Object k = groupKey(r[rightIdx]);
                if (k != null) index.computeIfAbsent(k, x -> new ArrayList<>()).add(r);
            }
            for (Object[] l : left) {
                Object k = groupKey(l[leftIdx]);
                List<Object[]> matches = k == null ? null : index.get(k);
                if (matches == null || matches.isEmpty()) {
                    if (j.left()) out.add(l.clone());
                    continue;
                }
                for (Object[] r : matches) {
                    Object[] w = l.clone();
                    System.arraycopy(r, 0, w, right.offset, rw);
                    out.add(w);
                    budget(1);
                }
            }
        } else {
            for (Object[] l : left) {
                boolean any = false;
                for (Object[] r : rt.rows()) {
                    budget(1);
                    Object[] w = l.clone();
                    System.arraycopy(r, 0, w, right.offset, rw);
                    if (truthy(eval(j.on(), scope, w, null))) { out.add(w); any = true; }
                }
                if (!any && j.left()) out.add(l.clone());
            }
        }
        worked += out.size();
        return out;
    }

    private void budget(long n) {
        worked += n;
        if (worked > MAX_ROWS_WORKED)
            throw new SqlException("This query would combine too many rows (over " + MAX_ROWS_WORKED
                    + "). Check that every JOIN has the right ON condition.");
    }

    // ------------------------------------------------------------------ aliases in ORDER BY / HAVING
    private Expr replaceAlias(Expr e, List<SelectItem> original, List<SelectItem> expanded, boolean allowPosition) {
        if (allowPosition && e instanceof Literal l && l.value() instanceof Long n) {
            if (n < 1 || n > expanded.size()) throw new SqlException("ORDER BY " + n + " is not a column number in this query.");
            return expanded.get((int) (n - 1)).expr();
        }
        if (e instanceof ColumnRef c && c.table() == null) {
            for (SelectItem it : original)
                if (it.alias() != null && it.alias().equalsIgnoreCase(c.column())) return it.expr();
            return e;
        }
        if (e instanceof Binary b) return new Binary(b.op(), replaceAlias(b.left(), original, expanded, false),
                replaceAlias(b.right(), original, expanded, false));
        if (e instanceof Unary u) return new Unary(u.op(), replaceAlias(u.expr(), original, expanded, false));
        return e;
    }

    // ------------------------------------------------------------------ evaluating expressions
    private Object eval(Expr e, Scope scope, Object[] row, List<Object[]> group) {
        if (e instanceof Literal l) return l.value();
        if (e instanceof ColumnRef c) return row[scope.resolve(c)];
        if (e instanceof Func f) return f.aggregate() ? aggregate(f, scope, group) : function(f, scope, row, group);
        if (e instanceof Unary u) {
            Object v = eval(u.expr(), scope, row, group);
            if (u.op().equals("NOT")) return v == null ? null : !truthy(v);
            if (v == null) return null;
            Object num = toNumber(v);
            if (num instanceof Long x) return -x;
            if (num instanceof Double d) return -d;
            throw new SqlException("Cannot put a minus sign in front of text.");
        }
        if (e instanceof Binary b) return binary(b, scope, row, group);
        if (e instanceof InList in) {
            Object v = eval(in.expr(), scope, row, group);
            if (v == null) return null;
            boolean hit = false;
            for (Expr x : in.values()) {
                Object w = eval(x, scope, row, group);
                if (w != null && compare(v, w) == 0) { hit = true; break; }
            }
            return in.not() != hit;
        }
        if (e instanceof Between bt) {
            Object v = eval(bt.expr(), scope, row, group);
            Object lo = eval(bt.low(), scope, row, group), hi = eval(bt.high(), scope, row, group);
            if (v == null || lo == null || hi == null) return null;
            boolean in = compare(v, lo) >= 0 && compare(v, hi) <= 0;
            return bt.not() != in;
        }
        if (e instanceof IsNull n) return (eval(n.expr(), scope, row, group) == null) != n.not();
        if (e instanceof Like lk) {
            Object v = eval(lk.expr(), scope, row, group), pat = eval(lk.pattern(), scope, row, group);
            if (v == null || pat == null) return null;
            return likePattern(String.valueOf(pat)).matcher(asText(v)).matches() != lk.not();
        }
        Case c = (Case) e;
        for (int i = 0; i < c.whens().size(); i++)
            if (truthy(eval(c.whens().get(i), scope, row, group))) return eval(c.thens().get(i), scope, row, group);
        return c.otherwise() == null ? null : eval(c.otherwise(), scope, row, group);
    }

    private Object binary(Binary b, Scope scope, Object[] row, List<Object[]> group) {
        String op = b.op();
        if (op.equals("AND")) {
            Object l = eval(b.left(), scope, row, group);
            if (l != null && !truthy(l)) return false;
            Object r = eval(b.right(), scope, row, group);
            if (r != null && !truthy(r)) return false;
            return l == null || r == null ? null : true;
        }
        if (op.equals("OR")) {
            Object l = eval(b.left(), scope, row, group);
            if (l != null && truthy(l)) return true;
            Object r = eval(b.right(), scope, row, group);
            if (r != null && truthy(r)) return true;
            return l == null || r == null ? null : false;
        }
        Object l = eval(b.left(), scope, row, group), r = eval(b.right(), scope, row, group);
        if (l == null || r == null) return null;
        switch (op) {
            case "=": return compare(l, r) == 0;
            case "!=": return compare(l, r) != 0;
            case "<": return compare(l, r) < 0;
            case "<=": return compare(l, r) <= 0;
            case ">": return compare(l, r) > 0;
            case ">=": return compare(l, r) >= 0;
            case "||": return asText(l) + asText(r);
            default: break;
        }
        Object x = toNumber(l), y = toNumber(r);
        if (!(x instanceof Number) || !(y instanceof Number))
            throw new SqlException("Cannot do '" + op + "' with text. Both sides must be numbers.");
        if (op.equals("/")) {
            double d = ((Number) y).doubleValue();
            return d == 0 ? null : ((Number) x).doubleValue() / d;
        }
        if (x instanceof Long a && y instanceof Long c) {
            switch (op) {
                case "+": return a + c;
                case "-": return a - c;
                case "*": return a * c;
                case "%": return c == 0 ? null : a % c;
                default: break;
            }
        }
        double a = ((Number) x).doubleValue(), c = ((Number) y).doubleValue();
        switch (op) {
            case "+": return a + c;
            case "-": return a - c;
            case "*": return a * c;
            case "%": return c == 0 ? null : a % c;
            default: throw new SqlException("Unknown operator " + op);
        }
    }

    private Object function(Func f, Scope scope, Object[] row, List<Object[]> group) {
        List<Object> a = new ArrayList<>();
        for (Expr x : f.args()) a.add(eval(x, scope, row, group));
        String n = f.name();
        if (n.equals("COALESCE")) {
            for (Object v : a) if (v != null) return v;
            return null;
        }
        if (n.equals("CONCAT")) {
            StringBuilder sb = new StringBuilder();
            for (Object v : a) if (v != null) sb.append(asText(v));
            return sb.toString();
        }
        if (a.isEmpty()) throw new SqlException(n + "() needs a value inside the brackets.");
        Object v = a.get(0);
        if (v == null) return null;
        switch (n) {
            case "UPPER": return asText(v).toUpperCase();
            case "LOWER": return asText(v).toLowerCase();
            case "TRIM": return asText(v).trim();
            case "LENGTH": return (long) asText(v).length();
            case "ABS": {
                Object x = toNumber(v);
                if (x instanceof Long l) return Math.abs(l);
                if (x instanceof Double d) return Math.abs(d);
                throw new SqlException("ABS() needs a number.");
            }
            case "ROUND": {
                Object x = toNumber(v);
                if (!(x instanceof Number num)) throw new SqlException("ROUND() needs a number.");
                int places = a.size() > 1 && a.get(1) instanceof Number p ? p.intValue() : 0;
                BigDecimal bd = BigDecimal.valueOf(num.doubleValue()).setScale(places, RoundingMode.HALF_UP);
                return places <= 0 ? (Object) bd.longValue() : (Object) bd.doubleValue();
            }
            case "YEAR": case "MONTH": {
                String s = asText(v);
                if (!s.matches("\\d{4}-\\d{2}.*")) throw new SqlException(n + "() needs a date like 2026-03-15.");
                return n.equals("YEAR") ? Long.parseLong(s.substring(0, 4)) : Long.parseLong(s.substring(5, 7));
            }
            case "SUBSTR": {
                String s = asText(v);
                int start = a.size() > 1 && a.get(1) instanceof Number p ? p.intValue() : 1;
                int len = a.size() > 2 && a.get(2) instanceof Number q ? q.intValue() : s.length();
                int from = Math.max(0, Math.min(s.length(), start - 1));
                return s.substring(from, Math.min(s.length(), from + Math.max(0, len)));
            }
            default: throw new SqlException("Unknown function " + n + "().");
        }
    }

    private Object aggregate(Func f, Scope scope, List<Object[]> group) {
        if (group == null) throw new SqlException(f.name() + "() can only be used in SELECT, HAVING or ORDER BY.");
        if (f.star()) return (long) group.size();
        Expr arg = f.args().get(0);
        if (Ast.hasAggregate(arg)) throw new SqlException("You cannot put one of COUNT / SUM / AVG / MIN / MAX inside another.");
        List<Object> vals = new ArrayList<>();
        Set<Object> seen = new HashSet<>();
        for (Object[] r : group) {
            Object v = eval(arg, scope, r, null);
            if (v == null) continue;
            if (f.distinct() && !seen.add(groupKey(v))) continue;
            vals.add(v);
        }
        switch (f.name()) {
            case "COUNT": return (long) vals.size();
            case "MIN": case "MAX": {
                Object best = null;
                for (Object v : vals)
                    if (best == null || (f.name().equals("MIN") ? compare(v, best) < 0 : compare(v, best) > 0)) best = v;
                return best;
            }
            default: {   // SUM, AVG
                if (vals.isEmpty()) return null;
                boolean allLong = true;
                long ls = 0;
                double ds = 0;
                for (Object v : vals) {
                    Object x = toNumber(v);
                    if (!(x instanceof Number num)) throw new SqlException(f.name() + "() needs numbers, but found text '" + v + "'.");
                    if (x instanceof Long l) ls += l; else allLong = false;
                    ds += num.doubleValue();
                }
                if (f.name().equals("SUM")) return allLong ? (Object) ls : (Object) ds;
                return ds / vals.size();
            }
        }
    }

    // ------------------------------------------------------------------ value helpers
    public static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0;
        return !String.valueOf(v).isEmpty();
    }

    /** Numbers compare as numbers, text compares without caring about capital letters. */
    public static int compare(Object a, Object b) {
        Object x = a instanceof Boolean ba ? (Object) (ba ? 1L : 0L) : a;
        Object y = b instanceof Boolean bb ? (Object) (bb ? 1L : 0L) : b;
        if (x instanceof Number || y instanceof Number) {
            Object nx = toNumber(x), ny = toNumber(y);
            if (nx instanceof Number p && ny instanceof Number q) return Double.compare(p.doubleValue(), q.doubleValue());
        }
        return asText(x).compareToIgnoreCase(asText(y));
    }

    static Object toNumber(Object v) {
        if (v instanceof Number) return v;
        if (v instanceof String s) {
            String t = s.trim();
            try {
                if (t.matches("-?\\d{1,18}")) return Long.parseLong(t);
                if (t.matches("-?\\d*\\.\\d+|-?\\d+\\.\\d*")) return Double.parseDouble(t);
            } catch (NumberFormatException ignored) { }
        }
        return v;
    }

    static String asText(Object v) {
        if (v instanceof Double d) return formatNumber(d);
        return String.valueOf(v);
    }

    /** Key used for GROUP BY / DISTINCT / joins: numbers by value, text ignoring case. */
    static Object groupKey(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.rint(d) ? (Object) (long) d : (Object) d;
        }
        if (v instanceof Boolean b) return b ? 1L : 0L;
        return String.valueOf(v).toLowerCase();
    }

    private static Object clean(Object v) {
        if (v instanceof Boolean b) return b ? 1L : 0L;
        if (v instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) return null;
            return BigDecimal.valueOf(d).setScale(4, RoundingMode.HALF_UP).doubleValue();
        }
        return v;
    }

    public static String formatNumber(Object v) {
        if (v instanceof Double d) {
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf(d.longValue());
            return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
        }
        return String.valueOf(v);
    }

    private static final Map<String, Pattern> LIKE_CACHE = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Pattern> e) { return size() > 200; }
    });

    static Pattern likePattern(String like) {
        return LIKE_CACHE.computeIfAbsent(like, k -> {
            StringBuilder sb = new StringBuilder();
            for (char c : k.toCharArray()) {
                if (c == '%') sb.append(".*");
                else if (c == '_') sb.append('.');
                else sb.append(Pattern.quote(String.valueOf(c)));
            }
            return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
        });
    }

    private static void makeUnique(List<String> h) {
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < h.size(); i++) {
            String k = h.get(i).toLowerCase();
            int n = seen.merge(k, 1, Integer::sum);
            if (n > 1) h.set(i, h.get(i) + "_" + n);
        }
    }

    // ------------------------------------------------------------------ "did you mean ...?"
    private String suggestTable(String wrong) {
        String best = null;
        int bestD = 3;
        for (Table t : db.tables()) {
            int d = editDistance(wrong.toLowerCase(), t.schema.name.toLowerCase());
            if (d < bestD) { bestD = d; best = t.schema.name; }
        }
        String all = String.join(", ", db.tables().stream().map(t -> t.schema.name).toList());
        return best != null ? " Did you mean '" + best + "'?" : " Tables: " + all + ".";
    }

    private String suggestColumn(String wrong, List<Binding> where) {
        String best = null;
        int bestD = 3;
        for (Binding b : where)
            for (Column c : b.table.schema.columns) {
                int d = editDistance(wrong.toLowerCase(), c.name.toLowerCase());
                if (d < bestD) { bestD = d; best = c.name; }
            }
        return best != null ? " Did you mean '" + best + "'?" : "";
    }

    public static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }
}
