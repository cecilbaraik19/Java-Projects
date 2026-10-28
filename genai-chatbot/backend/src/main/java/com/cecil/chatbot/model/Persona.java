package com.cecil.chatbot.model;

import com.cecil.chatbot.util.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An "assistant" = a ready-made personality for the chatbot.
 * The system prompt is a hidden instruction sent before the chat, for example
 * "You are a patient Java tutor. Explain with small examples."
 */
public class Persona {
    public String id;
    public String name;
    public String description = "";
    public String systemPrompt = "";
    public double temperature = 0.7;
    public boolean useKnowledge;
    public List<String> starters = new ArrayList<>();
    public String color = "teal";
    public boolean active = true;
    public String createdBy;
    public String updatedAt;

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("description", description);
        m.put("systemPrompt", systemPrompt);
        m.put("temperature", temperature);
        m.put("useKnowledge", useKnowledge);
        m.put("starters", starters);
        m.put("color", color);
        m.put("active", active);
        m.put("createdBy", createdBy);
        m.put("updatedAt", updatedAt);
        return m;
    }

    public static Persona fromMap(Map<String, Object> m) {
        Persona p = new Persona();
        p.id = Json.str(m, "id");
        p.name = Json.str(m, "name", "Assistant");
        p.description = Json.str(m, "description", "");
        p.systemPrompt = Json.str(m, "systemPrompt", "");
        p.temperature = Json.num(m, "temperature", 0.7);
        p.useKnowledge = Json.bool(m, "useKnowledge", false);
        for (Object o : Json.list(m, "starters")) if (o != null) p.starters.add(String.valueOf(o));
        p.color = Json.str(m, "color", "teal");
        p.active = Json.bool(m, "active", true);
        p.createdBy = Json.str(m, "createdBy");
        p.updatedAt = Json.str(m, "updatedAt");
        return p;
    }
}
