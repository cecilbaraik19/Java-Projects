package com.cecil.chatbot.model;

import com.cecil.chatbot.util.Json;
import java.util.LinkedHashMap;
import java.util.Map;

/** One call to an AI model: who, which model, how many tokens, how long, did it work. Used for the dashboard and limits. */
public class UsageRecord {
    public String at;
    public String username;
    public String chatId;
    public String providerId;
    public String model;
    public int promptTokens;
    public int outputTokens;
    public long ms;
    public boolean ok = true;
    public boolean fallback;
    public String error;

    public int totalTokens() { return promptTokens + outputTokens; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", at);
        m.put("username", username);
        m.put("chatId", chatId);
        m.put("providerId", providerId);
        m.put("model", model);
        m.put("promptTokens", promptTokens);
        m.put("outputTokens", outputTokens);
        m.put("ms", ms);
        m.put("ok", ok);
        m.put("fallback", fallback);
        m.put("error", error);
        return m;
    }

    public static UsageRecord fromMap(Map<String, Object> m) {
        UsageRecord u = new UsageRecord();
        u.at = Json.str(m, "at");
        u.username = Json.str(m, "username");
        u.chatId = Json.str(m, "chatId");
        u.providerId = Json.str(m, "providerId");
        u.model = Json.str(m, "model");
        u.promptTokens = Json.integer(m, "promptTokens", 0);
        u.outputTokens = Json.integer(m, "outputTokens", 0);
        u.ms = (long) Json.num(m, "ms", 0);
        u.ok = Json.bool(m, "ok", true);
        u.fallback = Json.bool(m, "fallback", false);
        u.error = Json.str(m, "error");
        return u;
    }
}
