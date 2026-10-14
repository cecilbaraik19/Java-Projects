package com.cecil.docqa.service.answer;

import com.cecil.docqa.config.DocQaProperties;
import com.cecil.docqa.service.answer.AnswerGenerator.Context;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnswerGeneratorTest {

    private final ExtractiveAnswerGenerator offline = new ExtractiveAnswerGenerator();

    private final List<Context> contexts = List.of(
            new Context(1, "policy.pdf", 2,
                    "Our store is friendly. Refunds are given within 30 days of purchase if you keep the receipt. "
                            + "Gift cards cannot be refunded."),
            new Context(2, "faq.txt", null,
                    "Shipping usually takes five business days. We ship to every state."));

    @Test
    void offlineAnswerPicksTheMatchingSentenceAndCitesIt() {
        AnswerGenerator.Answer a = offline.generate("How long do I have to get a refund?", contexts);
        assertThat(a.mode()).isEqualTo("offline");
        assertThat(a.text()).contains("30 days").contains("[1]");
        assertThat(a.text()).doesNotContain("Shipping");
    }

    @Test
    void offlineAnswerSaysSoWhenNothingMatches() {
        AnswerGenerator.Answer a = offline.generate("Who won the cricket match?", contexts);
        assertThat(a.text()).startsWith("I couldn't find");
    }

    @Test
    void offlineSummaryListsMainPointsWithCitations() {
        java.util.Map<String, List<Context>> byDoc = new java.util.LinkedHashMap<>();
        byDoc.put("policy.pdf", contexts);
        AnswerGenerator.Answer a = offline.summarize(byDoc, 3);
        assertThat(a.text()).startsWith("Here are the main points:");
        assertThat(a.text()).contains("\u2022").contains("Refunds are given within 30 days").containsPattern("\\[\\d]");
    }

    @Test
    void claudeRequestContainsNumberedExcerptsAndQuestion() {
        ClaudeAnswerGenerator claude = new ClaudeAnswerGenerator(props("key-123"), new ObjectMapper());
        ObjectNode req = claude.buildRequest("What is the refund window?", contexts);

        assertThat(req.get("model").asText()).isEqualTo("test-model");
        assertThat(req.get("system").asText()).contains("ONLY");
        String content = req.get("messages").get(0).get("content").asText();
        assertThat(content).contains("[1] (policy.pdf, page 2)").contains("[2] (faq.txt)")
                .endsWith("Question: What is the refund window?");
    }

    @Test
    void claudeResponseTextIsParsed() throws Exception {
        ClaudeAnswerGenerator claude = new ClaudeAnswerGenerator(props("key-123"), new ObjectMapper());
        String json = """
                {"content":[{"type":"text","text":"  Refunds are allowed for 30 days [1]. "}]}""";
        assertThat(claude.parseText(json)).isEqualTo("Refunds are allowed for 30 days [1].");
    }

    @Test
    void claudeIsOffWithoutKey() {
        ClaudeAnswerGenerator claude = new ClaudeAnswerGenerator(props(""), new ObjectMapper());
        assertThat(claude.isEnabled()).isFalse();
        assertThatThrownBy(() -> claude.generate("q", contexts)).isInstanceOf(IllegalStateException.class);
    }

    private static DocQaProperties props(String key) {
        return new DocQaProperties(
                new DocQaProperties.Chunk(400, 50),
                new DocQaProperties.Retrieval(4),
                new DocQaProperties.Ai(key, "test-model", "http://localhost:1", 256, 5),
                new DocQaProperties.Cors("http://localhost:5173"));
    }
}
