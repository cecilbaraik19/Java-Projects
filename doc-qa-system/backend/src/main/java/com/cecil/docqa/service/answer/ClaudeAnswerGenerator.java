package com.cecil.docqa.service.answer;

import com.cecil.docqa.config.DocQaProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Uses the Claude API (Anthropic Messages API) to write a natural answer.
 * Claude only sees the matching document pieces, and is told to answer from them only.
 */
@Component
public class ClaudeAnswerGenerator implements AnswerGenerator {

    static final String SYSTEM_PROMPT = """
            You answer questions using ONLY the numbered document excerpts the user gives you.
            Rules:
            - Use only facts found in the excerpts. Do not use outside knowledge.
            - After each fact, cite the excerpt number in square brackets, like [1] or [2][3].
            - If the excerpts do not contain the answer, say clearly that the documents
              don't cover it. Do not guess.
            - Write in simple, clear English. Keep it short; use a short list only when it helps.
            """;

    private final DocQaProperties.Ai config;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public ClaudeAnswerGenerator(DocQaProperties props, ObjectMapper mapper) {
        this.config = props.ai();
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public boolean isEnabled() {
        return config.enabled();
    }

    public String model() {
        return config.model();
    }

    @Override
    public Answer generate(String question, List<Context> contexts) throws Exception {
        if (!isEnabled()) throw new IllegalStateException("No Claude API key configured");

        String body = mapper.writeValueAsString(buildRequest(question, contexts));
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/v1/messages"))
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .header("content-type", "application/json")
                .header("x-api-key", config.apiKey())
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Claude API returned " + response.statusCode() + ": "
                    + abbreviate(response.body(), 300));
        }
        return new Answer(parseText(response.body()), "claude");
    }

    ObjectNode buildRequest(String question, List<Context> contexts) {
        StringBuilder prompt = new StringBuilder("Document excerpts:\n\n");
        for (Context c : contexts) {
            prompt.append('[').append(c.number()).append("] (")
                    .append(c.fileName());
            if (c.pageNumber() != null) prompt.append(", page ").append(c.pageNumber());
            prompt.append(")\n").append(c.text()).append("\n\n");
        }
        prompt.append("Question: ").append(question);

        ObjectNode root = mapper.createObjectNode();
        root.put("model", config.model());
        root.put("max_tokens", config.maxTokens());
        root.put("system", SYSTEM_PROMPT);
        ArrayNode messages = root.putArray("messages");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", prompt.toString());
        return root;
    }

    String parseText(String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : root.path("content")) {
            if ("text".equals(block.path("type").asText())) sb.append(block.path("text").asText());
        }
        if (sb.isEmpty()) throw new IllegalStateException("Claude API returned no text");
        return sb.toString().trim();
    }

    private static String abbreviate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
