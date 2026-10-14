package com.cecil.codereview.model;

/** Turns a 0-100 score into a letter grade. */
public final class Grades {
    private Grades() { }

    public static String of(double score) {
        if (score >= 90) return "A";
        if (score >= 80) return "B";
        if (score >= 70) return "C";
        if (score >= 60) return "D";
        return "F";
    }

    public static String verdict(double score) {
        if (score >= 90) return "Excellent - ready to merge";
        if (score >= 80) return "Good - small fixes recommended";
        if (score >= 70) return "Fair - fix the important issues before merging";
        if (score >= 60) return "Poor - needs real changes";
        return "Failing - do not merge yet";
    }
}
