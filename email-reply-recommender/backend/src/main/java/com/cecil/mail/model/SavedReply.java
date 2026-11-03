package com.cecil.mail.model;

import com.cecil.mail.util.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A reply the user chose and saved.
 * rating: 1 = the suggestion was useful, -1 = not useful, 0 = no feedback.
 * source: "OFFLINE" (Patra's own Java engine) or "CLAUDE" (polished by Claude).
 */
public class SavedReply implements Json.JsonWritable {

    public long id;
    public String owner;
    public long emailId;
    public String toName;
    public String toEmail;
    public String subject;
    public String intent;
    public String tone;
    public String stance;
    public String body;
    public int professionalism;
    public int rating;
    public String source;
    public String savedAt;

    @Override
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("owner", owner);
        m.put("emailId", emailId);
        m.put("toName", toName);
        m.put("toEmail", toEmail);
        m.put("subject", subject);
        m.put("intent", intent);
        m.put("tone", tone);
        m.put("stance", stance);
        m.put("body", body);
        m.put("professionalism", professionalism);
        m.put("rating", rating);
        m.put("source", source);
        m.put("savedAt", savedAt);
        return m;
    }

    public static SavedReply fromJson(Map<String, Object> m) {
        SavedReply r = new SavedReply();
        r.id = Json.num(m, "id", 0);
        r.owner = Json.str(m, "owner");
        r.emailId = Json.num(m, "emailId", 0);
        r.toName = Json.str(m, "toName");
        r.toEmail = Json.str(m, "toEmail");
        r.subject = Json.str(m, "subject");
        r.intent = Json.str(m, "intent");
        r.tone = Json.str(m, "tone");
        r.stance = Json.str(m, "stance");
        r.body = Json.str(m, "body");
        r.professionalism = (int) Json.num(m, "professionalism", 0);
        r.rating = (int) Json.num(m, "rating", 0);
        r.source = Json.str(m, "source");
        r.savedAt = Json.str(m, "savedAt");
        return r;
    }
}
