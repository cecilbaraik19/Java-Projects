package com.cecil.parisar.model;

import com.cecil.parisar.util.Json;

import java.util.Map;

/** A notice on the campus notice board. The campus assistant also reads notices to answer questions. */
public class Notice implements Json.Writable {
    public String id;
    public String title;
    public String body;
    public String category;    // ACADEMIC, EXAM, EVENT, FACILITY, HOSTEL
    public String postedBy;
    public long postedAt;
    public boolean pinned;

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "title", title, "body", body, "category", category, "postedBy", postedBy,
                "postedAt", postedAt, "pinned", pinned);
    }

    public static Notice fromJson(Map<String, Object> m) {
        Notice n = new Notice();
        n.id = Json.str(m, "id");
        n.title = Json.str(m, "title");
        n.body = Json.str(m, "body");
        n.category = Json.str(m, "category", "ACADEMIC");
        n.postedBy = Json.str(m, "postedBy");
        n.postedAt = Json.num(m, "postedAt", 0);
        n.pinned = Json.bool(m, "pinned", false);
        return n;
    }
}
