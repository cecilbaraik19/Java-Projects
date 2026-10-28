package com.cecil.chatbot.ai;

import com.cecil.chatbot.util.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Talks to Anthropic's Claude (Messages API).
 *
 * Request:  POST {baseUrl}/messages    headers x-api-key: KEY, anthropic-version: 2023-06-01
 *           {"model":"...", "max_tokens":800, "system":"...", "messages":[{"role":"user","content":"Hi"}], "stream":true}
 * Reply:    events message_start (input tokens), content_block_delta (text pieces),
 *           message_delta (stop reason + output tokens), message_stop.
 */
public class AnthropicClient implements ChatModel {

    private final String baseUrl;
    private final String apiKey;

    public AnthropicClient(String baseUrl, String apiKey) {
        this.baseUrl = HttpAi.trimSlash(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey;
    }

    @Override
    public ChatResult stream(ChatRequest req, Consumer<String> onDelta, BooleanSupplier stop) throws AiException {
        List<Object> messages = new ArrayList<>();
        for (ChatRequest.Turn t : req.alternating()) messages.add(Map.of("role", t.role(), "content", t.text()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", req.model);
        body.put("max_tokens", req.maxTokens);
        if (!req.system.isBlank()) body.put("system", req.system);
        body.put("messages", messages);
        body.put("temperature", Math.min(1.0, req.temperature)); // Claude allows 0 - 1
        body.put("stream", true);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-api-key", apiKey);
        headers.put("anthropic-version", "2023-06-01");

        ChatResult r = new ChatResult();
        StringBuilder text = new StringBuilder();
        String[] problem = {null};
        HttpAi.postSse(baseUrl + "/messages", headers, body, req.timeoutSec, data -> {
            Map<String, Object> m;
            try { m = Json.parseObject(data); } catch (IllegalArgumentException e) { return; }
            switch (Json.str(m, "type", "")) {
                case "message_start" -> r.promptTokens = Json.integer(Json.obj(Json.obj(m, "message"), "usage"), "input_tokens", 0);
                case "content_block_delta" -> {
                    Map<String, Object> d = Json.obj(m, "delta");
                    if ("text_delta".equals(Json.str(d, "type"))) {
                        String piece = Json.str(d, "text", "");
                        if (!piece.isEmpty()) {
                            text.append(piece);
                            onDelta.accept(piece);
                        }
                    }
                }
                case "message_delta" -> {
                    if ("max_tokens".equals(Json.str(Json.obj(m, "delta"), "stop_reason"))) r.finish = "length";
                    r.outputTokens = Json.integer(Json.obj(m, "usage"), "output_tokens", r.outputTokens);
                }
                case "error" -> problem[0] = HttpAi.errorMessage(data);
                default -> { }
            }
        }, stop);
        if (problem[0] != null && text.length() == 0) throw new AiException(problem[0]);
        r.text = text.toString();
        return r;
    }
}
