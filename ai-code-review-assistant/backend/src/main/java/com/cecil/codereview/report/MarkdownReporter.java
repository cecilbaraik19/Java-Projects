package com.cecil.codereview.report;

import com.cecil.codereview.model.FileReport;
import com.cecil.codereview.model.Issue;
import com.cecil.codereview.model.ReviewReport;
import com.cecil.codereview.model.Severity;

import java.util.Map;

/** Markdown report - paste it as a GitHub pull request comment. */
public final class MarkdownReporter {
    private MarkdownReporter() { }

    public static String render(ReviewReport r) {
        StringBuilder sb = new StringBuilder();
        sb.append("## AI Code Review - Grade ").append(r.grade())
                .append(String.format(" (%.1f/100)%n%n", r.overallScore()));
        Map<Severity, Integer> c = r.countBySeverity();
        sb.append("| Critical | Major | Minor | Info |\n|:-:|:-:|:-:|:-:|\n");
        sb.append(String.format("| %d | %d | %d | %d |%n%n", c.get(Severity.CRITICAL), c.get(Severity.MAJOR),
                c.get(Severity.MINOR), c.get(Severity.INFO)));
        sb.append("### Reviewer summary\n\n");
        for (String s : r.aiSummary()) {
            String t = s.trim();
            sb.append(t.matches("\\d+\\..*") ? t : "- " + t).append('\n');
        }
        sb.append('\n');
        for (FileReport f : r.files()) {
            sb.append(String.format("### `%s` - %s (%.1f)%n%n", f.file(), f.grade(), f.score()));
            if (f.issues().isEmpty()) {
                sb.append("No issues found.\n\n");
                continue;
            }
            sb.append("| Severity | Line | Rule | Issue | Suggested fix |\n|---|---:|---|---|---|\n");
            for (Issue i : f.issues()) {
                sb.append("| ").append(icon(i.severity())).append(' ').append(i.severity())
                        .append(" | ").append(i.line())
                        .append(" | `").append(i.ruleId()).append('`')
                        .append(" | ").append(cell(i.message()))
                        .append(" | ").append(cell(i.suggestion())).append(" |\n");
            }
            if (!f.aiReview().isEmpty()) {
                sb.append("\n<details><summary>Claude AI review</summary>\n\n").append(f.aiReview()).append("\n\n</details>\n");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String icon(Severity s) {
        switch (s) {
            case CRITICAL: return "\uD83D\uDD34";
            case MAJOR: return "\uD83D\uDFE0";
            case MINOR: return "\uD83D\uDFE1";
            default: return "\uD83D\uDD35";
        }
    }

    private static String cell(String s) {
        return s.replace("|", "\\|").replace("\n", " ");
    }
}
