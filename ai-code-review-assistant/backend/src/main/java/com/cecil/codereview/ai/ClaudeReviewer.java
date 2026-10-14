package com.cecil.codereview.ai;

import com.cecil.codereview.model.FileReport;
import com.cecil.codereview.model.Issue;
import com.cecil.codereview.report.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Optional online AI review using the Claude API.
 *
 * Turn it on with the --ai option and set the ANTHROPIC_API_KEY environment variable.
 * The model can be changed with CODEREVIEW_MODEL (default claude-sonnet-5-5).
 * If anything goes wrong, the tool keeps working with the offline engine.
 */
public final class ClaudeReviewer {
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final int MAX_CODE_CHARS = 30_000;

    private final String apiKey;
    private final String model;
    private final HttpClient http;

    public ClaudeReviewer(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    /** Creates a reviewer from environment variables, or returns null if no key is set. */
    public static ClaudeReviewer fromEnvironment() {
        String key = System.getenv("ANTHROPIC_API_KEY");
        if (key == null || key.isBlank()) return null;
        String model = System.getenv("CODEREVIEW_MODEL");
        return new ClaudeReviewer(key.trim(), model == null || model.isBlank() ? "claude-sonnet-5-5" : model.trim());
    }

    public String model() { return model; }

    /** Sends one file (plus the static findings) to Claude and returns its review text. */
    public String reviewFile(FileReport report, String sourceCode) throws IOException, InterruptedException {
        String code = sourceCode.length() > MAX_CODE_CHARS
                ? sourceCode.substring(0, MAX_CODE_CHARS) + "\n... (file truncated)"
                : sourceCode;
        StringBuilder findings = new StringBuilder();
        List<Issue> issues = report.issues();
        for (int i = 0; i < Math.min(issues.size(), 40); i++) {
            Issue is = issues.get(i);
            findings.append("- line ").append(is.line()).append(" [").append(is.severity()).append("] ")
                    .append(is.ruleId()).append(": ").append(is.message()).append('\n');
        }
        if (findings.length() == 0) findings.append("(none)\n");

        String system = "You are a senior software engineer doing a code review. Be specific, kind and practical. "
                + "Use simple English. Reply in plain text (no markdown tables), at most 300 words, with these parts: "
                + "1) Summary (2 sentences). 2) Logic or design problems the static analyzer could not see, with line numbers. "
                + "3) Which static findings below are false positives, if any. 4) One short improved code example for the most important fix.";
        String user = "File: " + report.file() + " (" + report.language() + ")\n\nStatic analyzer findings:\n"
                + findings + "\nSource code:\n```\n" + code + "\n```";

        String body = "{\"model\":" + Json.str(model) + ",\"max_tokens\":1200,\"system\":" + Json.str(system)
                + ",\"messages\":[{\"role\":\"user\",\"content\":" + Json.str(user) + "}]}";

        HttpRequest req = HttpRequest.newBuilder(URI.create(API_URL))
                .timeout(Duration.ofSeconds(120))
                .header("content-type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            List<String> msg = Json.stringValues(resp.body(), "message");
            throw new IOException("Claude API error " + resp.statusCode() + ": "
                    + (msg.isEmpty() ? resp.body() : msg.get(0)));
        }
        List<String> texts = Json.stringValues(resp.body(), "text");
        return texts.isEmpty() ? "(no text returned)" : String.join("\n", texts).trim();
    }
}
