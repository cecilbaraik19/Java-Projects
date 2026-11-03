package com.cecil.mail.model;

import com.cecil.mail.util.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An email in someone's inbox. "owner" is the username whose inbox it is in.
 * intent / sentiment / urgency are filled in by the AI when the email arrives.
 */
public class Email implements Json.JsonWritable {

    public long id;
    public String owner;
    public String senderName;
    public String senderEmail;
    public String subject;
    public String body;
    public String intent;
    public String sentiment;
    public String urgency;
    public boolean replied;
    public String receivedAt;     // ISO date-time, e.g. 2026-11-04T09:30

    @Override
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("owner", owner);
        m.put("senderName", senderName);
        m.put("senderEmail", senderEmail);
        m.put("subject", subject);
        m.put("body", body);
        m.put("intent", intent);
        m.put("sentiment", sentiment);
        m.put("urgency", urgency);
        m.put("replied", replied);
        m.put("receivedAt", receivedAt);
        return m;
    }

    public static Email fromJson(Map<String, Object> m) {
        Email e = new Email();
        e.id = Json.num(m, "id", 0);
        e.owner = Json.str(m, "owner");
        e.senderName = Json.str(m, "senderName");
        e.senderEmail = Json.str(m, "senderEmail");
        e.subject = Json.str(m, "subject");
        e.body = Json.str(m, "body");
        e.intent = Json.str(m, "intent");
        e.sentiment = Json.str(m, "sentiment");
        e.urgency = Json.str(m, "urgency");
        e.replied = Json.bool(m, "replied", false);
        e.receivedAt = Json.str(m, "receivedAt");
        return e;
    }
}
