package com.cecil.complaints.model;

import com.cecil.complaints.model.Enums.Category;
import com.cecil.complaints.util.Json;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A knowledge-base article: a known fix for a known type of complaint.
 * The recommender matches complaints to these articles.
 */
public class Solution {

    public String id;
    public String title;
    public Category category = Category.OTHER;
    public List<String> keywords = new ArrayList<>();
    /** What the agent should do, step by step. */
    public List<String> steps = new ArrayList<>();
    /**
     * Text sent to the customer. Placeholders: {customerName}, {orderId}, {complaintId}, {agentName}.
     */
    public String responseTemplate;
    public boolean active = true;

    // ---- learning statistics (updated by agent feedback)
    public int timesRecommended;
    public int timesApplied;
    public int helpfulCount;
    public int notHelpfulCount;
    public Instant createdAt;
    public Instant updatedAt;

    /**
     * Success rate with a small prior (Laplace smoothing), so a new article starts at 0.5
     * instead of 0 or 1.
     */
    public double successRate() {
        return (helpfulCount + 1.0) / (helpfulCount + notHelpfulCount + 2.0);
    }

    /** Text used for TF-IDF matching. Title and keywords count twice because they are most specific. */
    public String searchText() {
        String kw = String.join(" ", keywords);
        return title + " " + title + " " + kw + " " + kw + " " + String.join(" ", steps);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("category", category);
        m.put("categoryLabel", category.label);
        m.put("keywords", keywords);
        m.put("steps", steps);
        m.put("responseTemplate", responseTemplate);
        m.put("active", active);
        m.put("timesRecommended", timesRecommended);
        m.put("timesApplied", timesApplied);
        m.put("helpfulCount", helpfulCount);
        m.put("notHelpfulCount", notHelpfulCount);
        m.put("successRate", Math.round(successRate() * 100) / 100.0);
        m.put("createdAt", Complaint.ts(createdAt));
        m.put("updatedAt", Complaint.ts(updatedAt));
        return m;
    }

    public static Solution fromMap(Map<String, Object> m) {
        Solution s = new Solution();
        s.id = Json.str(m, "id");
        s.title = Json.str(m, "title");
        s.category = Enums.parse(Category.class, Json.str(m, "category"), Category.OTHER);
        s.keywords = Json.strList(m, "keywords");
        s.steps = Json.strList(m, "steps");
        s.responseTemplate = Json.str(m, "responseTemplate");
        s.active = Json.bool(m, "active", true);
        s.timesRecommended = (int) Json.lng(m, "timesRecommended", 0);
        s.timesApplied = (int) Json.lng(m, "timesApplied", 0);
        s.helpfulCount = (int) Json.lng(m, "helpfulCount", 0);
        s.notHelpfulCount = (int) Json.lng(m, "notHelpfulCount", 0);
        s.createdAt = Complaint.parseTime(Json.str(m, "createdAt"));
        s.updatedAt = Complaint.parseTime(Json.str(m, "updatedAt"));
        return s;
    }
}
