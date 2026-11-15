package com.cecil.sangam.model;

import com.cecil.sangam.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A shared document (plain text, one line per paragraph). Every save makes a new version and keeps the old text,
 * so two people can save at the same time: the second save is merged with the first (3-way merge).
 */
public class Doc implements Json.Writable {
    public static final int KEEP_VERSIONS = 60;

    public String id;
    public String projectId;
    public String title;
    public String body = "";
    public int version = 1;
    public long createdAt, updatedAt;
    public String createdBy, updatedBy;
    /** A short note about the current version ("fixed the deploy steps"). */
    public String note = "";
    /** Old versions, oldest first. Each: {v, by, at, body, note}. */
    public List<Map<String, Object>> history = new ArrayList<>();

    /** The text of one version, or null when it is too old and no longer kept. */
    public String bodyAt(int v) {
        if (v == version) return body;
        for (Map<String, Object> h : history) if (Json.num(h, "v", -1) == v) return Json.str(h, "body", "");
        return null;
    }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "projectId", projectId, "title", title, "body", body, "version", version,
                "createdAt", createdAt, "updatedAt", updatedAt, "createdBy", createdBy, "updatedBy", updatedBy,
                "note", note, "history", history);
    }

    @SuppressWarnings("unchecked")
    public static Doc fromJson(Map<String, Object> m) {
        Doc d = new Doc();
        d.id = Json.str(m, "id");
        d.projectId = Json.str(m, "projectId");
        d.title = Json.str(m, "title");
        d.body = Json.str(m, "body", "");
        d.version = (int) Json.num(m, "version", 1);
        d.createdAt = Json.num(m, "createdAt", 0);
        d.updatedAt = Json.num(m, "updatedAt", 0);
        d.createdBy = Json.str(m, "createdBy");
        d.updatedBy = Json.str(m, "updatedBy");
        d.note = Json.str(m, "note", "");
        for (Object o : Json.list(m, "history")) if (o instanceof Map) d.history.add((Map<String, Object>) o);
        return d;
    }
}
