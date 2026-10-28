package com.cecil.chatbot.ai;

import com.cecil.chatbot.model.Provider;
import java.time.Clock;

/** Picks the right client class for a provider (this is the "factory" pattern). */
public class ModelRegistry {

    private final DemoModel demo;

    public ModelRegistry(Clock clock) {
        this.demo = new DemoModel(clock);
    }

    public DemoModel demo() { return demo; }

    public ChatModel forProvider(Provider p) {
        return switch (p.kind) {
            case DEMO -> demo;
            case OPENAI -> new OpenAiClient(p.baseUrl, p.effectiveKey());
            case GEMINI -> new GeminiClient(p.baseUrl, p.effectiveKey());
            case ANTHROPIC -> new AnthropicClient(p.baseUrl, p.effectiveKey());
        };
    }
}
