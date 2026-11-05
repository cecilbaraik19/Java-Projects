package com.cecil.sql.model;

import com.cecil.sql.util.Json;

import java.util.Map;

/** One question asked (or SQL run), kept so people can look back, export and give feedback. */
public final class HistoryItem implements Json.JsonWritable {
    public final String id, user, db, question, at;
    public final String sql;
    public final String source;     // RULES, LEARNED, CLAUDE, MANUAL
    public final double confidence;
    public final int rows;
    public final long millis;
    public final String error;
    public int feedback;            // 1 = right, -1 = wrong, 0 = none
    public boolean taught;
    public String unknown = "";     // words the engine did not understand (helps admins add synonyms)

    public HistoryItem(String id, String user, String db, String question, String sql, String source, double confidence,
                       int rows, long millis, String error, String at) {
        this.id = id; this.user = user; this.db = db; this.question = question; this.sql = sql; this.source = source;
        this.confidence = confidence; this.rows = rows; this.millis = millis; this.error = error; this.at = at;
    }

    @Override public Object toJson() {
        return Json.map("id", id, "user", user, "db", db, "question", question, "sql", sql, "source", source,
                "confidence", confidence, "rows", rows, "millis", millis, "error", error, "at", at,
                "feedback", feedback, "taught", taught, "unknown", unknown);
    }

    public static HistoryItem fromJson(Map<String, Object> m) {
        Object c = m.get("confidence");
        HistoryItem h = new HistoryItem(Json.str(m, "id"), Json.str(m, "user"), Json.str(m, "db"), Json.str(m, "question"),
                Json.str(m, "sql"), Json.str(m, "source"), c instanceof Number n ? n.doubleValue() : 0,
                (int) Json.num(m, "rows", 0), Json.num(m, "millis", 0), Json.str(m, "error"), Json.str(m, "at"));
        h.feedback = (int) Json.num(m, "feedback", 0);
        h.taught = Json.bool(m, "taught", false);
        h.unknown = Json.str(m, "unknown", "");
        return h;
    }
}
