package com.cecil.sikho.model;

import com.cecil.sikho.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Stars (1-5), a few words and quick tags, written after a completed exchange. */
public class Review implements Json.Writable {
    public String id;
    public String exchangeId;
    public String fromId;
    public String toId;
    /** True when the person reviewed (toId) was the teacher. */
    public boolean aboutTeacher;
    public int stars;
    public String text;
    public List<String> tags = new ArrayList<>();
    public long at;

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "exchangeId", exchangeId, "fromId", fromId, "toId", toId, "aboutTeacher", aboutTeacher,
                "stars", stars, "text", text, "tags", tags, "at", at);
    }

    public static Review fromJson(Map<String, Object> m) {
        Review r = new Review();
        r.id = Json.str(m, "id");
        r.exchangeId = Json.str(m, "exchangeId");
        r.fromId = Json.str(m, "fromId");
        r.toId = Json.str(m, "toId");
        r.aboutTeacher = Json.bool(m, "aboutTeacher", true);
        r.stars = (int) Json.num(m, "stars", 5);
        r.text = Json.str(m, "text");
        r.tags = Json.strings(m, "tags");
        r.at = Json.num(m, "at", 0);
        return r;
    }
}
