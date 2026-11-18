package com.cecil.swikriti.model;

import com.cecil.swikriti.util.Json;

import java.util.Map;

/** A "redline": a reviewer's note on one line of one revision (line 0 = about the whole document). */
public class Comment implements Json.Writable {
    public String id;
    public String docId;
    public int rev;
    public int line;
    public String userId;
    public String text;
    public long at;
    public boolean resolved;
    public String resolvedBy;

    @Override public Map<String, Object> toJson() {
        return Json.obj("id", id, "docId", docId, "rev", rev, "line", line, "userId", userId, "text", text, "at", at,
                "resolved", resolved, "resolvedBy", resolvedBy);
    }

    public static Comment fromJson(Map<String, Object> m) {
        Comment c = new Comment();
        c.id = Json.str(m, "id");
        c.docId = Json.str(m, "docId");
        c.rev = (int) Json.num(m, "rev", 1);
        c.line = (int) Json.num(m, "line", 0);
        c.userId = Json.str(m, "userId");
        c.text = Json.str(m, "text");
        c.at = Json.num(m, "at", 0);
        c.resolved = Json.bool(m, "resolved", false);
        c.resolvedBy = Json.str(m, "resolvedBy");
        return c;
    }
}
