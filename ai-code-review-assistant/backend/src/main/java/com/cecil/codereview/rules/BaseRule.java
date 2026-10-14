package com.cecil.codereview.rules;

import com.cecil.codereview.analyzer.Rule;
import com.cecil.codereview.model.Category;
import com.cecil.codereview.model.Severity;

/** Stores the common fields so each rule only has to write check(). */
public abstract class BaseRule implements Rule {
    private final String id;
    private final String name;
    private final String description;
    private final Category category;
    private final Severity severity;

    protected BaseRule(String id, String name, Category category, Severity severity, String description) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.severity = severity;
        this.description = description;
    }

    @Override public String id() { return id; }
    @Override public String name() { return name; }
    @Override public String description() { return description; }
    @Override public Category category() { return category; }
    @Override public Severity defaultSeverity() { return severity; }
}
