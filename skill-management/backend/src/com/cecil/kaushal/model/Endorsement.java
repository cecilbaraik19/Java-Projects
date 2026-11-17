package com.cecil.kaushal.model;

import com.cecil.kaushal.util.Json;

import java.util.Map;

/** "I have seen Asha do this": a colleague vouches for one skill of another person. */
public class Endorsement implements Json.Writable {
    public String id;
    public String fromId;
    public String toId;
    public String skillId;
    public String comment;
    public long at;

    @Override public Map<String, Object> toJson() {
        return Json.obj("id", id, "fromId", fromId, "toId", toId, "skillId", skillId, "comment", comment, "at", at);
    }

    public static Endorsement fromJson(Map<String, Object> m) {
        Endorsement e = new Endorsement();
        e.id = Json.str(m, "id");
        e.fromId = Json.str(m, "fromId");
        e.toId = Json.str(m, "toId");
        e.skillId = Json.str(m, "skillId");
        e.comment = Json.str(m, "comment");
        e.at = Json.num(m, "at", 0);
        return e;
    }
}
