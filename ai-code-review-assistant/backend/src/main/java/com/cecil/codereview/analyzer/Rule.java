package com.cecil.codereview.analyzer;

import com.cecil.codereview.model.Category;
import com.cecil.codereview.model.Severity;

/** One code review check. Every rule looks at a file and reports issues. */
public interface Rule {
    /** Short unique id, e.g. SQL_INJECTION. */
    String id();

    /** Human friendly name. */
    String name();

    /** What the rule looks for and why. */
    String description();

    Category category();

    Severity defaultSeverity();

    /** Can AutoFixer repair this issue automatically? */
    default boolean autoFixable() { return false; }

    /** Most rules only make sense for Java. Generic rules override this. */
    default boolean appliesTo(SourceFile file) { return file.isJava(); }

    void check(SourceFile file, ReviewContext ctx);
}
