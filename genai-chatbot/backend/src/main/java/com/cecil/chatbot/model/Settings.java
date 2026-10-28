package com.cecil.chatbot.model;

import com.cecil.chatbot.model.Enums.InjectionMode;
import com.cecil.chatbot.util.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** App-wide settings, changed by the admin on the "AI settings" page. */
public class Settings {
    public String botName = "Nova";
    public String defaultProviderId = "demo";
    /** If the online model fails (no internet, wrong key), let the built-in demo answer instead. */
    public boolean fallbackToDemo = true;

    // ---- safety
    /** Hide e-mails, phone numbers, card numbers, Aadhaar-like numbers and secret keys before text leaves this computer. */
    public boolean redactPii = true;
    public InjectionMode injectionMode = InjectionMode.WARN;
    public List<String> blockedWords = new ArrayList<>();

    // ---- limits
    /** How much of the chat (in tokens) is sent to the model each time. Older messages are left out. */
    public int maxContextTokens = 3000;
    /** Longest answer the model may write (tokens). */
    public int maxReplyTokens = 800;
    /** Messages one person may send per minute (admin has no limit). */
    public int ratePerMinute = 10;
    /** Tokens one person may use per day (0 = no limit; admin has no limit). */
    public int dailyTokenQuota = 40000;
    /** Seconds to wait for the online model. */
    public int timeoutSec = 60;

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("botName", botName);
        m.put("defaultProviderId", defaultProviderId);
        m.put("fallbackToDemo", fallbackToDemo);
        m.put("redactPii", redactPii);
        m.put("injectionMode", injectionMode);
        m.put("blockedWords", blockedWords);
        m.put("maxContextTokens", maxContextTokens);
        m.put("maxReplyTokens", maxReplyTokens);
        m.put("ratePerMinute", ratePerMinute);
        m.put("dailyTokenQuota", dailyTokenQuota);
        m.put("timeoutSec", timeoutSec);
        return m;
    }

    public static Settings fromMap(Map<String, Object> m) {
        Settings s = new Settings();
        s.botName = Json.str(m, "botName", s.botName);
        s.defaultProviderId = Json.str(m, "defaultProviderId", s.defaultProviderId);
        s.fallbackToDemo = Json.bool(m, "fallbackToDemo", s.fallbackToDemo);
        s.redactPii = Json.bool(m, "redactPii", s.redactPii);
        s.injectionMode = Enums.parse(InjectionMode.class, Json.str(m, "injectionMode"), s.injectionMode);
        s.blockedWords.clear();
        for (Object o : Json.list(m, "blockedWords")) if (o != null && !String.valueOf(o).isBlank()) s.blockedWords.add(String.valueOf(o));
        s.maxContextTokens = Json.integer(m, "maxContextTokens", s.maxContextTokens);
        s.maxReplyTokens = Json.integer(m, "maxReplyTokens", s.maxReplyTokens);
        s.ratePerMinute = Json.integer(m, "ratePerMinute", s.ratePerMinute);
        s.dailyTokenQuota = Json.integer(m, "dailyTokenQuota", s.dailyTokenQuota);
        s.timeoutSec = Json.integer(m, "timeoutSec", s.timeoutSec);
        return s;
    }
}
