package com.cecil.sql.service;

import com.cecil.sql.ai.*;
import com.cecil.sql.db.*;
import com.cecil.sql.model.*;
import com.cecil.sql.store.DataStore;
import com.cecil.sql.util.Clock;
import com.cecil.sql.util.Csv;
import com.cecil.sql.util.Json;

import java.util.*;

/**
 * The main flow when someone asks a question:
 *
 *   1. learned examples  – a taught question that matches (75%+ similar) is reused first
 *   2. Claude (optional)  – if the admin chose "always", or "when not sure" and step 3 is not sure
 *   3. offline engine     – NlToSql (rules + schema dictionary)
 *   4. safety + run       – parse (SELECT only), check private columns, run with limits
 *   5. explain + history  – plain-English steps, saved to history for feedback and export
 */
public final class QueryService {
    public static final int MAX_HISTORY = 3000;

    private final DataStore store;
    private final DatabaseService dbs;
    private final ExampleMemory memory;
    private final ClaudeSql claude;
    private Settings settings;
    private final List<HistoryItem> history;
    private final List<SavedQuery> saved;

    public QueryService(DataStore store, DatabaseService dbs, ClaudeSql claude, Settings settings) {
        this.store = store;
        this.dbs = dbs;
        this.claude = claude;
        this.settings = settings;
        this.history = new ArrayList<>(store.loadHistory());
        this.saved = new ArrayList<>(store.loadSaved());
        this.memory = new ExampleMemory(store.loadExamples(), null);
    }

    private void saveMemory() { store.saveExamples(memory.all()); }

    public Settings settings() { return settings; }
    public synchronized void settings(Settings s) { settings = s; store.saveSettings(s); }
    public boolean claudeReady() { return claude != null && claude.ready(); }
    public ExampleMemory memory() { return memory; }

    // ================================================================== ask in English
    public Map<String, Object> ask(User u, String dbId, String question) {
        Database db = dbs.get(u, dbId);
        if (question == null || question.isBlank()) throw new IllegalArgumentException("Please type a question.");
        String q = question.trim();
        if (q.length() > 400) throw new IllegalArgumentException("Please keep the question under 400 characters.");
        Lexicon lex = dbs.lexicon(db);
        SqlExecutor.AccessPolicy policy = DatabaseService.policyFor(u);

        String source;
        String sql = null;
        String note = null;
        NlToSql.Result rules = NlToSql.translate(lex, q);
        Map<String, Object> learnedFrom = null;
        double confidence = rules.confidence;

        ExampleMemory.Hit hit = memory.match(lex, q);
        if (hit != null) saveMemory();
        Settings.ClaudeMode mode = claudeReady() ? settings.claudeMode : Settings.ClaudeMode.OFF;
        if (hit != null) {
            source = "LEARNED";
            sql = hit.sql();
            confidence = Math.max(0.75, hit.similarity());
            learnedFrom = Json.map("question", hit.example().question, "similarity", Math.round(hit.similarity() * 100) / 100.0,
                    "by", hit.example().createdBy);
        } else if (mode == Settings.ClaudeMode.ALWAYS || (mode == Settings.ClaudeMode.WHEN_NOT_SURE && (rules.notSure || rules.sql == null))) {
            String c = null;
            try {
                c = claude.write(db, policy, q, Clock.today().toString());
                if (c != null) SqlParser.parse(c);    // must be a valid SELECT for our engine
            } catch (SqlException e) {
                note = "Claude's SQL was not accepted (" + e.getMessage() + "), so the offline engine answered.";
                c = null;
            } catch (Exception e) {
                note = "Claude could not be reached, so the offline engine answered.";
                c = null;
            }
            if (c != null) { source = "CLAUDE"; sql = c; confidence = Math.max(confidence, 0.8); }
            else { source = "RULES"; sql = rules.sql; if (note == null && mode != Settings.ClaudeMode.OFF) note = "Claude could not answer this, so the offline engine did."; }
        } else {
            source = "RULES";
            sql = rules.sql;
        }

        Map<String, Object> resp = Json.map("question", q, "db", db.id, "source", source, "note", note,
                "understanding", rules, "learnedFrom", learnedFrom, "confidence", Math.round(confidence * 100) / 100.0,
                "notSure", "RULES".equals(source) ? rules.notSure : false);
        if (sql == null) {
            HistoryItem h = record(u, db, q, null, source, confidence, 0, 0, rules.error, rules.unknown);
            resp.put("historyId", h.id);
            resp.put("error", rules.error);
            resp.put("suggestions", suggestions(db));
            return resp;
        }
        return runAndRecord(u, db, q, sql, source, confidence, resp, rules.unknown);
    }

    // ================================================================== run your own SQL
    public Map<String, Object> runSql(User u, String dbId, String sql, String question) {
        if (!u.can(Role.ANALYST)) throw new SecurityException("Viewers can ask in English but cannot run their own SQL.");
        Database db = dbs.get(u, dbId);
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("Type a SQL query first.");
        Map<String, Object> resp = Json.map("question", question, "db", db.id, "source", "MANUAL", "confidence", 1.0, "notSure", false);
        return runAndRecord(u, db, question == null || question.isBlank() ? "(own SQL)" : question.trim(), sql.trim(), "MANUAL", 1.0, resp, List.of());
    }

    private Map<String, Object> runAndRecord(User u, Database db, String q, String sql, String source, double conf,
                                             Map<String, Object> resp, List<String> unknown) {
        resp.put("sql", visibleSql(u, sql));
        try {
            QueryResult r = new SqlExecutor(db, DatabaseService.policyFor(u)).run(sql);
            resp.put("result", r);
            resp.put("explanation", steps(db, sql));
            HistoryItem h = record(u, db, q, sql, source, conf, r.totalRows, r.millis, null, unknown);
            resp.put("historyId", h.id);
        } catch (SqlException e) {
            resp.put("error", e.getMessage());
            resp.put("errorAt", e.position);
            try { resp.put("explanation", steps(db, sql)); } catch (SqlException ignored) { }
            HistoryItem h = record(u, db, q, sql, source, conf, 0, 0, e.getMessage(), unknown);
            resp.put("historyId", h.id);
        }
        return resp;
    }

    private String visibleSql(User u, String sql) {
        return u.role == Role.VIEWER && !settings.viewersSeeSql ? null : sql;
    }

    public List<Object> steps(Database db, String sql) {
        List<Object> l = new ArrayList<>();
        for (SqlExplainer.Step s : SqlExplainer.explain(db, sql)) l.add(Json.map("clause", s.clause(), "text", s.text()));
        return l;
    }

    public Map<String, Object> explain(User u, String dbId, String sql) {
        Database db = dbs.get(u, dbId);
        try {
            return Json.map("explanation", steps(db, sql));
        } catch (SqlException e) {
            return Json.map("error", e.getMessage(), "errorAt", e.position);
        }
    }

    // ================================================================== history, feedback, teaching
    private synchronized HistoryItem record(User u, Database db, String q, String sql, String source, double conf, int rows,
                                            long millis, String error, List<String> unknown) {
        HistoryItem h = new HistoryItem(UUID.randomUUID().toString().substring(0, 12), u.username, db.id, q, sql, source,
                Math.round(conf * 100) / 100.0, rows, millis, error, Clock.now().toString());
        h.unknown = String.join(", ", unknown == null ? List.of() : unknown);
        history.add(h);
        while (history.size() > MAX_HISTORY) history.remove(0);
        store.saveHistory(history);
        return h;
    }

    public synchronized List<HistoryItem> history(User u, boolean everyone) {
        List<HistoryItem> l = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && l.size() < 300; i--) {
            HistoryItem h = history.get(i);
            if (h.user.equals(u.username) || (everyone && u.role == Role.ADMIN && !isPrivateDb(h.db, u))) l.add(h);
        }
        return l;
    }

    private boolean isPrivateDb(String dbId, User admin) { return dbId != null && dbId.startsWith("my_") && !dbId.equals("my_" + admin.username); }

    /** A history item that belongs to this user (an admin may read anyone's, except private uploads). */
    public synchronized HistoryItem item(User u, String id) {
        for (HistoryItem h : history)
            if (h.id.equals(id) && (h.user.equals(u.username))) return h;
        throw new IllegalArgumentException("No such history item.");
    }

    public synchronized void feedback(User u, String id, int value) {
        HistoryItem h = item(u, id);
        h.feedback = Integer.signum(value);
        store.saveHistory(history);
    }

    public synchronized void clearHistory(User u) {
        history.removeIf(h -> h.user.equals(u.username));
        store.saveHistory(history);
    }

    /** "Teach Prashna": store the corrected SQL for this question (it must run first). */
    public Map<String, Object> teach(User u, String historyId, String sql) {
        if (!u.can(Role.ANALYST)) throw new SecurityException("Only analysts and admins can teach new questions.");
        HistoryItem h = item(u, historyId);
        Database db = dbs.get(u, h.db);
        String use = sql == null || sql.isBlank() ? h.sql : sql.trim();
        if (use == null) throw new IllegalArgumentException("There is no SQL to learn from.");
        new SqlExecutor(db, DatabaseService.policyFor(u)).run(use);   // throws if it does not work
        ExampleMemory.Example e = memory.learn(dbs.lexicon(db), h.question, use, u.username);
        saveMemory();
        synchronized (this) { h.taught = true; h.feedback = 1; store.saveHistory(history); }
        return Json.map("example", e, "message", "Learned. Similar questions will now use this SQL.");
    }

    public List<ExampleMemory.Example> examples(User u, String dbId) {
        Database db = dbs.get(u, dbId);
        return memory.forDb(db.id);
    }

    public void forget(User u, String id) {
        if (!u.can(Role.ADMIN)) throw new SecurityException("Only admins can remove learned examples.");
        if (!memory.remove(id)) throw new IllegalArgumentException("No such example.");
        saveMemory();
    }

    // ================================================================== export
    public String csv(User u, String historyId) {
        HistoryItem h = item(u, historyId);
        if (h.sql == null) throw new IllegalArgumentException("This question has no SQL to export.");
        Database db = dbs.get(u, h.db);
        QueryResult r = new SqlExecutor(db, DatabaseService.policyFor(u)).run(h.sql);
        StringBuilder sb = new StringBuilder("﻿");   // so Excel reads the text as UTF-8
        sb.append(Csv.line(r.columns));
        for (List<Object> row : r.rows) sb.append(Csv.line(row));
        return sb.toString();
    }

    // ================================================================== saved cards
    public synchronized List<SavedQuery> saved(User u) {
        List<SavedQuery> l = new ArrayList<>();
        for (SavedQuery s : saved) if (s.user.equals(u.username)) l.add(s);
        return l;
    }

    public synchronized SavedQuery save(User u, String dbId, String name, String question, String sql) {
        dbs.get(u, dbId);
        if (name == null || name.isBlank()) name = question == null ? "Saved query" : question;
        if (name.length() > 80) name = name.substring(0, 80);
        if ((question == null || question.isBlank()) && (sql == null || sql.isBlank())) throw new IllegalArgumentException("Nothing to save.");
        if (sql != null && !sql.isBlank()) SqlParser.parse(sql);
        if (saved(u).size() >= 60) throw new IllegalArgumentException("You can keep 60 saved cards. Remove one first.");
        SavedQuery s = new SavedQuery(UUID.randomUUID().toString().substring(0, 10), u.username, dbId, name.trim(),
                question == null ? "" : question.trim(), sql == null ? "" : sql.trim(), Clock.now().toString());
        saved.add(s);
        store.saveSaved(saved);
        return s;
    }

    public synchronized void unsave(User u, String id) {
        if (!saved.removeIf(s -> s.id.equals(id) && s.user.equals(u.username))) throw new IllegalArgumentException("No such saved card.");
        store.saveSaved(saved);
    }

    // ================================================================== insights (admin)
    public synchronized Map<String, Object> insights() {
        Map<String, Integer> bySource = new LinkedHashMap<>(), unknown = new HashMap<>(), byDb = new LinkedHashMap<>();
        int up = 0, down = 0, errors = 0, notSure = 0;
        for (HistoryItem h : history) {
            bySource.merge(h.source == null ? "?" : h.source, 1, Integer::sum);
            byDb.merge(h.db, 1, Integer::sum);
            if (h.feedback > 0) up++;
            if (h.feedback < 0) down++;
            if (h.error != null) errors++;
            if ("RULES".equals(h.source) && h.confidence < NlToSql.NOT_SURE_BELOW) notSure++;
            if (h.unknown != null && !h.unknown.isBlank())
                for (String w : h.unknown.split(",\\s*")) unknown.merge(w.toLowerCase(), 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> words = new ArrayList<>(unknown.entrySet());
        words.sort((a, b) -> b.getValue() - a.getValue());
        List<Object> top = new ArrayList<>();
        for (int i = 0; i < Math.min(15, words.size()); i++) top.add(Json.map("word", words.get(i).getKey(), "count", words.get(i).getValue()));
        return Json.map("total", history.size(), "bySource", bySource, "byDb", byDb, "thumbsUp", up, "thumbsDown", down,
                "errors", errors, "notSure", notSure, "unknownWords", top, "learned", memory.all().size());
    }

    // ================================================================== suggestions
    public static List<String> suggestions(Database db) {
        if (db.id.equals("koel_college")) return List.of(
                "How many students are in each department?",
                "Top 5 students by CGPA",
                "Average marks in DBMS",
                "Girls from Ranchi with CGPA above 8",
                "Which department has the most students?",
                "Total fees collected by payment mode",
                "Students with attendance below 60% in Java",
                "Teachers who joined before 2015",
                "How many students failed?",
                "Pending hostel fee payments");
        if (db.id.equals("ghar_store")) return List.of(
                "Total sales per category",
                "Top 3 customers by number of orders",
                "Products under 500 rupees",
                "How many orders were cancelled?",
                "Most expensive product",
                "Orders placed this month",
                "Which city has the most customers?",
                "Products that are out of stock");
        List<String> l = new ArrayList<>();
        for (Table t : db.tables()) {
            TableSchema s = t.schema;
            l.add("How many " + s.plural + " are there?");
            l.add("Show all " + s.plural);
            Column num = Lexicon.defaultMeasure(s);
            Column txt = null;
            for (Column c : s.columns) if (c.type == ColumnType.TEXT && !c.sensitive && !c.name.equals(s.nameColumn)) { txt = c; break; }
            if (num != null) l.add("Average " + num.label + " of " + s.plural);
            if (num != null && txt != null) l.add("Total " + num.label + " per " + txt.label);
            if (num != null) l.add("Top 5 " + s.plural + " by " + num.label);
        }
        return l.size() > 10 ? l.subList(0, 10) : l;
    }
}
