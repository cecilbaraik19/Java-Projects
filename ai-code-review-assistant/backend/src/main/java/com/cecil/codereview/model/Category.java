package com.cecil.codereview.model;

/** The kind of problem an issue describes. */
public enum Category {
    SECURITY("Security"),
    BUG("Bug Risk"),
    PERFORMANCE("Performance"),
    MAINTAINABILITY("Maintainability"),
    STYLE("Style"),
    BEST_PRACTICE("Best Practice"),
    DOCUMENTATION("Documentation");

    private final String label;

    Category(String label) { this.label = label; }

    public String label() { return label; }
}
