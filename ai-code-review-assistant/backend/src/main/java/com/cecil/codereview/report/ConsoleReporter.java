package com.cecil.codereview.report;

import com.cecil.codereview.metrics.FileMetrics;
import com.cecil.codereview.model.FileReport;
import com.cecil.codereview.model.Issue;
import com.cecil.codereview.model.ReviewReport;
import com.cecil.codereview.model.Severity;

import java.io.PrintStream;
import java.util.Map;

/** Prints the review to the terminal, with colors if wanted. */
public final class ConsoleReporter {
    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String DIM = "\u001B[2m";
    private static final String GREEN = "\u001B[32m";

    private final PrintStream out;
    private final boolean color;
    private final boolean showSuggestions;

    public ConsoleReporter(PrintStream out, boolean color, boolean showSuggestions) {
        this.out = out;
        this.color = color;
        this.showSuggestions = showSuggestions;
    }

    private String c(String code, String text) {
        return color ? code + text + RESET : text;
    }

    public void print(ReviewReport report) {
        String line = "=".repeat(78);
        out.println(c(BOLD, line));
        out.println(c(BOLD, "  AI CODE REVIEW ASSISTANT  -  " + report.reviewMode()));
        out.println(c(BOLD, line));

        for (FileReport f : report.files()) {
            FileMetrics m = f.metrics();
            out.println();
            out.println(c(BOLD, "FILE  " + f.file()) + "   "
                    + gradeColored(f.grade(), f.score())
                    + c(DIM, String.format("   (%d code lines, %d methods, avg complexity %.1f, comments %.0f%%)",
                    m.codeLines(), m.methods().size(), m.averageComplexity(), m.commentRatio() * 100)));
            if (f.issues().isEmpty()) {
                out.println("  " + c(GREEN, "No issues found."));
            }
            for (Issue i : f.issues()) {
                out.println(String.format("  %s %s %s",
                        c(i.severity().ansiColor(), String.format("%-8s", i.severity())),
                        c(DIM, String.format("line %-5d", i.line())),
                        i.message() + c(DIM, "  [" + i.ruleId() + "]" + (i.autoFixable() ? " (auto-fixable)" : ""))));
                if (!i.snippet().isEmpty()) out.println("           " + c(DIM, "> " + i.snippet()));
                if (showSuggestions && !i.suggestion().isEmpty()) {
                    out.println("           " + c(GREEN, "Fix: ") + i.suggestion());
                }
            }
            if (!f.aiReview().isEmpty()) {
                out.println();
                out.println("  " + c(BOLD, "Claude AI review:"));
                for (String l : f.aiReview().split("\n")) out.println("    " + l);
            }
        }

        out.println();
        out.println(c(BOLD, line));
        out.println(c(BOLD, "  SUMMARY"));
        out.println(c(BOLD, line));
        Map<Severity, Integer> counts = report.countBySeverity();
        StringBuilder sb = new StringBuilder("  ");
        for (Severity s : Severity.values()) {
            sb.append(c(s.ansiColor(), s + ": " + counts.get(s))).append("   ");
        }
        out.println(sb);
        out.println("  Overall: " + gradeColored(report.grade(), report.overallScore()));
        out.println();
        out.println(c(BOLD, "  AI Reviewer Summary"));
        for (String s : report.aiSummary()) out.println("  " + s);
        out.println(c(BOLD, line));
    }

    private String gradeColored(String grade, double score) {
        String code;
        switch (grade) {
            case "A": case "B": code = "\u001B[1;32m"; break;
            case "C": code = "\u001B[1;33m"; break;
            default: code = "\u001B[1;31m";
        }
        return c(code, String.format("Grade %s (%.1f/100)", grade, score));
    }
}
