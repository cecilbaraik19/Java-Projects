package com.cecil.complaints.ai;

import com.cecil.complaints.util.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * OPTIONAL: polishes the draft reply with a large language model (Claude API).
 *
 * It is only used when the ANTHROPIC_API_KEY environment variable is set.
 * Without a key the app works fully offline with its own NLP engine.
 * Any error simply falls back to the offline draft.
 */
public class LlmClient {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";

    private final String apiKey;
    private final String model;
    private final HttpClient http;

    public LlmClient(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model == null || model.isBlank() ? "claude-sonnet-5-5" : model;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public static LlmClient fromEnvironment() {
        return new LlmClient(System.getenv("ANTHROPIC_API_KEY"), System.getenv("CLAUDE_MODEL"));
    }

    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String model() {
        return model;
    }

    /** Ask the model to rewrite the draft. Returns empty if disabled or on any error. */
    public Optional<String> polishReply(String complaintText, String solutionSteps, String draft) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        String prompt = "A customer sent this complaint:\n<complaint>\n" + complaintText + "\n</complaint>\n\n"
                + "The support team decided on this resolution:\n<resolution>\n" + solutionSteps + "\n</resolution>\n\n"
                + "Here is a draft reply:\n<draft>\n" + draft + "\n</draft>\n\n"
                + "Rewrite the draft so it is empathetic, specific to this complaint, clear and under 200 words. "
                + "Keep the reference number and the promised time frame. Do not promise anything that is not in "
                + "the resolution. Reply with the email text only.";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", 800);
        body.put("system", "You are a senior customer support specialist writing replies to customer complaints.");
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));

        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(API_URL))
                    .timeout(Duration.ofSeconds(45))
                    .header("content-type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body)))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                System.err.println("[LLM] API returned " + res.statusCode() + " - using offline draft");
                return Optional.empty();
            }
            Map<String, Object> json = Json.parseObject(res.body());
            StringBuilder text = new StringBuilder();
            for (Map<String, Object> block : Json.mapList(json, "content")) {
                if ("text".equals(Json.str(block, "type"))) {
                    text.append(Json.str(block, "text", ""));
                }
            }
            String out = text.toString().trim();
            return out.isEmpty() ? Optional.empty() : Optional.of(out);
        } catch (Exception ex) {
            System.err.println("[LLM] " + ex.getMessage() + " - using offline draft");
            return Optional.empty();
        }
    }
}
