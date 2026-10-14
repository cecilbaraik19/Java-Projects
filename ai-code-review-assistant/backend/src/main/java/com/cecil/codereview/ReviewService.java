package com.cecil.codereview;

import com.cecil.codereview.ai.AutoFixer;
import com.cecil.codereview.ai.ClaudeReviewer;
import com.cecil.codereview.ai.ReviewSummarizer;
import com.cecil.codereview.analyzer.ReviewEngine;
import com.cecil.codereview.analyzer.SourceFile;
import com.cecil.codereview.config.ReviewConfig;
import com.cecil.codereview.model.FileReport;
import com.cecil.codereview.model.ReviewReport;
import com.cecil.codereview.report.HistoryTracker;
import com.cecil.codereview.report.HtmlReporter;
import com.cecil.codereview.report.JsonReporter;
import com.cecil.codereview.report.MarkdownReporter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Glue code: review -> AI review -> summary -> history. Used by both the CLI and the GUI. */
public final class ReviewService {
    private final ReviewEngine engine;
    private final ClaudeReviewer claude;
    private final Map<String, SourceFile> sources = new LinkedHashMap<>();

    public ReviewService(ReviewConfig config, ClaudeReviewer claude) {
        this.engine = new ReviewEngine(config);
        this.claude = claude;
    }

    public ReviewEngine engine() { return engine; }

    /** The loaded source of each reviewed file (by display name). */
    public Map<String, SourceFile> sources() { return sources; }

    public ReviewReport reviewPaths(Collection<Path> inputs, Path baseDir, Consumer<String> log) throws IOException {
        sources.clear();
        List<FileReport> reports = new ArrayList<>();
        List<Path> files = engine.collectFiles(inputs);
        if (files.isEmpty()) log.accept("No files to review (looking for: " + engine.config().includeExtensions() + ").");
        for (Path p : files) {
            String name = displayName(p, baseDir);
            SourceFile sf = SourceFile.fromPath(p, name);
            sources.put(name, sf);
            reports.add(engine.reviewFile(sf));
        }
        return finish(new ReviewReport(reports), log);
    }

    public ReviewReport reviewText(String fileName, String text, Consumer<String> log) {
        sources.clear();
        SourceFile sf = SourceFile.fromText(fileName, text);
        sources.put(fileName, sf);
        List<FileReport> reports = new ArrayList<>();
        reports.add(engine.reviewFile(sf));
        return finish(new ReviewReport(reports), log);
    }

    /** Reviews files that were sent from the website (name + content). */
    public ReviewReport reviewSources(List<SourceFile> files, Consumer<String> log) {
        sources.clear();
        List<FileReport> reports = new ArrayList<>();
        for (SourceFile sf : files) {
            sources.put(sf.displayName(), sf);
            reports.add(engine.reviewFile(sf));
        }
        return finish(new ReviewReport(reports), log);
    }

    private ReviewReport finish(ReviewReport report, Consumer<String> log) {
        if (claude != null) {
            report.setReviewMode("Static analysis + Claude AI (" + claude.model() + ")");
            for (FileReport f : report.files()) {
                log.accept("Asking Claude to review " + f.file() + " ...");
                try {
                    f.setAiReview(claude.reviewFile(f, sources.get(f.file()).rawText()));
                } catch (IOException e) {
                    log.accept("  Claude review skipped: " + e.getMessage());
                    report.setReviewMode("Static analysis + offline AI engine (Claude unavailable)");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } else {
            report.setReviewMode("Static analysis + offline AI engine");
        }
        return report;
    }

    /** Adds the summary (needs the previous score, so history is read first). */
    public void summarize(ReviewReport report, Path outDir, String target, boolean recordHistory) {
        if (outDir != null) {
            HistoryTracker history = new HistoryTracker(outDir);
            report.setPreviousScore(history.previousScore(target));
            report.aiSummary().clear();
            report.aiSummary().addAll(ReviewSummarizer.summarize(report));
            if (recordHistory) {
                try {
                    history.record(target, report);
                } catch (IOException e) {
                    System.err.println("Could not update history: " + e.getMessage());
                }
            }
        } else {
            report.aiSummary().clear();
            report.aiSummary().addAll(ReviewSummarizer.summarize(report));
        }
    }

    /** Writes the chosen report formats. Returns the written files. */
    public List<Path> writeReports(ReviewReport report, Path outDir, List<String> formats) throws IOException {
        Files.createDirectories(outDir);
        List<Path> written = new ArrayList<>();
        for (String f : formats) {
            switch (f) {
                case "html":
                    written.add(write(outDir.resolve("review-report.html"), HtmlReporter.render(report)));
                    break;
                case "json":
                    written.add(write(outDir.resolve("review-report.json"), JsonReporter.render(report)));
                    break;
                case "md":
                    written.add(write(outDir.resolve("review-report.md"), MarkdownReporter.render(report)));
                    break;
                default:
                    // console is printed, not written
            }
        }
        return written;
    }

    /** Applies safe auto-fixes. If inPlace is false the fixed copies go to outDir/fixed/. */
    public List<String> applyFixes(ReviewReport report, Path outDir, boolean inPlace) throws IOException {
        List<String> log = new ArrayList<>();
        for (FileReport f : report.files()) {
            SourceFile sf = sources.get(f.file());
            if (sf == null) continue;
            AutoFixer.FixResult r = AutoFixer.fix(sf, f.issues());
            if (!r.changed()) continue;
            Path target;
            if (inPlace && sf.path() != null) {
                target = sf.path();
                Files.copy(target, target.resolveSibling(target.getFileName() + ".bak"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else {
                target = outDir.resolve("fixed").resolve(f.file().replace("..", "_"));
            }
            Files.createDirectories(target.toAbsolutePath().getParent());
            Files.write(target, r.fixedText.getBytes(StandardCharsets.UTF_8));
            log.add(f.file() + " -> " + target + " (" + r.changes.size() + " change(s))");
            for (AutoFixer.Change c : r.changes) {
                String where = c.line > 0 ? "line " + c.line : "file";
                log.add("   " + where + " [" + c.ruleId + "]");
                log.add("     - " + c.before.trim());
                log.add("     + " + c.after.trim());
            }
        }
        if (log.isEmpty()) log.add("Nothing to auto-fix.");
        return log;
    }

    private static Path write(Path p, String content) throws IOException {
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
        return p;
    }

    static String displayName(Path p, Path baseDir) {
        try {
            if (baseDir != null) {
                Path rel = baseDir.toAbsolutePath().normalize().relativize(p.toAbsolutePath().normalize());
                if (!rel.toString().startsWith("..") && !rel.toString().isEmpty()) return rel.toString().replace('\\', '/');
            }
        } catch (IllegalArgumentException ignored) {
            // different drive letters on Windows: use the full path
        }
        return p.toString().replace('\\', '/');
    }
}
