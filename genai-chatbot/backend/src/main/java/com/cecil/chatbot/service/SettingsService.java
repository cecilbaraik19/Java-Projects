package com.cecil.chatbot.service;

import com.cecil.chatbot.ai.AiException;
import com.cecil.chatbot.ai.ChatRequest;
import com.cecil.chatbot.ai.ChatResult;
import com.cecil.chatbot.ai.ModelRegistry;
import com.cecil.chatbot.model.Enums;
import com.cecil.chatbot.model.Enums.InjectionMode;
import com.cecil.chatbot.model.Enums.ProviderKind;
import com.cecil.chatbot.model.Provider;
import com.cecil.chatbot.model.Settings;
import com.cecil.chatbot.model.User;
import com.cecil.chatbot.store.DataStore;
import com.cecil.chatbot.util.Json;
import com.cecil.chatbot.util.Passwords;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** App settings and AI providers (admin only), plus the "Test connection" button. */
public class SettingsService {

    private final DataStore store;
    private final ModelRegistry models;

    public SettingsService(DataStore store, ModelRegistry models) {
        this.store = store;
        this.models = models;
    }

    public Map<String, Object> get(User me) {
        AuthService.requireAdmin(me);
        synchronized (store.lock) {
            return store.settings.toMap();
        }
    }

    public Map<String, Object> update(User me, Map<String, Object> b) {
        AuthService.requireAdmin(me);
        synchronized (store.lock) {
            Settings s = store.settings;
            if (b.containsKey("botName")) {
                String n = AuthService.clean(Json.str(b, "botName", ""), 30);
                if (n.isEmpty()) throw ServiceException.bad("Give the bot a name.");
                s.botName = n;
            }
            if (b.containsKey("defaultProviderId")) {
                String id = Json.str(b, "defaultProviderId", "");
                if (store.provider(id) == null) throw ServiceException.bad("Unknown AI model provider.");
                s.defaultProviderId = id;
            }
            if (b.containsKey("fallbackToDemo")) s.fallbackToDemo = Json.bool(b, "fallbackToDemo", true);
            if (b.containsKey("redactPii")) s.redactPii = Json.bool(b, "redactPii", true);
            if (b.containsKey("injectionMode")) s.injectionMode = Enums.parse(InjectionMode.class, Json.str(b, "injectionMode"), s.injectionMode);
            if (b.containsKey("blockedWords")) {
                List<String> words = new ArrayList<>();
                Object raw = b.get("blockedWords");
                List<Object> items = raw instanceof List ? Json.list(b, "blockedWords") : List.of((Object[]) String.valueOf(raw).split("[,\\n]"));
                for (Object o : items) {
                    String w = AuthService.clean(String.valueOf(o), 40);
                    if (!w.isEmpty() && !words.contains(w) && words.size() < 100) words.add(w);
                }
                s.blockedWords = words;
            }
            s.maxContextTokens = range(b, "maxContextTokens", s.maxContextTokens, 500, 200_000, "Context budget");
            s.maxReplyTokens = range(b, "maxReplyTokens", s.maxReplyTokens, 50, 16_000, "Reply length");
            s.ratePerMinute = range(b, "ratePerMinute", s.ratePerMinute, 0, 600, "Messages per minute");
            s.dailyTokenQuota = range(b, "dailyTokenQuota", s.dailyTokenQuota, 0, 10_000_000, "Daily token limit");
            s.timeoutSec = range(b, "timeoutSec", s.timeoutSec, 5, 300, "Time-out");
            store.saveSettings();
            return s.toMap();
        }
    }

    private static int range(Map<String, Object> b, String key, int cur, int min, int max, String label) {
        if (!b.containsKey(key)) return cur;
        double v = Json.num(b, key, Double.NaN);
        if (Double.isNaN(v) || v < min || v > max) throw ServiceException.bad(label + " must be between " + min + " and " + max + ".");
        return (int) Math.round(v);
    }

    // ------------------------------------------------------------------ providers

    public List<Map<String, Object>> providers(User me) {
        boolean admin = me.role == Enums.Role.ADMIN;
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (store.lock) {
            for (Provider p : store.providers) {
                Map<String, Object> m = p.toPublic(admin);
                m.put("isDefault", p.id.equals(store.settings.defaultProviderId));
                out.add(m);
            }
        }
        return out;
    }

    public Map<String, Object> saveProvider(User me, String id, Map<String, Object> b) {
        AuthService.requireAdmin(me);
        synchronized (store.lock) {
            Provider p;
            if (id == null) {
                if (store.providers.size() >= 30) throw ServiceException.bad("Too many providers.");
                p = new Provider();
                p.id = "pr" + Passwords.randomId();
                p.kind = Enums.parse(ProviderKind.class, Json.str(b, "kind"), ProviderKind.OPENAI);
                p.enabled = true;
                if (p.kind == ProviderKind.DEMO) throw ServiceException.bad("There is already a demo model.");
            } else {
                p = store.provider(id);
                if (p == null) throw ServiceException.notFound("No such provider.");
            }
            String name = AuthService.clean(Json.str(b, "name", p.name), 40);
            if (name == null || name.length() < 2) throw ServiceException.bad("Give the provider a name.");
            p.name = name;
            if (p.kind != ProviderKind.DEMO) {
                String url = Json.str(b, "baseUrl", p.baseUrl).trim();
                checkUrl(url);
                p.baseUrl = url;
                String model = AuthService.clean(Json.str(b, "model", p.model), 100);
                if (model.isEmpty()) throw ServiceException.bad("Enter the model name, for example gemini-2.5-flash.");
                if (!model.matches("[A-Za-z0-9._:/@-]+")) throw ServiceException.bad("The model name has characters that are not allowed.");
                p.model = model;
                if (b.containsKey("models")) {
                    List<String> list = new ArrayList<>();
                    Object raw = b.get("models");
                    List<Object> items = raw instanceof List ? Json.list(b, "models") : List.of((Object[]) String.valueOf(raw).split("[,\\n]"));
                    for (Object o : items) {
                        String m = AuthService.clean(String.valueOf(o), 100);
                        if (!m.isEmpty() && m.matches("[A-Za-z0-9._:/@-]+") && !list.contains(m) && list.size() < 12) list.add(m);
                    }
                    p.models = list;
                }
                if (b.containsKey("apiKey")) {
                    // an empty box means "keep the key"; the word CLEAR removes it
                    String key = Json.str(b, "apiKey", "").trim();
                    if (key.equalsIgnoreCase("clear")) p.apiKey = "";
                    else if (!key.isEmpty()) {
                        if (key.length() > 300 || key.matches(".*\\s.*")) throw ServiceException.bad("That does not look like an API key.");
                        p.apiKey = key;
                    }
                }
                String env = Json.str(b, "envKey", p.envKey).trim();
                if (!env.isEmpty() && !env.matches("[A-Z][A-Z0-9_]{1,60}")) throw ServiceException.bad("Environment variable names use capital letters, digits and _.");
                p.envKey = env;
                p.needsKey = Json.bool(b, "needsKey", p.needsKey);
                p.enabled = Json.bool(b, "enabled", p.enabled);
                if (!p.enabled && p.id.equals(store.settings.defaultProviderId)) {
                    store.settings.defaultProviderId = "demo";
                    store.saveSettings();
                }
            }
            if (id == null) store.providers.add(p);
            store.saveProviders();
            Map<String, Object> m = p.toPublic(true);
            m.put("isDefault", p.id.equals(store.settings.defaultProviderId));
            return m;
        }
    }

    static void checkUrl(String url) {
        try {
            URI u = URI.create(url);
            boolean local = u.getHost() != null && (u.getHost().equals("localhost") || u.getHost().startsWith("127."));
            if (u.getHost() == null || !("https".equals(u.getScheme()) || ("http".equals(u.getScheme()) && local))) {
                throw new IllegalArgumentException();
            }
            if (u.getUserInfo() != null || u.getQuery() != null) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw ServiceException.bad("Base URL must start with https:// (http:// is allowed only for localhost, e.g. Ollama).");
        }
    }

    public void deleteProvider(User me, String id) {
        AuthService.requireAdmin(me);
        synchronized (store.lock) {
            Provider p = store.provider(id);
            if (p == null) throw ServiceException.notFound("No such provider.");
            if (p.kind == ProviderKind.DEMO) throw ServiceException.bad("The demo model cannot be removed.");
            store.providers.remove(p);
            if (id.equals(store.settings.defaultProviderId)) {
                store.settings.defaultProviderId = "demo";
                store.saveSettings();
            }
            store.saveProviders();
        }
    }

    /** Sends a tiny prompt to check the key, address and model all work. */
    public Map<String, Object> test(User me, String id) {
        AuthService.requireAdmin(me);
        Provider p;
        int timeout;
        synchronized (store.lock) {
            p = store.provider(id);
            if (p == null) throw ServiceException.notFound("No such provider.");
            timeout = store.settings.timeoutSec;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (p.kind != ProviderKind.DEMO && p.needsKey && p.effectiveKey().isEmpty()) {
            out.put("ok", false);
            out.put("message", "No API key yet. Paste the key or set the environment variable " + (p.envKey.isEmpty() ? "" : p.envKey) + ".");
            return out;
        }
        ChatRequest req = new ChatRequest();
        req.system = "You are a connection test. Reply with exactly: OK";
        req.turns.add(new ChatRequest.Turn("user", "Say OK"));
        req.model = p.model;
        req.temperature = 0;
        req.maxTokens = 20;
        req.timeoutSec = Math.min(timeout, 30);
        long t0 = System.currentTimeMillis();
        try {
            ChatResult r = models.forProvider(p).stream(req, s -> { }, () -> false);
            out.put("ok", true);
            out.put("reply", r.text.length() > 80 ? r.text.substring(0, 80) : r.text);
            out.put("ms", System.currentTimeMillis() - t0);
            out.put("message", "Connected to " + p.name + " (" + p.model + ") in " + (System.currentTimeMillis() - t0) + " ms.");
        } catch (AiException e) {
            out.put("ok", false);
            out.put("message", e.getMessage());
        }
        return out;
    }
}
