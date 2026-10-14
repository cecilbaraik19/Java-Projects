package com.cecil.codereview;

import com.cecil.codereview.ai.ClaudeReviewer;
import com.cecil.codereview.ai.KnowledgeBase;
import com.cecil.codereview.analyzer.Rule;
import com.cecil.codereview.config.ReviewConfig;
import com.cecil.codereview.git.GitChanges;
import com.cecil.codereview.model.Category;
import com.cecil.codereview.model.Issue;
import com.cecil.codereview.model.ReviewReport;
import com.cecil.codereview.model.Severity;
import com.cecil.codereview.report.ConsoleReporter;
import com.cecil.codereview.rules.RuleRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

/**
 * Command line version of the reviewer (the web app is started by {@link App}).
 *
 *   java -jar code-review.jar --cli                 (interactive menu)
 *   java -jar code-review.jar --cli ..\samples      (review a folder)
 *   java -jar code-review.jar --cli --help          (all options)
 */
public final class Cli {
    public static final String VERSION = "1.0.0";

    private Cli() { }

    /** Parsed command line options. */
    static final class Options {
        final List<Path> paths = new ArrayList<>();
        List<String> formats = new ArrayList<>(Arrays.asList("console", "html"));
        Path outDir = Path.of("review-output");
        Path configFile;
        Severity minSeverity;
        Severity failOn;
        final List<String> disabled = new ArrayList<>();
        boolean fix;
        boolean fixInPlace;
        boolean ai;
        boolean git;
        String gitBase;
        boolean changedLinesOnly;
        boolean color = System.console() != null && System.getenv("NO_COLOR") == null;
        boolean suggestions = true;
        boolean history = true;
    }

    /** Entry point used by App when started with --cli. */
    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println("Use --help to see all options.");
            System.exit(1);
        }
    }

    static int run(String[] args) {
        if (args.length == 0) return interactiveMenu();
        Options o = new Options();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-h": case "--help": printHelp(); return 0;
                case "-v": case "--version": System.out.println("AI Code Review Assistant " + VERSION); return 0;
                case "--list-rules": listRules(); return 0;
                case "--explain": explain(need(args, ++i, a)); return 0;
                case "-f": case "--format": o.formats = parseFormats(need(args, ++i, a)); break;
                case "-o": case "--out": o.outDir = Path.of(need(args, ++i, a)); break;
                case "-c": case "--config": o.configFile = Path.of(need(args, ++i, a)); break;
                case "--min-severity": o.minSeverity = severity(need(args, ++i, a)); break;
                case "--fail-on": o.failOn = severity(need(args, ++i, a)); break;
                case "--disable": o.disabled.addAll(Arrays.asList(need(args, ++i, a).split(","))); break;
                case "--fix": o.fix = true; break;
                case "--fix-in-place": o.fix = true; o.fixInPlace = true; break;
                case "--ai": o.ai = true; break;
                case "--git": o.git = true; break;
                case "--base": o.git = true; o.gitBase = need(args, ++i, a); break;
                case "--changed-lines-only": o.git = true; o.changedLinesOnly = true; break;
                case "--no-color": o.color = false; break;
                case "--color": o.color = true; break;
                case "-q": case "--quiet": o.suggestions = false; break;
                case "--no-history": o.history = false; break;
                default:
                    if (a.startsWith("-")) throw new IllegalArgumentException("Unknown option " + a);
                    o.paths.add(Path.of(a));
            }
        }
        return review(o);
    }

    // ------------------------------------------------------------------ review

    static int review(Options o) {
        try {
            Path base = baseDir(o.paths);
            ReviewConfig config = loadConfig(o, base);
            ClaudeReviewer claude = null;
            if (o.ai) {
                claude = ClaudeReviewer.fromEnvironment();
                if (claude == null) {
                    System.out.println("Note: --ai needs the ANTHROPIC_API_KEY environment variable. Using the offline AI engine.");
                }
            }
            ReviewService service = new ReviewService(config, claude);

            List<Path> inputs = new ArrayList<>(o.paths);
            if (o.git) {
                GitChanges git = new GitChanges(o.paths.isEmpty() ? Path.of(".") : o.paths.get(0), o.gitBase);
                base = git.repoRoot();
                inputs = git.changedFiles();
                System.out.println("Git mode: " + inputs.size() + " changed file(s)"
                        + (o.gitBase != null ? " since " + o.gitBase : " (uncommitted)") + ".");
                if (o.changedLinesOnly) {
                    Map<String, Set<Integer>> lines = git.changedLines();
                    service.engine().setChangedLines(lines);
                }
            } else if (inputs.isEmpty()) {
                throw new IllegalArgumentException("Give a file or folder to review, e.g.  cli.bat ..\\samples");
            }

            ReviewReport report = service.reviewPaths(inputs, base, System.out::println);
            String target = o.git ? "git:" + (o.gitBase == null ? "working-tree" : o.gitBase) : String.join(";", strings(o.paths));
            service.summarize(report, o.outDir, target, o.history);

            if (o.formats.contains("console")) new ConsoleReporter(System.out, o.color, o.suggestions).print(report);
            List<Path> written = service.writeReports(report, o.outDir, o.formats);
            for (Path p : written) System.out.println("Report written: " + p.toAbsolutePath());

            if (o.fix) {
                System.out.println();
                System.out.println("Auto-fix " + (o.fixInPlace ? "(in place, .bak backups created)" : "(copies in " + o.outDir.resolve("fixed") + ")") + ":");
                for (String l : service.applyFixes(report, o.outDir, o.fixInPlace)) System.out.println("  " + l);
            }

            Severity failOn = o.failOn != null ? o.failOn : config.failOn();
            if (failOn != null) {
                long bad = report.allIssues().stream().filter(i -> i.severity().atLeast(failOn)).count();
                if (bad > 0) {
                    System.out.println("Quality gate FAILED: " + bad + " issue(s) at " + failOn + " or above.");
                    return 2;
                }
                System.out.println("Quality gate passed.");
            }
            return 0;
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private static Path baseDir(List<Path> paths) {
        if (paths.size() == 1) {
            Path p = paths.get(0).toAbsolutePath().normalize();
            return Files.isDirectory(p) ? p : p.getParent();
        }
        return Path.of("").toAbsolutePath();
    }

    private static ReviewConfig loadConfig(Options o, Path base) throws IOException {
        ReviewConfig config;
        if (o.configFile != null) {
            config = ReviewConfig.load(o.configFile);
        } else if (base != null && Files.isRegularFile(base.resolve(ReviewConfig.DEFAULT_FILE_NAME))) {
            config = ReviewConfig.load(base.resolve(ReviewConfig.DEFAULT_FILE_NAME));
        } else if (Files.isRegularFile(Path.of(ReviewConfig.DEFAULT_FILE_NAME))) {
            config = ReviewConfig.load(Path.of(ReviewConfig.DEFAULT_FILE_NAME));
        } else {
            config = ReviewConfig.defaults();
        }
        if (o.minSeverity != null) config.setMinSeverity(o.minSeverity);
        if (o.failOn != null) config.setFailOn(o.failOn);
        for (String d : o.disabled) if (!d.isBlank()) config.disableRule(d.trim());
        return config;
    }

    private static List<String> strings(List<Path> paths) {
        List<String> s = new ArrayList<>();
        for (Path p : paths) s.add(p.toAbsolutePath().normalize().toString());
        return s;
    }

    private static String need(String[] args, int i, String option) {
        if (i >= args.length) throw new IllegalArgumentException(option + " needs a value");
        return args[i];
    }

    private static Severity severity(String s) {
        Severity v = Severity.parse(s, null);
        if (v == null) throw new IllegalArgumentException("Severity must be CRITICAL, MAJOR, MINOR or INFO");
        return v;
    }

    private static List<String> parseFormats(String s) {
        List<String> f = new ArrayList<>();
        for (String part : s.toLowerCase(Locale.ROOT).split(",")) {
            String p = part.trim();
            if (p.equals("all")) return new ArrayList<>(Arrays.asList("console", "html", "json", "md"));
            if (p.equals("markdown")) p = "md";
            if (!Arrays.asList("console", "html", "json", "md").contains(p)) {
                throw new IllegalArgumentException("Unknown format '" + p + "' (use console, html, json, md or all)");
            }
            f.add(p);
        }
        return f;
    }

    // ------------------------------------------------------------------- rules

    static void listRules() {
        List<Rule> rules = RuleRegistry.allRules();
        System.out.println(rules.size() + " rules:\n");
        for (Category c : Category.values()) {
            System.out.println("== " + c.label() + " ==");
            for (Rule r : rules) {
                if (r.category() != c) continue;
                System.out.printf("  %-26s %-8s %s%s%n", r.id(), r.defaultSeverity(), r.name(),
                        r.autoFixable() ? "  (auto-fix)" : "");
            }
            System.out.println();
        }
        System.out.println("Use --explain RULE_ID for details.");
    }

    static void explain(String id) {
        Rule r = RuleRegistry.find(id);
        if (r == null) {
            System.out.println("No rule called " + id + ". Use --list-rules.");
            return;
        }
        System.out.println(r.id() + " - " + r.name());
        System.out.println("Category: " + r.category().label() + "   Default severity: " + r.defaultSeverity());
        System.out.println();
        System.out.println(r.description());
        KnowledgeBase.Advice a = KnowledgeBase.forRule(r.id());
        if (a != null) {
            System.out.println();
            System.out.println("Why it matters: " + a.why);
            System.out.println("\nBefore:\n" + indent(a.before) + "\nAfter:\n" + indent(a.after));
        }
    }

    private static String indent(String s) {
        return "    " + s.replace("\n", "\n    ");
    }

    // -------------------------------------------------------------------- menu

    static int interactiveMenu() {
        Scanner in = new Scanner(System.in);
        boolean color = System.console() != null;
        while (true) {
            System.out.println();
            System.out.println("==============================================");
            System.out.println("        AI CODE REVIEW ASSISTANT v" + VERSION);
            System.out.println("==============================================");
            System.out.println(" 1. Review a file or folder");
            System.out.println(" 2. Paste code and review it");
            System.out.println(" 3. Review my Git changes");
            System.out.println(" 4. List all rules");
            System.out.println(" 5. Explain a rule");
            System.out.println(" 0. Exit");
            System.out.print("Choose: ");
            if (!in.hasNextLine()) return 0;
            String choice = in.nextLine().trim();
            switch (choice) {
                case "1": {
                    System.out.print("Path to file or folder: ");
                    String p = in.nextLine().trim().replace("\"", "");
                    Options o = new Options();
                    o.paths.add(Path.of(p));
                    o.color = color;
                    System.out.print("Apply safe auto-fixes too? (y/N): ");
                    o.fix = in.nextLine().trim().equalsIgnoreCase("y");
                    review(o);
                    break;
                }
                case "2": {
                    System.out.print("File name with extension (e.g. Test.java): ");
                    String name = in.nextLine().trim();
                    if (name.isEmpty()) name = "Pasted.java";
                    System.out.println("Paste the code. Type END on its own line when finished.");
                    StringBuilder sb = new StringBuilder();
                    while (in.hasNextLine()) {
                        String line = in.nextLine();
                        if (line.trim().equals("END")) break;
                        sb.append(line).append('\n');
                    }
                    ReviewService s = new ReviewService(ReviewConfig.defaults(), null);
                    ReviewReport r = s.reviewText(name, sb.toString(), System.out::println);
                    s.summarize(r, null, name, false);
                    new ConsoleReporter(System.out, color, true).print(r);
                    break;
                }
                case "3": {
                    Options o = new Options();
                    o.git = true;
                    o.color = color;
                    System.out.print("Compare with branch (blank = uncommitted changes): ");
                    String b = in.nextLine().trim();
                    if (!b.isEmpty()) o.gitBase = b;
                    review(o);
                    break;
                }
                case "4": listRules(); break;
                case "5": {
                    System.out.print("Rule id: ");
                    explain(in.nextLine().trim());
                    break;
                }
                case "0": return 0;
                default: System.out.println("Please type a number from the menu.");
            }
        }
    }

    static void printHelp() {
        System.out.println(String.join("\n",
                "AI Code Review Assistant " + VERSION + " - automated code review for Java (and JS / TS / Python)",
                "",
                "Usage:  cli.bat [paths...] [options]      (or: java -jar code-review.jar --cli ...)",
                "        (no arguments = interactive menu;  run.bat starts the web app instead)",
                "",
                "What to review:",
                "  <file|folder> ...          Files or folders to review",
                "  --git                      Only files changed in Git (uncommitted + new files)",
                "  --base <branch>            Only files changed since a branch, e.g. --base main",
                "  --changed-lines-only       Only report issues on changed lines (implies --git)",
                "",
                "Output:",
                "  -f, --format <list>        console,html,json,md or all   (default console,html)",
                "  -o, --out <dir>            Output folder                 (default review-output)",
                "  -q, --quiet                Hide fix suggestions in the console",
                "  --no-color                 Plain console output",
                "  --no-history               Do not save the score to history.csv",
                "",
                "Rules:",
                "  -c, --config <file>        Settings file (default .codereview.properties)",
                "  --min-severity <level>     Hide issues below CRITICAL|MAJOR|MINOR|INFO",
                "  --disable <IDS>            Turn off rules, e.g. --disable MAGIC_NUMBER,LONG_LINE",
                "  --list-rules               Show every rule",
                "  --explain <RULE_ID>        Explain a rule with a before/after example",
                "",
                "AI and fixes:",
                "  --ai                       Also ask Claude for a deeper review (needs ANTHROPIC_API_KEY)",
                "  --fix                      Write auto-fixed copies to <out>/fixed/",
                "  --fix-in-place             Fix the original files (keeps .bak backups)",
                "",
                "CI / quality gate:",
                "  --fail-on <level>          Exit code 2 if any issue is at this level or above",
                "",
                "Other:",
                "  -h, --help | -v, --version",
                "",
                "Ignore an issue: add  // review-ignore  (or  // review-ignore: RULE_ID) on the line or the line above."));
    }

    /** Small helper used by the GUI to produce a one line description of an issue. */
    public static String describe(Issue i) {
        return "[" + i.severity() + "] line " + i.line() + ": " + i.message();
    }
}
