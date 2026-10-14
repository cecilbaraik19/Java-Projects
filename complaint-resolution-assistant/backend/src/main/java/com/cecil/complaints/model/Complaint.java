package com.cecil.complaints.model;

import com.cecil.complaints.model.Enums.Category;
import com.cecil.complaints.model.Enums.Channel;
import com.cecil.complaints.model.Enums.CustomerTier;
import com.cecil.complaints.model.Enums.Priority;
import com.cecil.complaints.model.Enums.Sentiment;
import com.cecil.complaints.model.Enums.Status;
import com.cecil.complaints.util.Json;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A customer complaint plus everything the AI engine worked out about it.
 */
public class Complaint {

    // ---- what the customer told us
    public String id;
    public String customerName;
    public String customerEmail;
    public String orderId;
    public Channel channel = Channel.WEB;
    public CustomerTier customerTier = CustomerTier.STANDARD;
    public String subject;
    public String description;

    // ---- AI analysis
    public Category category = Category.OTHER;
    public double categoryConfidence;
    public Sentiment sentiment = Sentiment.NEUTRAL;
    public double sentimentScore;
    public List<String> emotions = new ArrayList<>();
    public Priority priority = Priority.MEDIUM;
    public List<String> priorityReasons = new ArrayList<>();
    public List<String> keywords = new ArrayList<>();
    public List<Recommendation> recommendations = new ArrayList<>();
    public String duplicateOf;
    public String draftResponse;

    // ---- workflow
    public Status status = Status.OPEN;
    public String assignedTo;
    public String appliedSolutionId;
    public String finalResponse;
    public boolean responseSent;
    public String escalationReason;
    public Integer satisfactionRating;
    public Instant createdAt;
    public Instant updatedAt;
    public Instant dueAt;
    public Instant resolvedAt;
    public List<Note> notes = new ArrayList<>();
    public List<HistoryEntry> history = new ArrayList<>();

    /** Full text used for AI processing. */
    public String fullText() {
        return (subject == null ? "" : subject) + ". " + (description == null ? "" : description);
    }

    public boolean isOverdue(Instant now) {
        return status.isActive() && dueAt != null && now.isAfter(dueAt);
    }

    public void addHistory(String by, String action) {
        history.add(new HistoryEntry(Instant.now(), by, action));
    }

    // ------------------------------------------------------------ nested types

    /** One recommended solution with its confidence score and the reasons behind it. */
    public static class Recommendation {
        public String solutionId;
        public String title;
        public double score;
        public List<String> reasons = new ArrayList<>();
        /** null = no feedback yet, true = helpful, false = not helpful */
        public Boolean helpful;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("solutionId", solutionId);
            m.put("title", title);
            m.put("score", Math.round(score * 1000) / 1000.0);
            m.put("confidence", (int) Math.round(score * 100));
            m.put("reasons", reasons);
            m.put("helpful", helpful);
            return m;
        }

        public static Recommendation fromMap(Map<String, Object> m) {
            Recommendation r = new Recommendation();
            r.solutionId = Json.str(m, "solutionId");
            r.title = Json.str(m, "title");
            r.score = Json.num(m, "score", 0);
            r.reasons = Json.strList(m, "reasons");
            Object h = m.get("helpful");
            r.helpful = h instanceof Boolean b ? b : null;
            return r;
        }
    }

    /** An internal note written by an agent. */
    public static class Note {
        public Instant at;
        public String author;
        public String text;

        public Note() {
        }

        public Note(Instant at, String author, String text) {
            this.at = at;
            this.author = author;
            this.text = text;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", at == null ? null : at.toString());
            m.put("author", author);
            m.put("text", text);
            return m;
        }

        public static Note fromMap(Map<String, Object> m) {
            return new Note(parseTime(Json.str(m, "at")), Json.str(m, "author"), Json.str(m, "text"));
        }
    }

    /** Audit trail entry: who did what and when. */
    public static class HistoryEntry {
        public Instant at;
        public String by;
        public String action;

        public HistoryEntry() {
        }

        public HistoryEntry(Instant at, String by, String action) {
            this.at = at;
            this.by = by;
            this.action = action;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", at == null ? null : at.toString());
            m.put("by", by);
            m.put("action", action);
            return m;
        }

        public static HistoryEntry fromMap(Map<String, Object> m) {
            return new HistoryEntry(parseTime(Json.str(m, "at")), Json.str(m, "by"), Json.str(m, "action"));
        }
    }

    // ------------------------------------------------------------ JSON mapping

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("customerName", customerName);
        m.put("customerEmail", customerEmail);
        m.put("orderId", orderId);
        m.put("channel", channel);
        m.put("customerTier", customerTier);
        m.put("subject", subject);
        m.put("description", description);
        m.put("category", category);
        m.put("categoryLabel", category.label);
        m.put("categoryConfidence", Math.round(categoryConfidence * 1000) / 1000.0);
        m.put("sentiment", sentiment);
        m.put("sentimentScore", Math.round(sentimentScore * 1000) / 1000.0);
        m.put("emotions", emotions);
        m.put("priority", priority);
        m.put("priorityReasons", priorityReasons);
        m.put("keywords", keywords);
        List<Object> recs = new ArrayList<>();
        for (Recommendation r : recommendations) {
            recs.add(r.toMap());
        }
        m.put("recommendations", recs);
        m.put("duplicateOf", duplicateOf);
        m.put("draftResponse", draftResponse);
        m.put("status", status);
        m.put("assignedTo", assignedTo);
        m.put("appliedSolutionId", appliedSolutionId);
        m.put("finalResponse", finalResponse);
        m.put("responseSent", responseSent);
        m.put("escalationReason", escalationReason);
        m.put("satisfactionRating", satisfactionRating);
        m.put("createdAt", ts(createdAt));
        m.put("updatedAt", ts(updatedAt));
        m.put("dueAt", ts(dueAt));
        m.put("resolvedAt", ts(resolvedAt));
        m.put("overdue", isOverdue(Instant.now()));
        List<Object> ns = new ArrayList<>();
        for (Note n : notes) {
            ns.add(n.toMap());
        }
        m.put("notes", ns);
        List<Object> hs = new ArrayList<>();
        for (HistoryEntry h : history) {
            hs.add(h.toMap());
        }
        m.put("history", hs);
        return m;
    }

    /** A short version for list screens (no long text, notes or history). */
    public Map<String, Object> toSummaryMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("customerName", customerName);
        m.put("customerEmail", customerEmail);
        m.put("subject", subject);
        m.put("category", category);
        m.put("categoryLabel", category.label);
        m.put("sentiment", sentiment);
        m.put("priority", priority);
        m.put("status", status);
        m.put("assignedTo", assignedTo);
        m.put("channel", channel);
        m.put("customerTier", customerTier);
        m.put("createdAt", ts(createdAt));
        m.put("dueAt", ts(dueAt));
        m.put("overdue", isOverdue(Instant.now()));
        m.put("duplicateOf", duplicateOf);
        return m;
    }

    public static Complaint fromMap(Map<String, Object> m) {
        Complaint c = new Complaint();
        c.id = Json.str(m, "id");
        c.customerName = Json.str(m, "customerName");
        c.customerEmail = Json.str(m, "customerEmail");
        c.orderId = Json.str(m, "orderId");
        c.channel = Enums.parse(Channel.class, Json.str(m, "channel"), Channel.WEB);
        c.customerTier = Enums.parse(CustomerTier.class, Json.str(m, "customerTier"), CustomerTier.STANDARD);
        c.subject = Json.str(m, "subject");
        c.description = Json.str(m, "description");
        c.category = Enums.parse(Category.class, Json.str(m, "category"), Category.OTHER);
        c.categoryConfidence = Json.num(m, "categoryConfidence", 0);
        c.sentiment = Enums.parse(Sentiment.class, Json.str(m, "sentiment"), Sentiment.NEUTRAL);
        c.sentimentScore = Json.num(m, "sentimentScore", 0);
        c.emotions = Json.strList(m, "emotions");
        c.priority = Enums.parse(Priority.class, Json.str(m, "priority"), Priority.MEDIUM);
        c.priorityReasons = Json.strList(m, "priorityReasons");
        c.keywords = Json.strList(m, "keywords");
        for (Map<String, Object> rm : Json.mapList(m, "recommendations")) {
            c.recommendations.add(Recommendation.fromMap(rm));
        }
        c.duplicateOf = Json.str(m, "duplicateOf");
        c.draftResponse = Json.str(m, "draftResponse");
        c.status = Enums.parse(Status.class, Json.str(m, "status"), Status.OPEN);
        c.assignedTo = Json.str(m, "assignedTo");
        c.appliedSolutionId = Json.str(m, "appliedSolutionId");
        c.finalResponse = Json.str(m, "finalResponse");
        c.responseSent = Json.bool(m, "responseSent", false);
        c.escalationReason = Json.str(m, "escalationReason");
        Object rating = m.get("satisfactionRating");
        c.satisfactionRating = rating instanceof Number n ? n.intValue() : null;
        c.createdAt = parseTime(Json.str(m, "createdAt"));
        c.updatedAt = parseTime(Json.str(m, "updatedAt"));
        c.dueAt = parseTime(Json.str(m, "dueAt"));
        c.resolvedAt = parseTime(Json.str(m, "resolvedAt"));
        for (Map<String, Object> nm : Json.mapList(m, "notes")) {
            c.notes.add(Note.fromMap(nm));
        }
        for (Map<String, Object> hm : Json.mapList(m, "history")) {
            c.history.add(HistoryEntry.fromMap(hm));
        }
        return c;
    }

    static String ts(Instant i) {
        return i == null ? null : i.toString();
    }

    static Instant parseTime(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (Exception ex) {
            return null;
        }
    }
}
