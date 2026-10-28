package com.cecil.chatbot.ai;

import com.cecil.chatbot.util.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Talks to Google Gemini (generativelanguage.googleapis.com). A free API key comes from Google AI Studio.
 *
 * Request:  POST {baseUrl}/models/{model}:streamGenerateContent?alt=sse     header x-goog-api-key: KEY
 *           {"systemInstruction":{"parts":[{"text":"..."}]},
 *            "contents":[{"role":"user","parts":[{"text":"Hi"}]}, {"role":"model","parts":[...]}],
 *            "generationConfig":{"temperature":0.7,"maxOutputTokens":800}}
 * Reply:    data: {"candidates":[{"content":{"parts":[{"text":"Hel"}]}}]}
 * Gemini calls the AI side "model" instead of "assistant".
 */
public class GeminiClient implements ChatModel {

    /** "Thinking" Gemini models spend some output tokens on thinking first, so we give them extra room. */
    static final int THINKING_ROOM = 1024;

    private final String baseUrl;
    private final String apiKey;

    public GeminiClient(String baseUrl, String apiKey) {
        this.baseUrl = HttpAi.trimSlash(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ChatResult stream(ChatRequest req, Consumer<String> onDelta, BooleanSupplier stop) throws AiException {
        List<Object> contents = new ArrayList<>();
        for (ChatRequest.Turn t : req.alternating()) {
            contents.add(Map.of("role", t.role().equals("assistant") ? "model" : "user",
                    "parts", List.of(Map.of("text", t.text()))));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (!req.system.isBlank()) body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", req.system))));
        body.put("contents", contents);
        Map<String, Object> gen = new LinkedHashMap<>();
        gen.put("temperature", req.temperature);
        gen.put("maxOutputTokens", req.maxTokens + THINKING_ROOM);
        body.put("generationConfig", gen);

        String model = req.model.startsWith("models/") ? req.model.substring(7) : req.model;
        String url = baseUrl + "/models/" + encode(model) + ":streamGenerateContent?alt=sse";

        ChatResult r = new ChatResult();
        StringBuilder text = new StringBuilder();
        String[] problem = {null};
        HttpAi.postSse(url, Map.of("x-goog-api-key", apiKey), body, req.timeoutSec, data -> {
            Map<String, Object> m;
            try { m = Json.parseObject(data); } catch (IllegalArgumentException e) { return; }
            if (m.get("error") != null) { problem[0] = HttpAi.errorMessage(data); return; }
            String blocked = Json.str(Json.obj(m, "promptFeedback"), "blockReason");
            if (blocked != null) { problem[0] = "Gemini's safety filter blocked this message (" + blocked + ")."; return; }
            for (Object c : Json.list(m, "candidates")) {
                if (!(c instanceof Map)) continue;
                Map<String, Object> cand = (Map<String, Object>) c;
                for (Object p : Json.list(Json.obj(cand, "content"), "parts")) {
                    if (!(p instanceof Map)) continue;
                    Map<String, Object> part = (Map<String, Object>) p;
                    if (Json.bool(part, "thought", false)) continue; // hidden thinking, not part of the answer
                    String piece = Json.str(part, "text");
                    if (piece != null && !piece.isEmpty()) {
                        text.append(piece);
                        onDelta.accept(piece);
                    }
                }
                String fr = Json.str(cand, "finishReason");
                if ("MAX_TOKENS".equals(fr)) r.finish = "length";
                if ("SAFETY".equals(fr) && text.length() == 0) problem[0] = "Gemini's safety filter stopped the answer.";
            }
            Map<String, Object> u = Json.obj(m, "usageMetadata");
            if (!u.isEmpty()) {
                r.promptTokens = Json.integer(u, "promptTokenCount", r.promptTokens);
                r.outputTokens = Json.integer(u, "candidatesTokenCount", r.outputTokens);
            }
        }, stop);
        if (problem[0] != null && text.length() == 0) throw new AiException(problem[0]);
        r.text = text.toString();
        return r;
    }

    private static String encode(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "");
    }
}
