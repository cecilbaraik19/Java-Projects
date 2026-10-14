package com.cecil.codereview.model;

import com.cecil.codereview.metrics.FileMetrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Review result for a single file. */
public final class FileReport {
    private final String file;
    private final String language;
    private final FileMetrics metrics;
    private final List<Issue> issues;
    private final double score;
    private String aiReview = "";

    public FileReport(String file, String language, FileMetrics metrics, List<Issue> issues, double score) {
        this.file = file;
        this.language = language;
        this.metrics = metrics;
        List<Issue> sorted = new ArrayList<>(issues);
        Collections.sort(sorted);
        this.issues = Collections.unmodifiableList(sorted);
        this.score = score;
    }

    public String file() { return file; }
    public String language() { return language; }
    public FileMetrics metrics() { return metrics; }
    public List<Issue> issues() { return issues; }
    public double score() { return score; }
    public String grade() { return Grades.of(score); }
    public String aiReview() { return aiReview; }
    public void setAiReview(String aiReview) { this.aiReview = aiReview == null ? "" : aiReview; }

    public Map<Severity, Integer> countBySeverity() {
        Map<Severity, Integer> map = new EnumMap<>(Severity.class);
        for (Severity s : Severity.values()) map.put(s, 0);
        for (Issue i : issues) map.merge(i.severity(), 1, Integer::sum);
        return map;
    }
}
