package com.cecil.codereview.model;

/**
 * How serious an issue is. The weight is used when calculating the quality score.
 */
public enum Severity {
    CRITICAL(10.0, "\u001B[1;31m", "Must fix now - security hole or certain bug"),
    MAJOR(5.0, "\u001B[31m", "Should fix - likely bug or serious design problem"),
    MINOR(2.0, "\u001B[33m", "Nice to fix - code smell or maintainability problem"),
    INFO(0.5, "\u001B[36m", "Suggestion - style or small improvement");

    private final double weight;
    private final String ansiColor;
    private final String meaning;

    Severity(double weight, String ansiColor, String meaning) {
        this.weight = weight;
        this.ansiColor = ansiColor;
        this.meaning = meaning;
    }

    public double weight() { return weight; }
    public String ansiColor() { return ansiColor; }
    public String meaning() { return meaning; }

    /** true if this severity is the same or more serious than the other one. */
    public boolean atLeast(Severity other) {
        return this.ordinal() <= other.ordinal();
    }

    public static Severity parse(String text, Severity fallback) {
        if (text == null || text.isBlank()) return fallback;
        try {
            return Severity.valueOf(text.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
