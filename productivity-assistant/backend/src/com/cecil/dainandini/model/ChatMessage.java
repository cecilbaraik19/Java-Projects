package com.cecil.dainandini.model;

import com.cecil.dainandini.util.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/** One line in the "Ask Dainandini" chat. card = extra data the page draws (a task list, a plan ...). */
public class ChatMessage implements Json.Writable {
    public String id;
    public String userId;
    public boolean fromUser;
    public String text;
    public long at;
    public String intent = "";
    public double confidence;
    /** "offline" or "claude" for answers. */
    public String source = "offline";
    public Map<String, Object> card = new LinkedHashMap<>();

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "userId", userId, "fromUser", fromUser, "text", text, "at", at, "intent", intent,
                "confidence", confidence, "source", source, "card", card);
    }

    @SuppressWarnings("unchecked")
    public static ChatMessage fromJson(Map<String, Object> m) {
        ChatMessage c = new ChatMessage();
        c.id = Json.str(m, "id");
        c.userId = Json.str(m, "userId");
        c.fromUser = Json.bool(m, "fromUser", false);
        c.text = Json.str(m, "text", "");
        c.at = Json.num(m, "at", 0);
        c.intent = Json.str(m, "intent", "");
        c.confidence = Json.dbl(m, "confidence", 0);
        c.source = Json.str(m, "source", "offline");
        if (m.get("card") instanceof Map<?, ?> card) c.card = new LinkedHashMap<>((Map<String, Object>) card);
        return c;
    }
}
