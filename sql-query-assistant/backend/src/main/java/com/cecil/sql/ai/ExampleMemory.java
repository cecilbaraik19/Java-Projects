package com.cecil.sql.ai;

import com.cecil.sql.db.SqlExecutor;
import com.cecil.sql.util.Clock;
import com.cecil.sql.util.Json;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Learning from corrections. When a person fixes the SQL for a question and presses "Teach", the question is
 * stored as a TEMPLATE: its values and numbers become slots.
 *
 *   taught:  "fees paid by hostellers from Ranchi"  ->  SQL with 'Ranchi' replaced by {{0}}
 *   later:   "fees paid by hostellers from Dhanbad" ->  same words, same kind of slot -> SQL with 'Dhanbad'
 *
 * Similarity = Jaccard index of the two word sets (shared words / all words). The slots must be of the same
 * kind (both a city, both a number). At 75% or more the taught SQL is reused before the rule engine runs.
 */
public final class ExampleMemory {

    public static final double MATCH_AT = 0.75;

    public static final class Example implements Json.JsonWritable {
        public final String id, db, question, template, createdBy, createdAt;
        public final List<String> tokens, kinds;
        public int uses;

        public Example(String id, String db, String question, String template, List<String> tokens, List<String> kinds,
                       String createdBy, String createdAt, int uses) {
            this.id = id; this.db = db; this.question = question; this.template = template;
            this.tokens = tokens; this.kinds = kinds; this.createdBy = createdBy; this.createdAt = createdAt; this.uses = uses;
        }

        @Override public Object toJson() {
            return Json.map("id", id, "db", db, "question", question, "template", template, "tokens", tokens,
                    "kinds", kinds, "createdBy", createdBy, "createdAt", createdAt, "uses", uses);
        }

        public static Example fromJson(Map<String, Object> m) {
            return new Example(Json.str(m, "id"), Json.str(m, "db"), Json.str(m, "question"), Json.str(m, "template"),
                    strings(Json.list(m, "tokens")), strings(Json.list(m, "kinds")), Json.str(m, "createdBy"),
                    Json.str(m, "createdAt"), (int) Json.num(m, "uses", 0));
        }
    }

    public record Hit(String sql, double similarity, Example example) {}

    private final List<Example> examples = new ArrayList<>();
    private final Runnable onChange;

    public ExampleMemory(List<Example> start, Runnable onChange) {
        if (start != null) examples.addAll(start);
        this.onChange = onChange == null ? () -> { } : onChange;
    }

    public synchronized List<Example> all() { return new ArrayList<>(examples); }

    public synchronized List<Example> forDb(String db) {
        List<Example> l = new ArrayList<>();
        for (Example e : examples) if (e.db.equals(db)) l.add(e);
        return l;
    }

    /** Stores a corrected question. Returns the new example. */
    public Example learn(Lexicon lex, String question, String correctSql, String user) {
        NlToSql.Masked m = NlToSql.mask(lex, question);
        String template = correctSql.trim();
        for (int i = 0; i < m.values().size(); i++) template = replaceLiteral(template, m.values().get(i), "{{" + i + "}}");
        Example e = new Example(UUID.randomUUID().toString().substring(0, 8), lex.db.id, question.trim(), template,
                m.tokens(), m.kinds(), user, Clock.now().toString(), 0);
        synchronized (this) {
            examples.removeIf(x -> x.db.equals(e.db) && x.question.equalsIgnoreCase(e.question));
            examples.add(e);
        }
        onChange.run();
        return e;
    }

    public synchronized boolean remove(String id) {
        boolean r = examples.removeIf(e -> e.id.equals(id));
        if (r) onChange.run();
        return r;
    }

    /** The best taught example for this question, if it is similar enough. */
    public Hit match(Lexicon lex, String question) {
        NlToSql.Masked m = NlToSql.mask(lex, question);
        Example best = null;
        double bestSim = 0;
        synchronized (this) {
            for (Example e : examples) {
                if (!e.db.equals(lex.db.id) || !e.kinds.equals(m.kinds())) continue;
                double sim = jaccard(e.tokens, m.tokens());
                if (sim > bestSim) { bestSim = sim; best = e; }
            }
        }
        if (best == null || bestSim < MATCH_AT) return null;
        String sql = best.template;
        for (int i = 0; i < m.values().size(); i++) sql = sql.replace("{{" + i + "}}", literal(m.values().get(i)));
        if (sql.contains("{{")) return null;
        synchronized (this) { best.uses++; }
        onChange.run();
        return new Hit(sql, bestSim, best);
    }

    public static double jaccard(List<String> a, List<String> b) {
        Set<String> x = new HashSet<>(a), y = new HashSet<>(b);
        if (x.isEmpty() && y.isEmpty()) return 1;
        Set<String> inter = new HashSet<>(x);
        inter.retainAll(y);
        Set<String> union = new HashSet<>(x);
        union.addAll(y);
        return inter.size() / (double) union.size();
    }

    static String literal(Object v) {
        if (v instanceof Number) return SqlExecutor.formatNumber(v);
        return "'" + String.valueOf(v).replace("'", "''") + "'";
    }

    /** Replaces the first place the value appears as a literal in the SQL. */
    static String replaceLiteral(String sql, Object value, String slot) {
        if (value instanceof Number) {
            Matcher mm = Pattern.compile("(?<![\\w.'])" + Pattern.quote(SqlExecutor.formatNumber(value)) + "(?![\\w.])").matcher(sql);
            return mm.find() ? sql.substring(0, mm.start()) + slot + sql.substring(mm.end()) : sql;
        }
        String lit = "'" + String.valueOf(value).replace("'", "''") + "'";
        int at = sql.toLowerCase().indexOf(lit.toLowerCase());
        return at < 0 ? sql : sql.substring(0, at) + slot + sql.substring(at + lit.length());
    }

    private static List<String> strings(List<Object> l) {
        List<String> out = new ArrayList<>();
        for (Object o : l) out.add(String.valueOf(o));
        return out;
    }
}
