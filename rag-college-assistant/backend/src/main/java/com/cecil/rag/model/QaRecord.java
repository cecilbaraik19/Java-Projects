package com.cecil.rag.model;

import com.cecil.rag.util.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One question that someone asked, and the answer the assistant gave. */
public class QaRecord implements Json.JsonAware {
    public String id;
    public String userId;
    public String sessionId;
    public String question;
    public String standalone = "";      // the question rewritten so it makes sense alone (for follow-ups)
    public String answer = "";
    public String shortAnswer = "";
    public String mode = "offline";     // offline | claude | verified | none
    public String status = "answered";  // answered | unanswered
    public double confidence;           // 0..1
    public double topScore;             // best chunk score after re-ranking
    public double grounded = 1;         // share of answer sentences supported by the sources
    public String category = "";
    public List<Map<String, Object>> sources = new ArrayList<>();
    public String feedback = "";        // up | down | ""
    public String feedbackNote = "";
    public boolean resolved;
    public String resolvedNote = "";
    public long ms;
    public long at;

    public String confidenceLabel() {
        if ("none".equals(mode)) return "No answer";
        if (confidence >= 0.72) return "High";
        if (confidence >= 0.5) return "Medium";
        return "Low";
    }

    @Override
    public Map<String, Object> toJson() {
        return Json.map("id", id, "userId", userId, "sessionId", sessionId, "question", question,
                "standalone", standalone, "answer", answer, "shortAnswer", shortAnswer, "mode", mode,
                "status", status, "confidence", round(confidence), "confidenceLabel", confidenceLabel(),
                "topScore", round(topScore), "grounded", round(grounded), "category", category,
                "sources", sources, "feedback", feedback, "feedbackNote", feedbackNote, "resolved", resolved,
                "resolvedNote", resolvedNote, "ms", ms, "at", at);
    }

    private static double round(double d) { return Math.round(d * 1000) / 1000.0; }

    @SuppressWarnings("unchecked")
    public static QaRecord from(Map<String, Object> m) {
        QaRecord r = new QaRecord();
        r.id = Json.str(m, "id", null);
        r.userId = Json.str(m, "userId", "");
        r.sessionId = Json.str(m, "sessionId", "");
        r.question = Json.str(m, "question", "");
        r.standalone = Json.str(m, "standalone", "");
        r.answer = Json.str(m, "answer", "");
        r.shortAnswer = Json.str(m, "shortAnswer", "");
        r.mode = Json.str(m, "mode", "offline");
        r.status = Json.str(m, "status", "answered");
        r.confidence = Json.dbl(m, "confidence", 0);
        r.topScore = Json.dbl(m, "topScore", 0);
        r.grounded = Json.dbl(m, "grounded", 1);
        r.category = Json.str(m, "category", "");
        for (Object o : Json.list(m, "sources")) if (o instanceof Map) r.sources.add((Map<String, Object>) o);
        r.feedback = Json.str(m, "feedback", "");
        r.feedbackNote = Json.str(m, "feedbackNote", "");
        r.resolved = Json.bool(m, "resolved", false);
        r.resolvedNote = Json.str(m, "resolvedNote", "");
        r.ms = Json.num(m, "ms", 0);
        r.at = Json.num(m, "at", 0);
        return r;
    }
}
