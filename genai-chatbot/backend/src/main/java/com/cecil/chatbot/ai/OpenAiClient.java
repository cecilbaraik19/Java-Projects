package com.cecil.chatbot.ai;

import com.cecil.chatbot.util.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Talks to any "OpenAI-compatible" chat API: OpenAI, Groq, OpenRouter, Together, Ollama, LM Studio ...
 *
 * Request:  POST {baseUrl}/chat/completions
 *           {"model": "...", "stream": true, "messages": [{"role":"system","content":"..."}, {"role":"user","content":"..."}]}
 * Reply:    data: {"choices":[{"delta":{"content":"Hel"}}]}
 *           data: {"choices":[{"delta":{"content":"lo"}}]}
 *           data: [DONE]
 */
public class OpenAiClient implements ChatModel {

    private final String baseUrl;
    private final String apiKey;

    public OpenAiClient(String baseUrl, String apiKey) {
        this.baseUrl = HttpAi.trimSlash(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ChatResult stream(ChatRequest req, Consumer<String> onDelta, BooleanSupplier stop) throws AiException {
        List<Object> messages = new ArrayList<>();
        if (!req.system.isBlank()) messages.add(Map.of("role", "system", "content", req.system));
        for (ChatRequest.Turn t : req.turns) {
            if (t.text() != null && !t.text().isBlank()) messages.add(Map.of("role", t.role(), "content", t.text()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", req.model);
        body.put("messages", messages);
        body.put("temperature", req.temperature);
        // OpenAI's own API now calls this max_completion_tokens; the copies still use max_tokens
        body.put(baseUrl.contains("api.openai.com") ? "max_completion_tokens" : "max_tokens", req.maxTokens);
        body.put("stream", true);

        Map<String, String> headers = new LinkedHashMap<>();
        if (!apiKey.isEmpty()) headers.put("Authorization", "Bearer " + apiKey);

        ChatResult r = new ChatResult();
        StringBuilder text = new StringBuilder();
        String[] problem = {null};
        HttpAi.postSse(baseUrl + "/chat/completions", headers, body, req.timeoutSec, data -> {
            if (data.equals("[DONE]")) return;
            Map<String, Object> m;
            try { m = Json.parseObject(data); } catch (IllegalArgumentException e) { return; }
            if (m.get("error") != null) { problem[0] = HttpAi.errorMessage(data); return; }
            for (Object c : Json.list(m, "choices")) {
                if (!(c instanceof Map)) continue;
                Map<String, Object> choice = (Map<String, Object>) c;
                String piece = Json.str(Json.obj(choice, "delta"), "content");
                if (piece != null && !piece.isEmpty()) {
                    text.append(piece);
                    onDelta.accept(piece);
                }
                if ("length".equals(Json.str(choice, "finish_reason"))) r.finish = "length";
            }
            readUsage(Json.obj(m, "usage"), r);
            readUsage(Json.obj(Json.obj(m, "x_groq"), "usage"), r); // Groq puts it here
        }, stop);
        if (problem[0] != null && text.length() == 0) throw new AiException(problem[0]);
        r.text = text.toString();
        return r;
    }

    private static void readUsage(Map<String, Object> u, ChatResult r) {
        if (u.isEmpty()) return;
        r.promptTokens = Json.integer(u, "prompt_tokens", r.promptTokens);
        r.outputTokens = Json.integer(u, "completion_tokens", r.outputTokens);
    }
}
