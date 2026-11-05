package com.cecil.sql.ai;

import com.cecil.sql.db.*;
import com.cecil.sql.util.Json;

import java.util.*;
import java.util.function.Function;

/**
 * Measures the English-to-SQL engine with "execution accuracy": run the engine's SQL and the gold SQL and
 * check that they give the same rows. Extra columns in the engine's answer are allowed (it often shows a few
 * helpful columns); row order is ignored; numbers are compared to 2 decimal places.
 */
public final class Evaluator {
    private Evaluator() {}

    public record Outcome(String db, String question, String goldSql, String sql, boolean pass, String reason, double confidence) {
        public Map<String, Object> toJson() {
            return Json.map("db", db, "question", question, "goldSql", goldSql, "sql", sql, "pass", pass,
                    "reason", reason, "confidence", Math.round(confidence * 100) / 100.0);
        }
    }

    public record Report(String name, int total, int passed, List<Outcome> outcomes) {
        public double accuracy() { return total == 0 ? 0 : passed / (double) total; }
        public Map<String, Object> toJson() {
            List<Object> l = new ArrayList<>();
            for (Outcome o : outcomes) l.add(o.toJson());
            return Json.map("name", name, "total", total, "passed", passed,
                    "accuracy", Math.round(accuracy() * 1000) / 1000.0, "outcomes", l);
        }
    }

    public static Report run(String name, List<TestQuestions.Case> cases, Function<String, Database> dbs, Function<String, Lexicon> lexicons) {
        List<Outcome> outs = new ArrayList<>();
        int passed = 0;
        for (TestQuestions.Case c : cases) {
            Database db = dbs.apply(c.db());
            NlToSql.Result r = NlToSql.translate(lexicons.apply(c.db()), c.question());
            String reason;
            boolean ok = false;
            if (r.sql == null) reason = "No SQL: " + r.error;
            else {
                try {
                    QueryResult gold = new SqlExecutor(db, null).run(c.goldSql());
                    QueryResult got = new SqlExecutor(db, null).run(r.sql);
                    reason = compare(gold, got);
                    ok = reason == null;
                    if (ok) reason = "Same " + gold.totalRows + " row(s)";
                } catch (SqlException e) {
                    reason = "SQL error: " + e.getMessage();
                }
            }
            if (ok) passed++;
            outs.add(new Outcome(c.db(), c.question(), c.goldSql(), r.sql, ok, reason, r.confidence));
        }
        return new Report(name, cases.size(), passed, outs);
    }

    /** null when the results match, otherwise a short reason. */
    public static String compare(QueryResult gold, QueryResult got) {
        if (gold.totalRows != got.totalRows) return "Expected " + gold.totalRows + " row(s), got " + got.totalRows;
        boolean[] usedCol = new boolean[got.columns.size()];
        for (int g = 0; g < gold.columns.size(); g++) {
            List<String> want = column(gold, g);
            boolean found = false;
            for (int k = 0; k < got.columns.size(); k++) {
                if (usedCol[k]) continue;
                if (column(got, k).equals(want)) { usedCol[k] = true; found = true; break; }
            }
            if (!found) return "Column '" + gold.columns.get(g) + "' has different values";
        }
        return null;
    }

    private static List<String> column(QueryResult r, int c) {
        List<String> l = new ArrayList<>();
        for (List<Object> row : r.rows) {
            Object v = row.get(c);
            if (v instanceof Number n) l.add(String.format(Locale.ROOT, "%.2f", n.doubleValue()));
            else l.add(String.valueOf(v).toLowerCase());
        }
        Collections.sort(l);
        return l;
    }
}
