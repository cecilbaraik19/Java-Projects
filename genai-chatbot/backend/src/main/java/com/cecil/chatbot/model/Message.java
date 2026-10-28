package com.cecil.chatbot.model;

import com.cecil.chatbot.util.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One message in a chat: either from the person ("user") or from the AI ("assistant").
 * For AI answers we also keep which model wrote it, how many tokens it used and how long it took.
 */
public class Message {
    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";

    public String id;
    public String role = USER;
    public String content = "";
    public String at;
    // AI answers only
    public String providerId;
    public String providerName;
    public String model;
    public int promptTokens;
    public int outputTokens;
    public long ms;
    /** Knowledge-base parts used for this answer: {n, docId, title, snippet}. */
    public List<Map<String, Object>> sources = new ArrayList<>();
    /**
     * Notes about the message, for example
     * REDACTED (personal details were hidden), INJECTION (looks like a prompt attack),
     * FALLBACK (online model failed, demo answered), STOPPED (person pressed Stop), ERROR.
     */
    public List<String> flags = new ArrayList<>();
    public String note;          // short explanation shown under the message
    public int feedback;         // 1 = thumbs up, -1 = thumbs down, 0 = none
    public String feedbackNote;
    public String editedAt;

    public boolean isUser() { return USER.equals(role); }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("role", role);
        m.put("content", content);
        m.put("at", at);
        if (!isUser()) {
            m.put("providerId", providerId);
            m.put("providerName", providerName);
            m.put("model", model);
            m.put("promptTokens", promptTokens);
            m.put("outputTokens", outputTokens);
            m.put("ms", ms);
            m.put("sources", sources);
            m.put("feedback", feedback);
            m.put("feedbackNote", feedbackNote);
        }
        m.put("flags", flags);
        m.put("note", note);
        m.put("editedAt", editedAt);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Message fromMap(Map<String, Object> m) {
        Message x = new Message();
        x.id = Json.str(m, "id");
        x.role = ASSISTANT.equals(Json.str(m, "role")) ? ASSISTANT : USER;
        x.content = Json.str(m, "content", "");
        x.at = Json.str(m, "at");
        x.providerId = Json.str(m, "providerId");
        x.providerName = Json.str(m, "providerName");
        x.model = Json.str(m, "model");
        x.promptTokens = Json.integer(m, "promptTokens", 0);
        x.outputTokens = Json.integer(m, "outputTokens", 0);
        x.ms = (long) Json.num(m, "ms", 0);
        for (Object o : Json.list(m, "sources")) if (o instanceof Map) x.sources.add((Map<String, Object>) o);
        for (Object o : Json.list(m, "flags")) if (o != null) x.flags.add(String.valueOf(o));
        x.note = Json.str(m, "note");
        x.feedback = Json.integer(m, "feedback", 0);
        x.feedbackNote = Json.str(m, "feedbackNote");
        x.editedAt = Json.str(m, "editedAt");
        return x;
    }
}
