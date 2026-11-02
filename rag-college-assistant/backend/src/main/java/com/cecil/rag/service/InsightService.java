package com.cecil.rag.service;

import com.cecil.rag.model.Doc;
import com.cecil.rag.model.QaRecord;
import com.cecil.rag.store.DataStore;
import com.cecil.rag.util.Clock;
import com.cecil.rag.util.Json;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Numbers for the Insights page: how many questions, how many answered, what people ask about, etc. */
public final class InsightService {
    private final DataStore store;
    private final KnowledgeService knowledge;

    public InsightService(DataStore store, KnowledgeService knowledge) {
        this.store = store;
        this.knowledge = knowledge;
    }

    public Map<String, Object> dashboard(int days) {
        days = Math.max(7, Math.min(90, days));
        LocalDate today = Clock.today();
        LocalDate from = today.minusDays(days - 1);
        LocalDate prevFrom = from.minusDays(days);

        long total = 0, answered = 0, verified = 0, up = 0, down = 0, prevTotal = 0, prevAnswered = 0;
        double confSum = 0, msSum = 0, groundSum = 0;
        Set<String> askers = new HashSet<>();
        Map<String, long[]> perDay = new LinkedHashMap<>();
        for (int i = 0; i < days; i++) perDay.put(from.plusDays(i).toString(), new long[2]);
        Map<String, Long> byCategory = new LinkedHashMap<>();
        Map<String, Long> docCites = new HashMap<>();
        long[] byHour = new long[24];
        Map<String, Long> byMode = new LinkedHashMap<>();
        Map<String, Long> confBands = new LinkedHashMap<>();
        confBands.put("High", 0L); confBands.put("Medium", 0L); confBands.put("Low", 0L); confBands.put("No answer", 0L);

        synchronized (store) {
            for (QaRecord r : store.records) {
                LocalDate d = Clock.dateOf(r.at);
                boolean ok = "answered".equals(r.status);
                if (!d.isBefore(prevFrom) && d.isBefore(from)) { prevTotal++; if (ok) prevAnswered++; }
                if (d.isBefore(from) || d.isAfter(today)) continue;
                total++;
                askers.add(r.userId);
                long[] pd = perDay.get(d.toString());
                if (pd != null) pd[ok ? 0 : 1]++;
                if (ok) { answered++; confSum += r.confidence; groundSum += r.grounded; }
                if ("verified".equals(r.mode)) verified++;
                if ("up".equals(r.feedback)) up++;
                if ("down".equals(r.feedback)) down++;
                msSum += r.ms;
                byHour[Clock.hourOf(r.at)]++;
                byMode.merge(r.mode, 1L, Long::sum);
                confBands.merge(r.confidenceLabel(), 1L, Long::sum);
                if (!r.category.isBlank()) byCategory.merge(r.category, 1L, Long::sum);
                Set<String> seen = new HashSet<>();
                for (Map<String, Object> s : r.sources) {
                    String id = String.valueOf(s.get("docId"));
                    if (seen.add(id)) docCites.merge(id, 1L, Long::sum);
                }
            }
        }

        List<Map<String, Object>> daily = new ArrayList<>();
        perDay.forEach((d, v) -> daily.add(Json.map("date", d, "answered", v[0], "unanswered", v[1])));
        List<Map<String, Object>> cats = new ArrayList<>();
        byCategory.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> cats.add(Json.map("category", e.getKey(), "count", e.getValue())));
        List<Map<String, Object>> topDocs = new ArrayList<>();
        List<Map<String, Object>> neverCited = new ArrayList<>();
        List<Map<String, Object>> reviewDue = new ArrayList<>();
        synchronized (store) {
            docCites.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(6).forEach(e -> {
                Doc d = store.docs.get(e.getKey());
                if (d != null) topDocs.add(Json.map("id", d.id, "title", d.title, "category", d.category, "count", e.getValue()));
            });
            for (Doc d : store.docs.values()) {
                if (d.searchable() && !docCites.containsKey(d.id)) neverCited.add(Json.map("id", d.id, "title", d.title));
                if (KnowledgeService.reviewDue(d)) reviewDue.add(Json.map("id", d.id, "title", d.title, "reviewDate", d.reviewDate));
            }
        }
        List<Long> hours = new ArrayList<>();
        for (long h : byHour) hours.add(h);

        return Json.map(
                "days", days, "from", from.toString(), "to", today.toString(),
                "total", total, "answered", answered, "unanswered", total - answered,
                "answerRate", total == 0 ? 0 : (double) answered / total,
                "prevAnswerRate", prevTotal == 0 ? 0 : (double) prevAnswered / prevTotal,
                "prevTotal", prevTotal,
                "avgConfidence", answered == 0 ? 0 : confSum / answered,
                "avgGrounded", answered == 0 ? 0 : groundSum / answered,
                "avgMs", total == 0 ? 0 : msSum / total,
                "verifiedUsed", verified, "people", askers.size(),
                "helpful", up, "notHelpful", down,
                "helpfulRate", up + down == 0 ? 0 : (double) up / (up + down),
                "daily", daily, "categories", cats, "topDocs", topDocs, "neverCited", neverCited,
                "reviewDue", reviewDue, "hours", hours, "modes", byMode, "confidence", confBands,
                "index", knowledge.stats());
    }
}
