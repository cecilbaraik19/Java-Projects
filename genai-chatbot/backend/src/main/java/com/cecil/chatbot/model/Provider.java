package com.cecil.chatbot.model;

import com.cecil.chatbot.model.Enums.ProviderKind;
import com.cecil.chatbot.util.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An AI service the chatbot can talk to, for example Google Gemini or Groq.
 *
 * The API key can be typed in on the "AI models" page (saved in data/providers.json)
 * or given as an environment variable (envKey, for example GEMINI_API_KEY) - the safer way.
 * The key is never sent to the browser; only the last 4 characters are shown.
 */
public class Provider {
    public String id;
    public String name;
    public ProviderKind kind = ProviderKind.OPENAI;
    public String baseUrl = "";
    public String model = "";
    /** Other model names to offer in the chat screen. */
    public List<String> models = new ArrayList<>();
    public String apiKey = "";
    public String envKey = "";
    /** Local servers such as Ollama do not need a key. */
    public boolean needsKey = true;
    public boolean enabled;
    public String help = "";

    /** The key to use: the saved one, else the environment variable. */
    public String effectiveKey() {
        if (apiKey != null && !apiKey.isBlank()) return apiKey.trim();
        if (envKey != null && !envKey.isBlank()) {
            String v = System.getenv(envKey.trim());
            if (v != null && !v.isBlank()) return v.trim();
        }
        return "";
    }

    public String keySource() {
        if (apiKey != null && !apiKey.isBlank()) return "saved";
        if (!effectiveKey().isEmpty()) return "env";
        return "none";
    }

    /** Can this provider answer right now? */
    public boolean ready() {
        if (kind == ProviderKind.DEMO) return true;
        if (!enabled) return false;
        return !needsKey || !effectiveKey().isEmpty();
    }

    /** Why it is not ready (shown in the screen). */
    public String status() {
        if (kind == ProviderKind.DEMO) return "Ready (works offline)";
        if (!enabled) return "Turned off";
        if (needsKey && effectiveKey().isEmpty()) return "Needs an API key";
        return "Ready";
    }

    /** Safe version for the browser: the key is hidden. */
    public Map<String, Object> toPublic(boolean admin) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("kind", kind);
        m.put("kindLabel", kind.label);
        m.put("model", model);
        m.put("models", models);
        m.put("enabled", kind == ProviderKind.DEMO || enabled);
        m.put("ready", ready());
        m.put("status", status());
        if (admin) {
            m.put("baseUrl", baseUrl);
            m.put("envKey", envKey);
            m.put("needsKey", needsKey);
            m.put("keySource", keySource());
            String k = effectiveKey();
            m.put("keyHint", k.isEmpty() ? "" : "••••" + (k.length() > 8 ? k.substring(k.length() - 4) : ""));
            m.put("help", help);
        }
        return m;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("kind", kind);
        m.put("baseUrl", baseUrl);
        m.put("model", model);
        m.put("models", models);
        m.put("apiKey", apiKey);
        m.put("envKey", envKey);
        m.put("needsKey", needsKey);
        m.put("enabled", enabled);
        m.put("help", help);
        return m;
    }

    public static Provider fromMap(Map<String, Object> m) {
        Provider p = new Provider();
        p.id = Json.str(m, "id");
        p.name = Json.str(m, "name", p.id);
        p.kind = Enums.parse(ProviderKind.class, Json.str(m, "kind"), ProviderKind.OPENAI);
        p.baseUrl = Json.str(m, "baseUrl", "");
        p.model = Json.str(m, "model", "");
        for (Object o : Json.list(m, "models")) if (o != null && !String.valueOf(o).isBlank()) p.models.add(String.valueOf(o));
        p.apiKey = Json.str(m, "apiKey", "");
        p.envKey = Json.str(m, "envKey", "");
        p.needsKey = Json.bool(m, "needsKey", true);
        p.enabled = Json.bool(m, "enabled", false);
        p.help = Json.str(m, "help", "");
        return p;
    }
}
