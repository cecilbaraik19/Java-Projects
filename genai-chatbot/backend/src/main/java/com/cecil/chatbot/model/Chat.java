package com.cecil.chatbot.model;

import com.cecil.chatbot.util.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A conversation. It belongs to one person and keeps all its messages in order. */
public class Chat {
    public String id;
    public String owner;
    public String title = "New chat";
    public String personaId;
    /** Which AI provider answers in this chat (blank = the default one). */
    public String providerId;
    /** Model name override (blank = the provider's default model). */
    public String model;
    /** Creativity 0.0 - 1.5 (null = use the assistant's setting). */
    public Double temperature;
    public boolean useKnowledge = true;
    public boolean pinned;
    public String createdAt;
    public String updatedAt;
    public List<Message> messages = new ArrayList<>();

    public Message last() { return messages.isEmpty() ? null : messages.get(messages.size() - 1); }

    public Message message(String id) {
        for (Message m : messages) if (m.id.equals(id)) return m;
        return null;
    }

    /** Short version for the chat list (no messages). */
    public Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("owner", owner);
        m.put("title", title);
        m.put("personaId", personaId);
        m.put("providerId", providerId);
        m.put("model", model);
        m.put("temperature", temperature);
        m.put("useKnowledge", useKnowledge);
        m.put("pinned", pinned);
        m.put("createdAt", createdAt);
        m.put("updatedAt", updatedAt);
        m.put("messageCount", messages.size());
        Message l = last();
        String preview = l == null ? "" : plain(l.content);
        m.put("preview", preview.length() > 90 ? preview.substring(0, 90) + "…" : preview);
        return m;
    }

    /** Text without Markdown symbols, on one line (for previews). */
    public static String plain(String md) {
        if (md == null) return "";
        return md.replaceAll("```[\\s\\S]*?(```|$)", " [code] ").replaceAll("[*_`#>|]+", "").replaceAll("\\[(\\d+)\\]", "")
                .replaceAll("\\s+", " ").trim();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = summary();
        m.remove("messageCount");
        m.remove("preview");
        List<Map<String, Object>> list = new ArrayList<>();
        for (Message x : messages) list.add(x.toMap());
        m.put("messages", list);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Chat fromMap(Map<String, Object> m) {
        Chat c = new Chat();
        c.id = Json.str(m, "id");
        c.owner = Json.str(m, "owner");
        c.title = Json.str(m, "title", "New chat");
        c.personaId = Json.str(m, "personaId");
        c.providerId = Json.str(m, "providerId");
        c.model = Json.str(m, "model");
        c.temperature = m.get("temperature") instanceof Number n ? n.doubleValue() : null;
        c.useKnowledge = Json.bool(m, "useKnowledge", true);
        c.pinned = Json.bool(m, "pinned", false);
        c.createdAt = Json.str(m, "createdAt");
        c.updatedAt = Json.str(m, "updatedAt", c.createdAt);
        for (Object o : Json.list(m, "messages")) if (o instanceof Map) c.messages.add(Message.fromMap((Map<String, Object>) o));
        return c;
    }
}
