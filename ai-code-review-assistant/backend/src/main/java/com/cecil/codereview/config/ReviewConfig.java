package com.cecil.codereview.config;

import com.cecil.codereview.model.Severity;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * All settings for a review. Values come from defaults, then from a
 * .codereview.properties file, then from command line options.
 */
public final class ReviewConfig {
    public static final String DEFAULT_FILE_NAME = ".codereview.properties";

    private int maxMethodLines = 50;
    private int maxComplexity = 10;
    private int maxParameters = 5;
    private int maxLineLength = 120;
    private int maxNesting = 4;
    private int maxClassLines = 500;
    private int duplicateBlockLines = 6;
    private Severity minSeverity = Severity.INFO;
    private Severity failOn = null;
    private final Set<String> disabledRules = new HashSet<>();
    private final Set<String> includeExtensions =
            new HashSet<>(Arrays.asList("java", "js", "ts", "py"));
    private final Properties raw = new Properties();

    public static ReviewConfig defaults() {
        return new ReviewConfig();
    }

    /** Loads settings from a properties file on top of the defaults. */
    public static ReviewConfig load(Path file) throws IOException {
        ReviewConfig c = new ReviewConfig();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            c.raw.load(r);
        }
        c.maxMethodLines = c.intProp("max.method.lines", c.maxMethodLines);
        c.maxComplexity = c.intProp("max.complexity", c.maxComplexity);
        c.maxParameters = c.intProp("max.parameters", c.maxParameters);
        c.maxLineLength = c.intProp("max.line.length", c.maxLineLength);
        c.maxNesting = c.intProp("max.nesting", c.maxNesting);
        c.maxClassLines = c.intProp("max.class.lines", c.maxClassLines);
        c.duplicateBlockLines = c.intProp("duplicate.block.lines", c.duplicateBlockLines);
        c.minSeverity = Severity.parse(c.raw.getProperty("min.severity"), c.minSeverity);
        c.failOn = Severity.parse(c.raw.getProperty("fail.on"), null);
        String disabled = c.raw.getProperty("disabled.rules", "");
        for (String id : disabled.split(",")) {
            if (!id.isBlank()) c.disabledRules.add(id.trim().toUpperCase(Locale.ROOT));
        }
        String ext = c.raw.getProperty("include.extensions");
        if (ext != null && !ext.isBlank()) {
            c.includeExtensions.clear();
            c.includeExtensions.addAll(Arrays.stream(ext.split(","))
                    .map(s -> s.trim().toLowerCase(Locale.ROOT).replace(".", ""))
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet()));
        }
        return c;
    }

    private int intProp(String key, int fallback) {
        String v = raw.getProperty(key);
        if (v == null) return fallback;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Lets the user change the severity of one rule: severity.RULE_ID=MAJOR */
    public Severity severityFor(String ruleId, Severity defaultSeverity) {
        return Severity.parse(raw.getProperty("severity." + ruleId), defaultSeverity);
    }

    public boolean isRuleEnabled(String ruleId) {
        return !disabledRules.contains(ruleId.toUpperCase(Locale.ROOT));
    }

    public void disableRule(String ruleId) { disabledRules.add(ruleId.toUpperCase(Locale.ROOT)); }

    public int maxMethodLines() { return maxMethodLines; }
    public int maxComplexity() { return maxComplexity; }
    public int maxParameters() { return maxParameters; }
    public int maxLineLength() { return maxLineLength; }
    public int maxNesting() { return maxNesting; }
    public int maxClassLines() { return maxClassLines; }
    public int duplicateBlockLines() { return duplicateBlockLines; }
    public Severity minSeverity() { return minSeverity; }
    public Severity failOn() { return failOn; }
    public Set<String> disabledRules() { return disabledRules; }
    public Set<String> includeExtensions() { return includeExtensions; }

    /**
     * Changes one number limit by name (used by the website settings panel).
     * Values below 1 are ignored. Returns false for an unknown name.
     */
    public boolean setLimit(String name, int value) {
        if (value < 1) return false;
        switch (name) {
            case "maxMethodLines": maxMethodLines = value; return true;
            case "maxComplexity": maxComplexity = value; return true;
            case "maxParameters": maxParameters = value; return true;
            case "maxLineLength": maxLineLength = value; return true;
            case "maxNesting": maxNesting = value; return true;
            case "maxClassLines": maxClassLines = value; return true;
            case "duplicateBlockLines": duplicateBlockLines = value; return true;
            default: return false;
        }
    }

    public void setMinSeverity(Severity s) { this.minSeverity = s; }
    public void setFailOn(Severity s) { this.failOn = s; }
}
