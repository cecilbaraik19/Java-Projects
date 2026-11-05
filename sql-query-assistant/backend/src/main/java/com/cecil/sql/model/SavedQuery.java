package com.cecil.sql.model;

import com.cecil.sql.util.Json;

import java.util.Map;

/** A question + SQL kept on the "saved cards" shelf to run again with one click. */
public final class SavedQuery implements Json.JsonWritable {
    public final String id, user, db, name, question, sql, at;

    public SavedQuery(String id, String user, String db, String name, String question, String sql, String at) {
        this.id = id; this.user = user; this.db = db; this.name = name; this.question = question; this.sql = sql; this.at = at;
    }

    @Override public Object toJson() {
        return Json.map("id", id, "user", user, "db", db, "name", name, "question", question, "sql", sql, "at", at);
    }

    public static SavedQuery fromJson(Map<String, Object> m) {
        return new SavedQuery(Json.str(m, "id"), Json.str(m, "user"), Json.str(m, "db"), Json.str(m, "name"),
                Json.str(m, "question"), Json.str(m, "sql"), Json.str(m, "at"));
    }
}
