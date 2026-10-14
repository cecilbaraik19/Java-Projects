package com.cecil.codereview.model;

/** One problem found in one place of the code. */
public final class Issue implements Comparable<Issue> {
    private final String ruleId;
    private final String ruleName;
    private final Severity severity;
    private final Category category;
    private final String file;
    private final int line;
    private final String message;
    private final String suggestion;
    private final String snippet;
    private final boolean autoFixable;

    public Issue(String ruleId, String ruleName, Severity severity, Category category, String file,
                 int line, String message, String suggestion, String snippet, boolean autoFixable) {
        this.ruleId = ruleId;
        this.ruleName = ruleName;
        this.severity = severity;
        this.category = category;
        this.file = file;
        this.line = line;
        this.message = message;
        this.suggestion = suggestion == null ? "" : suggestion;
        this.snippet = snippet == null ? "" : snippet;
        this.autoFixable = autoFixable;
    }

    public String ruleId() { return ruleId; }
    public String ruleName() { return ruleName; }
    public Severity severity() { return severity; }
    public Category category() { return category; }
    public String file() { return file; }
    public int line() { return line; }
    public String message() { return message; }
    public String suggestion() { return suggestion; }
    public String snippet() { return snippet; }
    public boolean autoFixable() { return autoFixable; }

    @Override
    public int compareTo(Issue o) {
        int c = Integer.compare(severity.ordinal(), o.severity.ordinal());
        if (c != 0) return c;
        c = file.compareTo(o.file);
        if (c != 0) return c;
        return Integer.compare(line, o.line);
    }

    @Override
    public String toString() {
        return "[" + severity + "] " + file + ":" + line + " " + ruleId + " - " + message;
    }
}
