package com.cecil.codereview.analyzer;

import com.cecil.codereview.model.Issue;
import com.cecil.codereview.model.Severity;

import java.util.List;

/**
 * Calculates a 0-100 quality score.
 *
 * Idea: add up the weight of all issues, divide by the size of the file
 * (small files count as 100 lines so one issue does not sink them), and turn that
 * "penalty per 100 lines" into a score with a smooth curve.
 * Any CRITICAL issue caps the score at 69 (grade D) because it must be fixed.
 */
public final class Scorer {
    private Scorer() { }

    public static double score(List<Issue> issues, int codeLines) {
        double penalty = 0;
        boolean critical = false;
        for (Issue i : issues) {
            penalty += i.severity().weight();
            if (i.severity() == Severity.CRITICAL) critical = true;
        }
        double per100 = penalty * 100.0 / Math.max(100, codeLines);
        double score = 100.0 * Math.exp(-per100 / 40.0);
        if (critical) score = Math.min(score, 69.0);
        return Math.round(score * 10) / 10.0;
    }
}
