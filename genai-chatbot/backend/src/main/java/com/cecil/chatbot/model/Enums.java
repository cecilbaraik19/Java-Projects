package com.cecil.chatbot.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fixed lists used by the app (roles, kinds of AI provider, safety modes). */
public final class Enums {

    private Enums() { }

    /** Who can do what. */
    public enum Role {
        ADMIN("Admin"),
        TRAINER("AI trainer"),
        MEMBER("Member");

        public final String label;
        Role(String label) { this.label = label; }

        /** Admin and AI trainer can change assistants and the knowledge base. */
        public boolean canTrain() { return this == ADMIN || this == TRAINER; }
    }

    /**
     * The kind of AI service. Each kind "speaks" a different web API.
     * OPENAI covers every service that copies OpenAI's API: OpenAI, Groq, OpenRouter, Ollama, LM Studio.
     */
    public enum ProviderKind {
        DEMO("Built-in demo (offline)"),
        OPENAI("OpenAI-compatible"),
        GEMINI("Google Gemini"),
        ANTHROPIC("Anthropic Claude");

        public final String label;
        ProviderKind(String label) { this.label = label; }
    }

    /** What to do when a message looks like a prompt-injection attempt. */
    public enum InjectionMode {
        OFF("Do nothing"),
        WARN("Allow, but mark it"),
        BLOCK("Block the message");

        public final String label;
        InjectionMode(String label) { this.label = label; }
    }

    /** Reads an enum by name; returns def when the text is empty or unknown. */
    public static <E extends Enum<E>> E parse(Class<E> type, String name, E def) {
        if (name == null || name.isBlank()) return def;
        try {
            return Enum.valueOf(type, name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return def;
        }
    }

    static <E extends Enum<E>> List<Map<String, Object>> list(E[] values, java.util.function.Function<E, String> label) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (E e : values) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.name());
            m.put("label", label.apply(e));
            out.add(m);
        }
        return out;
    }

    /** All the lists, for the browser. */
    public static Map<String, Object> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("roles", list(Role.values(), r -> r.label));
        m.put("providerKinds", list(ProviderKind.values(), k -> k.label));
        m.put("injectionModes", list(InjectionMode.values(), i -> i.label));
        return m;
    }
}
