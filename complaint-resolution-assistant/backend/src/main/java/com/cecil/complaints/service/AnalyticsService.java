package com.cecil.complaints.service;

import com.cecil.complaints.model.Complaint;
import com.cecil.complaints.model.Enums.Category;
import com.cecil.complaints.model.Enums.Priority;
import com.cecil.complaints.model.Enums.Role;
import com.cecil.complaints.model.Enums.Sentiment;
import com.cecil.complaints.model.Enums.Status;
import com.cecil.complaints.model.Solution;
import com.cecil.complaints.model.User;
import com.cecil.complaints.store.DataStore;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Numbers for the dashboard and analytics screens.
 */
public class AnalyticsService {

    private final DataStore store;
    private final ZoneId zone;

    public AnalyticsService(DataStore store, ZoneId zone) {
        this.store = store;
        this.zone = zone;
    }

    public Map<String, Object> summary() {
        List<Complaint> all;
        List<Solution> solutions;
        List<User> users;
        synchronized (store) {
            all = new ArrayList<>(store.complaints().values());
            solutions = new ArrayList<>(store.solutions().values());
            users = new ArrayList<>(store.users().values());
        }
        Instant now = Instant.now();
        Map<String, Object> m = new LinkedHashMap<>();

        // ---- headline numbers
        long active = all.stream().filter(c -> c.status.isActive()).count();
        long overdue = all.stream().filter(c -> c.isOverdue(now)).count();
        long escalated = all.stream().filter(c -> c.status == Status.ESCALATED).count();
        long critical = all.stream().filter(c -> c.status.isActive() && c.priority == Priority.CRITICAL).count();
        List<Complaint> resolved = all.stream().filter(c -> c.resolvedAt != null && c.createdAt != null).toList();
        double avgHours = resolved.stream()
                .mapToDouble(c -> Duration.between(c.createdAt, c.resolvedAt).toMinutes() / 60.0)
                .average().orElse(0);
        long withinSla = resolved.stream().filter(c -> c.dueAt == null || !c.resolvedAt.isAfter(c.dueAt)).count();
        List<Integer> ratings = all.stream().filter(c -> c.satisfactionRating != null)
                .map(c -> c.satisfactionRating).toList();
        double avgRating = ratings.stream().mapToInt(Integer::intValue).average().orElse(0);

        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("total", all.size());
        kpi.put("active", active);
        kpi.put("resolved", resolved.size());
        kpi.put("overdue", overdue);
        kpi.put("escalated", escalated);
        kpi.put("criticalOpen", critical);
        kpi.put("avgResolutionHours", round1(avgHours));
        kpi.put("slaCompliance", resolved.isEmpty() ? null : Math.round(withinSla * 100.0 / resolved.size()));
        kpi.put("avgSatisfaction", ratings.isEmpty() ? null : round1(avgRating));
        kpi.put("ratingsCount", ratings.size());
        m.put("kpi", kpi);

        // ---- breakdowns
        Map<Status, Integer> byStatus = new EnumMap<>(Status.class);
        Map<Priority, Integer> byPriority = new EnumMap<>(Priority.class);
        Map<Sentiment, Integer> bySentiment = new EnumMap<>(Sentiment.class);
        Map<Category, Integer> byCategory = new EnumMap<>(Category.class);
        for (Status s : Status.values()) {
            byStatus.put(s, 0);
        }
        for (Priority p : Priority.values()) {
            byPriority.put(p, 0);
        }
        for (Sentiment s : Sentiment.values()) {
            bySentiment.put(s, 0);
        }
        for (Complaint c : all) {
            byStatus.merge(c.status, 1, Integer::sum);
            byPriority.merge(c.priority, 1, Integer::sum);
            bySentiment.merge(c.sentiment, 1, Integer::sum);
            byCategory.merge(c.category, 1, Integer::sum);
        }
        m.put("byStatus", byStatus);
        m.put("byPriority", byPriority);
        m.put("bySentiment", bySentiment);
        Map<String, Integer> catLabels = new LinkedHashMap<>();
        byCategory.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> catLabels.put(e.getKey().label, e.getValue()));
        m.put("byCategory", catLabels);

        // ---- trend: complaints per day for the last 14 days
        Map<String, Integer> perDay = new LinkedHashMap<>();
        LocalDate today = LocalDate.now(zone);
        for (int i = 13; i >= 0; i--) {
            perDay.put(today.minusDays(i).toString(), 0);
        }
        for (Complaint c : all) {
            if (c.createdAt != null) {
                String day = c.createdAt.atZone(zone).toLocalDate().toString();
                perDay.computeIfPresent(day, (k, v) -> v + 1);
            }
        }
        m.put("trend", perDay);

        // ---- AI quality: how often agents chose the AI's suggestion
        List<Complaint> applied = all.stream().filter(c -> c.appliedSolutionId != null).toList();
        long top1 = applied.stream().filter(c -> !c.recommendations.isEmpty()
                && c.recommendations.get(0).solutionId.equals(c.appliedSolutionId)).count();
        long top3 = applied.stream().filter(c -> c.recommendations.stream()
                .anyMatch(r -> r.solutionId.equals(c.appliedSolutionId))).count();
        long helpfulVotes = 0;
        long totalVotes = 0;
        for (Complaint c : all) {
            for (Complaint.Recommendation r : c.recommendations) {
                if (r.helpful != null) {
                    totalVotes++;
                    if (r.helpful) {
                        helpfulVotes++;
                    }
                }
            }
        }
        Map<String, Object> aiq = new LinkedHashMap<>();
        aiq.put("appliedCount", applied.size());
        aiq.put("top1Accuracy", applied.isEmpty() ? null : Math.round(top1 * 100.0 / applied.size()));
        aiq.put("top3Accuracy", applied.isEmpty() ? null : Math.round(top3 * 100.0 / applied.size()));
        aiq.put("helpfulRate", totalVotes == 0 ? null : Math.round(helpfulVotes * 100.0 / totalVotes));
        aiq.put("feedbackVotes", totalVotes);
        m.put("aiQuality", aiq);

        // ---- top solutions
        List<Map<String, Object>> topSolutions = new ArrayList<>();
        solutions.stream()
                .filter(s -> s.timesRecommended > 0 || s.timesApplied > 0)
                .sorted((a, b) -> b.timesApplied != a.timesApplied ? b.timesApplied - a.timesApplied
                        : b.timesRecommended - a.timesRecommended)
                .limit(6)
                .forEach(s -> {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("id", s.id);
                    x.put("title", s.title);
                    x.put("recommended", s.timesRecommended);
                    x.put("applied", s.timesApplied);
                    x.put("successRate", Math.round(s.successRate() * 100));
                    topSolutions.add(x);
                });
        m.put("topSolutions", topSolutions);

        // ---- agent performance
        List<Map<String, Object>> agents = new ArrayList<>();
        for (User u : users) {
            if (!u.active) {
                continue;
            }
            List<Complaint> mine = all.stream().filter(c -> u.username.equals(c.assignedTo)).toList();
            if (mine.isEmpty() && u.role == Role.ADMIN) {
                continue;
            }
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("username", u.username);
            a.put("name", u.displayName);
            a.put("assigned", mine.size());
            a.put("active", mine.stream().filter(c -> c.status.isActive()).count());
            a.put("resolved", mine.stream().filter(c -> c.resolvedAt != null).count());
            a.put("overdue", mine.stream().filter(c -> c.isOverdue(now)).count());
            double r = mine.stream().filter(c -> c.satisfactionRating != null)
                    .mapToInt(c -> c.satisfactionRating).average().orElse(0);
            a.put("avgRating", r == 0 ? null : round1(r));
            agents.add(a);
        }
        m.put("agents", agents);

        // ---- urgent queue: what to look at first
        List<Map<String, Object>> urgent = new ArrayList<>();
        all.stream()
                .filter(c -> c.status.isActive())
                .sorted((a, b) -> {
                    int p = b.priority.ordinal() - a.priority.ordinal();
                    if (p != 0) {
                        return p;
                    }
                    if (a.dueAt == null || b.dueAt == null) {
                        return 0;
                    }
                    return a.dueAt.compareTo(b.dueAt);
                })
                .limit(6)
                .forEach(c -> urgent.add(c.toSummaryMap()));
        m.put("urgent", urgent);
        return m;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
