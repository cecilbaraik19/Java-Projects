package com.cecil.codereview.rules;

import com.cecil.codereview.analyzer.ReviewContext;
import com.cecil.codereview.analyzer.SourceFile;
import com.cecil.codereview.model.Category;
import com.cecil.codereview.model.Severity;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A simple rule that reports every line matching a regular expression.
 * It can look at the raw line (with strings and comments) or at the
 * cleaned code line (strings and comments blanked out).
 */
public class LinePatternRule extends BaseRule {
    private final Pattern pattern;
    private final boolean matchRawLine;
    private final String message;
    private final String suggestion;
    private final Set<String> languages;
    private final boolean fixable;

    public LinePatternRule(String id, String name, Category category, Severity severity, String description,
                           String regex, boolean matchRawLine, String message, String suggestion,
                           boolean fixable, String... languages) {
        super(id, name, category, severity, description);
        this.pattern = Pattern.compile(regex);
        this.matchRawLine = matchRawLine;
        this.message = message;
        this.suggestion = suggestion;
        this.fixable = fixable;
        this.languages = new HashSet<>(Arrays.asList(languages));
    }

    @Override
    public boolean autoFixable() { return fixable; }

    @Override
    public boolean appliesTo(SourceFile file) {
        return languages.isEmpty() || languages.contains(file.language());
    }

    @Override
    public void check(SourceFile file, ReviewContext ctx) {
        for (int i = 1; i <= file.lineCount(); i++) {
            // raw matching still ignores lines that are only a comment
            if (matchRawLine && file.codeLine(i).trim().isEmpty()) continue;
            String text = matchRawLine ? file.rawLine(i) : file.codeLine(i);
            if (pattern.matcher(text).find() && extraCheck(file, i)) {
                ctx.report(this, i, message, suggestion);
            }
        }
    }

    /** Subclasses can add an extra condition. */
    protected boolean extraCheck(SourceFile file, int line) {
        return true;
    }
}
