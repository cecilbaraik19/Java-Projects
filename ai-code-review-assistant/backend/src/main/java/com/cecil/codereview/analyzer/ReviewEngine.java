package com.cecil.codereview.analyzer;

import com.cecil.codereview.config.ReviewConfig;
import com.cecil.codereview.model.FileReport;
import com.cecil.codereview.model.Issue;
import com.cecil.codereview.model.ReviewReport;
import com.cecil.codereview.rules.RuleRegistry;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs every enabled rule on every file and builds the report. */
public final class ReviewEngine {
    private static final Set<String> SKIP_DIRS = new HashSet<>(Arrays.asList(
            ".git", ".idea", ".vscode", "target", "build", "out", "bin", "node_modules", "dist",
            "review-output", "__pycache__", ".gradle", "venv", ".venv"));
    private static final Pattern IGNORE = Pattern.compile("review-ignore(?!-file)(?::\\s*([\\w,\\s]+))?");

    private final ReviewConfig config;
    private final List<Rule> rules;
    /** Optional: only keep issues on these lines (used by --changed-lines-only). */
    private Map<String, Set<Integer>> changedLines;

    public ReviewEngine(ReviewConfig config) {
        this(config, RuleRegistry.allRules());
    }

    public ReviewEngine(ReviewConfig config, List<Rule> rules) {
        this.config = config;
        this.rules = rules;
    }

    public ReviewConfig config() { return config; }
    public List<Rule> rules() { return rules; }

    public void setChangedLines(Map<String, Set<Integer>> changedLines) {
        this.changedLines = changedLines;
    }

    /** Finds every reviewable file under the given files/folders. */
    public List<Path> collectFiles(Collection<Path> inputs) throws IOException {
        List<Path> result = new ArrayList<>();
        for (Path input : inputs) {
            if (Files.isRegularFile(input)) {
                result.add(input);
            } else if (Files.isDirectory(input)) {
                Files.walkFileTree(input, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        Path name = dir.getFileName();
                        if (name != null && !dir.equals(input) && SKIP_DIRS.contains(name.toString())) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (hasIncludedExtension(file)) result.add(file);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } else {
                throw new IOException("Not found: " + input);
            }
        }
        result.sort(null);
        return result;
    }

    private boolean hasIncludedExtension(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && config.includeExtensions().contains(name.substring(dot + 1));
    }

    /** Reviews files/folders and returns the full report. */
    public ReviewReport review(Collection<Path> inputs, Path baseDir) throws IOException {
        List<FileReport> reports = new ArrayList<>();
        for (Path p : collectFiles(inputs)) {
            String display = displayName(p, baseDir);
            reports.add(reviewFile(SourceFile.fromPath(p, display)));
        }
        return new ReviewReport(reports);
    }

    static String displayName(Path p, Path baseDir) {
        try {
            if (baseDir != null) {
                Path rel = baseDir.toAbsolutePath().normalize().relativize(p.toAbsolutePath().normalize());
                if (!rel.toString().startsWith("..")) return rel.toString().replace('\\', '/');
            }
        } catch (IllegalArgumentException ignored) {
            // different drive on Windows - fall back to the full path below
        }
        return p.toString().replace('\\', '/');
    }

    /** Reviews one already loaded file. */
    public FileReport reviewFile(SourceFile file) {
        ReviewContext ctx = new ReviewContext(config, file);
        if (!file.rawText().contains("review-ignore-file")) {
            for (Rule rule : rules) {
                if (!config.isRuleEnabled(rule.id()) || !rule.appliesTo(file)) continue;
                try {
                    rule.check(file, ctx);
                } catch (RuntimeException e) {
                    // one broken rule must never stop the whole review
                    System.err.println("Rule " + rule.id() + " failed on " + file.displayName() + ": " + e);
                }
            }
        }
        List<Issue> kept = new ArrayList<>();
        Set<Integer> changed = changedLines == null ? null : changedLines.get(file.displayName());
        for (Issue issue : ctx.issues()) {
            if (!issue.severity().atLeast(config.minSeverity())) continue;
            if (isSuppressed(file, issue)) continue;
            if (changedLines != null && (changed == null || !changed.contains(issue.line()))) continue;
            kept.add(issue);
        }
        double score = Scorer.score(kept, file.metrics().codeLines());
        return new FileReport(file.displayName(), file.language(), file.metrics(), kept, score);
    }

    /** Supports "// review-ignore" and "// review-ignore: RULE_A, RULE_B" on the line or the line above. */
    private boolean isSuppressed(SourceFile file, Issue issue) {
        if (matchesIgnore(file.rawLine(issue.line()), issue.ruleId())) return true;
        int above = issue.line() - 1;
        return above >= 1 && file.codeLine(above).trim().isEmpty() && matchesIgnore(file.rawLine(above), issue.ruleId());
    }

    private static boolean matchesIgnore(String rawLine, String ruleId) {
        Matcher m = IGNORE.matcher(rawLine);
        if (!m.find()) return false;
        if (m.group(1) == null || m.group(1).isBlank()) return true;
        for (String id : m.group(1).split("[,\\s]+")) {
            if (id.equalsIgnoreCase(ruleId)) return true;
        }
        return false;
    }
}
