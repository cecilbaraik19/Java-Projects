package com.cecil.codereview.ai;

import com.cecil.codereview.metrics.MethodInfo;
import com.cecil.codereview.model.Category;
import com.cecil.codereview.model.FileReport;
import com.cecil.codereview.model.Grades;
import com.cecil.codereview.model.Issue;
import com.cecil.codereview.model.ReviewReport;
import com.cecil.codereview.model.Severity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline AI engine. It reads all findings and metrics and writes a short
 * reviewer-style summary: verdict, top priorities, hotspots, what is good,
 * and next steps. Works without internet or API keys.
 */
public final class ReviewSummarizer {
    private ReviewSummarizer() { }

    public static List<String> summarize(ReviewReport report) {
        List<String> out = new ArrayList<>();
        double score = report.overallScore();
        Map<Severity, Integer> sev = report.countBySeverity();
        int total = report.allIssues().size();

        out.add(String.format("Verdict: %s (score %.1f/100, grade %s).", Grades.verdict(score), score, report.grade()));
        if (report.previousScore() != null) {
            double diff = score - report.previousScore();
            if (Math.abs(diff) < 0.05) out.add("Trend: score is unchanged since the last review.");
            else out.add(String.format("Trend: score %s by %.1f points since the last review (was %.1f).",
                    diff > 0 ? "improved" : "dropped", Math.abs(diff), report.previousScore()));
        }
        out.add(String.format("Reviewed %d file(s), %d lines of code. Found %d issue(s): %d critical, %d major, %d minor, %d info.",
                report.files().size(), report.totalCodeLines(), total,
                sev.get(Severity.CRITICAL), sev.get(Severity.MAJOR), sev.get(Severity.MINOR), sev.get(Severity.INFO)));

        if (total == 0) {
            out.add("No problems found. The code follows all enabled rules - nice work!");
            return out;
        }

        // ---- top priorities: group by rule, order by severity then count
        Map<String, List<Issue>> byRule = new LinkedHashMap<>();
        for (Issue i : report.allIssues()) byRule.computeIfAbsent(i.ruleId(), k -> new ArrayList<>()).add(i);
        List<List<Issue>> groups = new ArrayList<>(byRule.values());
        groups.sort(Comparator.<List<Issue>>comparingInt(g -> g.get(0).severity().ordinal())
                .thenComparing(g -> -g.size()));
        out.add("Top priorities:");
        int n = 0;
        for (List<Issue> g : groups) {
            if (n == 3) break;
            Issue first = g.get(0);
            if (first.severity() == Severity.INFO && n > 0) break;
            KnowledgeBase.Advice a = KnowledgeBase.forRule(first.ruleId());
            String why = a != null ? a.why : first.message();
            out.add(String.format("  %d. [%s] %s - %d place(s), first at %s:%d. %s", ++n, first.severity(),
                    first.ruleName(), g.size(), first.file(), first.line(), why));
        }

        // ---- security focus
        int security = report.countByCategory().get(Category.SECURITY);
        if (security > 0) {
            out.add("Security: " + security + " security finding(s). Fix these before anything else and never "
                    + "deploy code with CRITICAL security issues.");
        }

        // ---- hotspots
        FileReport worst = report.files().stream().min(Comparator.comparingDouble(FileReport::score)).orElse(null);
        if (worst != null && report.files().size() > 1 && !worst.issues().isEmpty()) {
            out.add(String.format("Hotspot file: %s (score %.1f, %d issues) - start your clean-up here.",
                    worst.file(), worst.score(), worst.issues().size()));
        }
        MethodInfo complex = null;
        String complexFile = null;
        for (FileReport f : report.files()) {
            for (MethodInfo m : f.metrics().methods()) {
                if (complex == null || m.complexity() > complex.complexity()) {
                    complex = m;
                    complexFile = f.file();
                }
            }
        }
        if (complex != null && complex.complexity() > 5) {
            out.add(String.format("Most complex method: %s() in %s (complexity %d, %d lines). "
                    + "It needs the most tests and is the best refactoring candidate.",
                    complex.name(), complexFile, complex.complexity(), complex.length()));
        }

        // ---- positives
        List<String> good = new ArrayList<>();
        if (security == 0) good.add("no security issues detected");
        if (sev.get(Severity.CRITICAL) == 0 && sev.get(Severity.MAJOR) == 0) good.add("no critical or major bugs");
        double avgComment = report.files().stream().mapToDouble(f -> f.metrics().commentRatio()).average().orElse(0);
        if (avgComment >= 0.15) good.add(String.format("good amount of comments (%.0f%%)", avgComment * 100));
        double avgCx = report.files().stream().mapToDouble(f -> f.metrics().averageComplexity()).average().orElse(0);
        if (avgCx > 0 && avgCx <= 4) good.add(String.format("methods are simple on average (complexity %.1f)", avgCx));
        if (!good.isEmpty()) out.add("What is good: " + String.join(", ", good) + ".");

        // ---- next steps
        long fixable = report.allIssues().stream().filter(Issue::autoFixable).count();
        out.add("Next steps:");
        int step = 1;
        if (sev.get(Severity.CRITICAL) > 0) out.add("  " + step++ + ". Fix every CRITICAL issue (security holes first).");
        if (sev.get(Severity.MAJOR) > 0) out.add("  " + step++ + ". Fix MAJOR issues - they are likely real bugs.");
        if (fixable > 0) out.add("  " + step++ + ". Use Auto-fix (or --fix on the command line) to fix " + fixable + " simple issue(s) automatically.");
        out.add("  " + step + ". Add unit tests for the most complex methods, then review again to see the score go up.");
        return out;
    }
}
