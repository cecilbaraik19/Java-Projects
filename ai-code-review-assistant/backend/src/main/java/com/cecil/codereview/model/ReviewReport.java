package com.cecil.codereview.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Review result for a whole run (one or many files). */
public final class ReviewReport {
    private final List<FileReport> files;
    private final LocalDateTime createdAt = LocalDateTime.now();
    private final List<String> aiSummary = new ArrayList<>();
    private String reviewMode = "Offline AI engine";
    private Double previousScore;

    public ReviewReport(List<FileReport> files) {
        this.files = Collections.unmodifiableList(new ArrayList<>(files));
    }

    public List<FileReport> files() { return files; }
    public LocalDateTime createdAt() { return createdAt; }
    public List<String> aiSummary() { return aiSummary; }
    public String reviewMode() { return reviewMode; }
    public void setReviewMode(String mode) { this.reviewMode = mode; }
    public Double previousScore() { return previousScore; }
    public void setPreviousScore(Double previousScore) { this.previousScore = previousScore; }

    public List<Issue> allIssues() {
        List<Issue> all = new ArrayList<>();
        for (FileReport f : files) all.addAll(f.issues());
        Collections.sort(all);
        return all;
    }

    public int totalCodeLines() {
        return files.stream().mapToInt(f -> f.metrics().codeLines()).sum();
    }

    /** Overall score = average of file scores weighted by code lines. */
    public double overallScore() {
        if (files.isEmpty()) return 100;
        double weighted = 0;
        double weights = 0;
        for (FileReport f : files) {
            double w = Math.max(1, f.metrics().codeLines());
            weighted += f.score() * w;
            weights += w;
        }
        return weighted / weights;
    }

    public String grade() { return Grades.of(overallScore()); }

    public Map<Severity, Integer> countBySeverity() {
        Map<Severity, Integer> map = new EnumMap<>(Severity.class);
        for (Severity s : Severity.values()) map.put(s, 0);
        for (FileReport f : files) {
            f.countBySeverity().forEach((k, v) -> map.merge(k, v, Integer::sum));
        }
        return map;
    }

    public Map<Category, Integer> countByCategory() {
        Map<Category, Integer> map = new LinkedHashMap<>();
        for (Category c : Category.values()) map.put(c, 0);
        for (Issue i : allIssues()) map.merge(i.category(), 1, Integer::sum);
        return map;
    }
}
