package com.cecil.sql.db;

import com.cecil.sql.db.Ast.*;
import com.cecil.sql.db.SqlLexer.Kind;
import com.cecil.sql.db.SqlLexer.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Step 2 of running SQL: a recursive-descent parser. It reads the tokens and builds a {@link Select} tree.
 * Only ONE SELECT statement is accepted. Anything that changes data (INSERT, UPDATE, DELETE, DROP ...) is
 * refused here, before anything runs – so the database is read-only by design.
 *
 * Grammar (simplified):
 *   select    := SELECT [DISTINCT] item {, item} FROM table {join} [WHERE expr] [GROUP BY expr {, expr}]
 *                [HAVING expr] [ORDER BY expr [ASC|DESC] {, ...}] [LIMIT n [OFFSET m]] [;]
 *   expr      := and {OR and}          and := not {AND not}          not := NOT not | predicate
 *   predicate := sum [(= | != | < | ...) sum | [NOT] LIKE | [NOT] IN (...) | [NOT] BETWEEN .. AND .. | IS [NOT] NULL]
 *   sum       := product {(+ | - | ||) product}        product := unary {(* | / | %) unary}
 *   primary   := number | 'text' | NULL | (expr) | function(args) | CASE ... END | column | table.column
 */
public final class SqlParser {

    static final Set<String> BLOCKED = Set.of("INSERT", "UPDATE", "DELETE", "DROP", "CREATE", "ALTER", "TRUNCATE",
            "REPLACE", "MERGE", "GRANT", "REVOKE", "EXEC", "EXECUTE", "CALL", "ATTACH", "PRAGMA", "SET", "INTO");
    static final Set<String> FUNCTIONS = Set.of("COUNT", "SUM", "AVG", "MIN", "MAX", "ROUND", "UPPER", "LOWER",
            "LENGTH", "ABS", "YEAR", "MONTH", "COALESCE", "IFNULL", "SUBSTR", "SUBSTRING", "CONCAT", "TRIM");

    private final List<Token> t;
    private int p;
    private int depth;

    private SqlParser(List<Token> tokens) { this.t = tokens; }

    public static Select parse(String sql) {
        if (sql == null || sql.isBlank()) throw new SqlException("The query is empty.");
        if (sql.length() > 5000) throw new SqlException("The query is too long (5000 characters at most).");
        SqlParser parser = new SqlParser(SqlLexer.tokenize(sql));
        return parser.statement();
    }

    // ------------------------------------------------------------------ statement
    private Select statement() {
        Token first = peek();
        for (Token tk : t) {
            if (tk.kind() == Kind.KEYWORD && BLOCKED.contains(tk.text().toUpperCase()))
                throw new SqlException("Only SELECT (reading) queries are allowed. '" + tk.text().toUpperCase()
                        + "' would change the database, so it is blocked.", tk.pos());
            if (tk.is("UNION")) throw new SqlException("UNION is not supported. Run the two queries separately.", tk.pos());
            if (tk.is("WITH")) throw new SqlException("WITH (common table expressions) is not supported.", tk.pos());
            if (tk.is("RIGHT") || tk.is("FULL") || tk.is("CROSS"))
                throw new SqlException("Only JOIN, INNER JOIN and LEFT JOIN are supported.", tk.pos());
        }
        if (!first.is("SELECT")) throw new SqlException("A query must start with SELECT.", first.pos());
        Select s = select();
        if (peek().is(";")) p++;
        if (peek().kind() != Kind.EOF) {
            if (peek().is("SELECT")) throw new SqlException("Only one query at a time, please.", peek().pos());
            throw new SqlException("Did not expect '" + peek().text() + "' here.", peek().pos());
        }
        return s;
    }

    private Select select() {
        expect("SELECT");
        boolean distinct = accept("DISTINCT");
        List<SelectItem> items = new ArrayList<>();
        do items.add(selectItem()); while (accept(","));
        if (!peek().is("FROM")) throw new SqlException("Expected FROM after the column list.", peek().pos());
        p++;
        TableRef from = tableRef();
        List<Join> joins = new ArrayList<>();
        while (peek().is("JOIN") || peek().is("INNER") || peek().is("LEFT")) {
            boolean left = false;
            if (accept("LEFT")) { left = true; accept("OUTER"); }
            else accept("INNER");
            expect("JOIN");
            TableRef tr = tableRef();
            expect("ON");
            joins.add(new Join(tr, left, expr()));
        }
        if (peek().is(",")) throw new SqlException("Use JOIN ... ON ... instead of listing tables with commas.", peek().pos());
        Expr where = null;
        if (accept("WHERE")) where = expr();
        List<Expr> group = new ArrayList<>();
        if (accept("GROUP")) {
            expect("BY");
            do group.add(expr()); while (accept(","));
        }
        Expr having = null;
        if (accept("HAVING")) having = expr();
        List<OrderItem> order = new ArrayList<>();
        if (accept("ORDER")) {
            expect("BY");
            do {
                Expr e = expr();
                boolean desc = false;
                if (accept("DESC")) desc = true;
                else accept("ASC");
                order.add(new OrderItem(e, desc));
            } while (accept(","));
        }
        Long limit = null, offset = null;
        if (accept("LIMIT")) {
            limit = wholeNumber();
            if (accept(",")) { offset = limit; limit = wholeNumber(); }   // MySQL style LIMIT 10, 5
            else if (accept("OFFSET")) offset = wholeNumber();
        }
        return new Select(distinct, items, from, joins, where, group, having, order, limit, offset);
    }

    private SelectItem selectItem() {
        if (accept("*")) return new SelectItem(null, null, true, null);
        if (peek().kind() == Kind.IDENT && peek(1).is(".") && peek(2).is("*")) {
            String tb = next().text();
            p += 2;
            return new SelectItem(null, null, true, tb);
        }
        Expr e = expr();
        String alias = null;
        if (accept("AS")) alias = name("a column name after AS");
        else if (peek().kind() == Kind.IDENT) alias = next().text();
        else if (peek().kind() == Kind.STRING) alias = next().text();
        return new SelectItem(e, alias, false, null);
    }

    private TableRef tableRef() {
        Token tk = peek();
        if (tk.is("(")) throw new SqlException("Sub-queries are not supported. Use JOIN instead.", tk.pos());
        String table = name("a table name");
        String alias = null;
        if (accept("AS")) alias = name("a short name after AS");
        else if (peek().kind() == Kind.IDENT) alias = next().text();
        return new TableRef(table, alias, tk.pos());
    }

    // ------------------------------------------------------------------ expressions
    private Expr expr() {
        if (++depth > 60) throw new SqlException("The query is nested too deeply.");
        try {
            Expr e = and();
            while (accept("OR")) e = new Binary("OR", e, and());
            return e;
        } finally { depth--; }
    }

    private Expr and() {
        Expr e = not();
        while (accept("AND")) e = new Binary("AND", e, not());
        return e;
    }

    private Expr not() {
        if (accept("NOT")) return new Unary("NOT", not());
        return predicate();
    }

    private Expr predicate() {
        Expr left = sum();
        Token tk = peek();
        for (String op : new String[]{"=", "!=", "<>", "<=", ">=", "<", ">"}) {
            if (tk.is(op)) {
                p++;
                return new Binary(op.equals("<>") ? "!=" : op, left, sum());
            }
        }
        boolean not = false;
        if (tk.is("NOT") && (peek(1).is("LIKE") || peek(1).is("IN") || peek(1).is("BETWEEN"))) { not = true; p++; }
        if (accept("LIKE")) return new Like(left, sum(), not);
        if (accept("IN")) {
            expect("(");
            if (peek().is("SELECT")) throw new SqlException("Sub-queries are not supported.", peek().pos());
            List<Expr> vals = new ArrayList<>();
            do vals.add(sum()); while (accept(","));
            expect(")");
            return new InList(left, vals, not);
        }
        if (accept("BETWEEN")) {
            Expr lo = sum();
            expect("AND");
            return new Between(left, lo, sum(), not);
        }
        if (accept("IS")) {
            boolean n = accept("NOT");
            expect("NULL");
            return new IsNull(left, n);
        }
        return left;
    }

    private Expr sum() {
        Expr e = product();
        while (peek().is("+") || peek().is("-") || peek().is("||")) {
            String op = next().text();
            e = new Binary(op, e, product());
        }
        return e;
    }

    private Expr product() {
        Expr e = unary();
        while (peek().is("*") || peek().is("/") || peek().is("%")) {
            String op = next().text();
            e = new Binary(op, e, unary());
        }
        return e;
    }

    private Expr unary() {
        if (accept("-")) return new Unary("-", unary());
        if (accept("+")) return unary();
        return primary();
    }

    private Expr primary() {
        Token tk = peek();
        switch (tk.kind()) {
            case NUMBER -> {
                p++;
                try {
                    if (tk.text().contains(".")) return new Literal(Double.parseDouble(tk.text()));
                    return new Literal(Long.parseLong(tk.text()));
                } catch (NumberFormatException e) {
                    throw new SqlException("'" + tk.text() + "' is not a valid number.", tk.pos());
                }
            }
            case STRING -> { p++; return new Literal(tk.text()); }
            case KEYWORD -> {
                if (accept("NULL")) return new Literal(null);
                if (accept("TRUE")) return new Literal(1L);
                if (accept("FALSE")) return new Literal(0L);
                if (tk.is("CASE")) return caseExpr();
                if (tk.is("SELECT")) throw new SqlException("Sub-queries are not supported. Use JOIN instead.", tk.pos());
                throw new SqlException("Did not expect the word " + tk.text().toUpperCase() + " here.", tk.pos());
            }
            case SYMBOL -> {
                if (accept("(")) {
                    if (peek().is("SELECT")) throw new SqlException("Sub-queries are not supported. Use JOIN instead.", peek().pos());
                    Expr e = expr();
                    expect(")");
                    return e;
                }
                throw new SqlException("Did not expect '" + tk.text() + "' here.", tk.pos());
            }
            case IDENT -> {
                p++;
                if (peek().is("(")) return function(tk);
                if (accept(".")) {
                    Token col = next();
                    if (col.kind() != Kind.IDENT) throw new SqlException("Expected a column name after '" + tk.text() + ".'.", col.pos());
                    return new ColumnRef(tk.text(), col.text(), tk.pos());
                }
                return new ColumnRef(null, tk.text(), tk.pos());
            }
            default -> throw new SqlException("The query ended too early.", tk.pos());
        }
    }

    private Expr function(Token nameTok) {
        String fn = nameTok.text().toUpperCase();
        if (!FUNCTIONS.contains(fn)) throw new SqlException("Unknown function " + fn + "(). Supported: " + String.join(", ",
                new java.util.TreeSet<>(FUNCTIONS)) + ".", nameTok.pos());
        expect("(");
        if (fn.equals("COUNT") && accept("*")) {
            expect(")");
            return new Func("COUNT", List.of(), false, true);
        }
        boolean distinct = accept("DISTINCT");
        List<Expr> args = new ArrayList<>();
        if (!peek().is(")")) do args.add(expr()); while (accept(","));
        expect(")");
        if (fn.equals("SUBSTRING")) fn = "SUBSTR";
        if (fn.equals("IFNULL")) fn = "COALESCE";
        if (SqlExecutor.AGGREGATES.contains(fn) && args.size() != 1)
            throw new SqlException(fn + "() takes exactly one value.", nameTok.pos());
        return new Func(fn, args, distinct, false);
    }

    private Expr caseExpr() {
        expect("CASE");
        List<Expr> whens = new ArrayList<>(), thens = new ArrayList<>();
        while (accept("WHEN")) {
            whens.add(expr());
            expect("THEN");
            thens.add(expr());
        }
        if (whens.isEmpty()) throw new SqlException("CASE needs at least one WHEN.", peek().pos());
        Expr otherwise = null;
        if (accept("ELSE")) otherwise = expr();
        expect("END");
        return new Case(whens, thens, otherwise);
    }

    // ------------------------------------------------------------------ small helpers
    private Token peek() { return t.get(p); }
    private Token peek(int k) { return t.get(Math.min(p + k, t.size() - 1)); }
    private Token next() { return t.get(p++); }

    private boolean accept(String s) {
        if (peek().is(s)) { p++; return true; }
        return false;
    }

    private void expect(String s) {
        Token tk = peek();
        if (!tk.is(s)) {
            String got = tk.kind() == Kind.EOF ? "the end of the query" : "'" + tk.text() + "'";
            throw new SqlException("Expected " + s + " but found " + got + ".", tk.pos());
        }
        p++;
    }

    private String name(String what) {
        Token tk = next();
        if (tk.kind() != Kind.IDENT) {
            String got = tk.kind() == Kind.EOF ? "the end of the query" : "'" + tk.text() + "'";
            throw new SqlException("Expected " + what + " but found " + got + ".", tk.pos());
        }
        return tk.text();
    }

    private long wholeNumber() {
        Token tk = next();
        if (tk.kind() != Kind.NUMBER || tk.text().contains("."))
            throw new SqlException("LIMIT and OFFSET need a whole number.", tk.pos());
        return Long.parseLong(tk.text());
    }
}
