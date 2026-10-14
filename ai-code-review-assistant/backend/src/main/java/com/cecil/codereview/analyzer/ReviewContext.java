package com.cecil.codereview.analyzer;

import com.cecil.codereview.config.ReviewConfig;
import com.cecil.codereview.model.Issue;
import com.cecil.codereview.model.Severity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Holds the config and collects the issues while one file is being checked. */
public final class ReviewContext {
    private final ReviewConfig config;
    private final SourceFile file;
    private final List<Issue> issues = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();

    public ReviewContext(ReviewConfig config, SourceFile file) {
        this.config = config;
        this.file = file;
    }

    public ReviewConfig config() { return config; }

    public List<Issue> issues() { return issues; }

    /** Adds one issue. The same rule is reported at most once per line. */
    public void report(Rule rule, int line, String message, String suggestion) {
        report(rule, line, message, suggestion, rule.defaultSeverity());
    }

    /** Same as above, but the rule chooses the severity for this one finding. */
    public void report(Rule rule, int line, String message, String suggestion, Severity severity) {
        if (!seen.add(rule.id() + "@" + line)) return;
        Severity sev = config.severityFor(rule.id(), severity);
        String snippet = file.rawLine(line).trim();
        if (snippet.length() > 160) snippet = snippet.substring(0, 157) + "...";
        issues.add(new Issue(rule.id(), rule.name(), sev, rule.category(), file.displayName(),
                line, message, suggestion, snippet, rule.autoFixable()));
    }
}
