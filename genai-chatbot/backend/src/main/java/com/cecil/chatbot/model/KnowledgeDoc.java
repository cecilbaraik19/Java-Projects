package com.cecil.chatbot.model;

import com.cecil.chatbot.util.Json;
import java.util.LinkedHashMap;
import java.util.Map;

/** A note in the knowledge base. The chatbot can look things up in these notes (RAG). */
public class KnowledgeDoc {
    public String id;
    public String title;
    public String text = "";
    public String tags = "";
    public String createdBy;
    public String updatedAt;

    public Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("tags", tags);
        m.put("chars", text.length());
        m.put("words", text.isBlank() ? 0 : text.trim().split("\\s+").length);
        m.put("createdBy", createdBy);
        m.put("updatedAt", updatedAt);
        return m;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = summary();
        m.remove("chars");
        m.remove("words");
        m.put("text", text);
        return m;
    }

    public static KnowledgeDoc fromMap(Map<String, Object> m) {
        KnowledgeDoc d = new KnowledgeDoc();
        d.id = Json.str(m, "id");
        d.title = Json.str(m, "title", "Untitled");
        d.text = Json.str(m, "text", "");
        d.tags = Json.str(m, "tags", "");
        d.createdBy = Json.str(m, "createdBy");
        d.updatedAt = Json.str(m, "updatedAt");
        return d;
    }
}
