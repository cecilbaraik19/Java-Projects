package com.cecil.codereview.rules;

import com.cecil.codereview.analyzer.ReviewContext;
import com.cecil.codereview.analyzer.Rule;
import com.cecil.codereview.analyzer.SourceFile;
import com.cecil.codereview.metrics.MetricsCalculator;
import com.cecil.codereview.model.Category;
import com.cecil.codereview.model.Severity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rules that find code which is very likely to behave wrongly. */
public final class BugRules {
    private BugRules() { }

    public static List<Rule> all() {
        List<Rule> rules = new ArrayList<>();
        rules.add(new StringEquality());
        rules.add(new EmptyCatch());
        rules.add(new LinePatternRule("CATCH_GENERIC_EXCEPTION", "Catching generic Exception", Category.BUG,
                Severity.MINOR,
                "Catching Exception/Throwable also hides programming errors like NullPointerException.",
                "catch\\s*\\(\\s*(final\\s+)?(Exception|Throwable|RuntimeException)\\s+\\w+\\s*\\)", false,
                "Generic Exception/Throwable is caught.",
                "Catch the specific exceptions you expect (IOException, SQLException, ...) and let others travel up.",
                false, "java"));
        rules.add(new LinePatternRule("PRINT_STACK_TRACE", "printStackTrace()", Category.BEST_PRACTICE,
                Severity.MINOR,
                "printStackTrace() writes to the console only; the error is lost in real applications.",
                "\\.printStackTrace\\s*\\(\\s*\\)", false, "printStackTrace() is used for error handling.",
                "Use a logger: LOGGER.log(Level.SEVERE, \"what failed\", e); or rethrow a meaningful exception.",
                false, "java"));
        rules.add(new EqualsWithoutHashCode());
        rules.add(new SwitchWithoutDefault());
        rules.add(new ResourceLeak());
        rules.add(new LinePatternRule("ASSIGNMENT_IN_CONDITION", "Assignment inside if", Category.BUG,
                Severity.MAJOR, "Using = instead of == in a condition is a classic typo bug.",
                "\\bif\\s*\\(\\s*!?[\\w.]+\\s*=(?!=)", false, "Assignment (=) used inside an if condition.",
                "Did you mean == ? If the assignment is on purpose, move it to its own line first.",
                false, "java", "javascript", "typescript"));
        rules.add(new LinePatternRule("NULL_COMPARE_EQUALS", "equals(null)", Category.BUG, Severity.MAJOR,
                "x.equals(null) is always false and throws NullPointerException when x is null.",
                "\\.equals\\s*\\(\\s*null\\s*\\)", false, "equals(null) is used to test for null.",
                "Use x == null (or Objects.isNull(x)).", false, "java"));
        rules.add(new LinePatternRule("FLOAT_MONEY", "double/float used for money", Category.BUG, Severity.MINOR,
                "double and float cannot store values like 0.1 exactly, so money calculations drift.",
                "(?i)\\b(double|float)\\s+\\w*(price|amount|balance|salary|cost|total|money|fee|tax)\\w*\\s*[=;,)]",
                false, "Money value is stored in a double/float.",
                "Use BigDecimal (or store paise/cents in a long).", false, "java"));
        rules.add(new LinePatternRule("THREAD_SLEEP_IN_LOOP", "Busy waiting with sleep", Category.PERFORMANCE,
                Severity.INFO, "Polling with Thread.sleep in a loop wastes threads and reacts slowly.",
                "Thread\\.sleep\\s*\\(", false, "Thread.sleep() used (possible busy-wait).",
                "Prefer wait/notify, a BlockingQueue, CompletableFuture or a ScheduledExecutorService.",
                false, "java") {
            private boolean[] loops;
            private SourceFile current;

            @Override
            protected boolean extraCheck(SourceFile file, int line) {
                if (current != file) {
                    current = file;
                    loops = CodeStructure.loopLines(file);
                }
                return loops[line];
            }
        });
        // Python specific bug patterns
        rules.add(new LinePatternRule("BARE_EXCEPT", "Bare except", Category.BUG, Severity.MAJOR,
                "A bare 'except:' also catches KeyboardInterrupt and SystemExit and hides every error.",
                "^\\s*except\\s*:", false, "Bare except: catches everything.",
                "Catch specific errors: except (ValueError, KeyError) as e:", false, "python"));
        rules.add(new LinePatternRule("MUTABLE_DEFAULT_ARG", "Mutable default argument", Category.BUG, Severity.MAJOR,
                "Default lists/dicts are created once and shared between calls, so data leaks between calls.",
                "def\\s+\\w+\\s*\\([^)]*=\\s*(\\[\\s*]|\\{\\s*}|list\\(\\)|dict\\(\\)|set\\(\\))", false,
                "A list/dict/set is used as a default argument.",
                "Use None as the default and create the object inside: if items is None: items = []",
                false, "python"));
        rules.add(new LinePatternRule("NONE_EQUALITY", "== None", Category.BEST_PRACTICE, Severity.MINOR,
                "Comparing with None should use 'is'; == can be overridden by classes.",
                "[!=]=\\s*None\\b", false, "Comparison to None uses ==/!=.", "Use 'is None' / 'is not None'.",
                false, "python"));
        // JavaScript specific
        rules.add(new LinePatternRule("LOOSE_EQUALITY", "Loose equality ==", Category.BUG, Severity.MINOR,
                "== converts types before comparing ('0' == 0 is true), which causes surprising bugs.",
                "[^=!<>]==[^=]|!=[^=]", false, "Loose equality (== / !=) is used.",
                "Use strict equality === and !==.", false, "javascript", "typescript"));
        return rules;
    }

    // ------------------------------------------------------------------------

    static final class StringEquality extends BaseRule {
        private static final Pattern LITERAL_COMPARE = Pattern.compile("\"[ ]*\"\\s*[!=]=(?!=)|[!=]=\\s*\"");
        private static final Pattern STRING_DECL = Pattern.compile("\\bString\\s+([a-zA-Z_]\\w*)\\s*[=;,)]");
        private static final Pattern VAR_COMPARE = Pattern.compile("([a-zA-Z_]\\w*)\\s*([!=]=)\\s*([a-zA-Z_]\\w*)");

        StringEquality() {
            super("STRING_EQUALITY", "String compared with ==", Category.BUG, Severity.MAJOR,
                    "== compares object references, not text. Two equal strings can still give false.");
        }

        @Override
        public boolean autoFixable() { return true; }

        @Override
        public void check(SourceFile file, ReviewContext ctx) {
            Set<String> stringVars = new HashSet<>();
            Matcher d = STRING_DECL.matcher(file.codeText());
            while (d.find()) stringVars.add(d.group(1));
            for (int i = 1; i <= file.lineCount(); i++) {
                String code = file.codeLine(i);
                boolean bad = LITERAL_COMPARE.matcher(code).find();
                if (!bad) {
                    Matcher v = VAR_COMPARE.matcher(code);
                    while (v.find()) {
                        String a = v.group(1);
                        String b = v.group(3);
                        if ("null".equals(a) || "null".equals(b)) continue;
                        if (stringVars.contains(a) && stringVars.contains(b)) {
                            bad = true;
                            break;
                        }
                    }
                }
                if (bad) {
                    ctx.report(this, i, "Strings are compared with == or != instead of equals().",
                            "Use \"text\".equals(value) or Objects.equals(a, b).");
                }
            }
        }
    }

    static final class EmptyCatch extends BaseRule {
        private static final Pattern P = Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*}");

        EmptyCatch() {
            super("EMPTY_CATCH", "Empty catch block", Category.BUG, Severity.MAJOR,
                    "An empty catch block silently swallows the error, so failures go unnoticed.");
        }

        @Override
        public void check(SourceFile file, ReviewContext ctx) {
            Matcher m = P.matcher(file.codeText());
            while (m.find()) {
                String raw = file.rawText().substring(m.start(), m.end());
                if (raw.contains("//") || raw.contains("/*")) continue; // intentionally ignored with explanation
                ctx.report(this, file.lineOf(m.start()), "Exception is caught and ignored.",
                        "Log it, rethrow it wrapped in a meaningful exception, or add a comment explaining why ignoring is safe.");
            }
        }
    }

    static final class EqualsWithoutHashCode extends BaseRule {
        private static final Pattern EQUALS = Pattern.compile("\\bboolean\\s+equals\\s*\\(\\s*(final\\s+)?Object\\s+\\w+\\s*\\)");
        private static final Pattern HASH = Pattern.compile("\\bint\\s+hashCode\\s*\\(\\s*\\)");

        EqualsWithoutHashCode() {
            super("EQUALS_WITHOUT_HASHCODE", "equals() without hashCode()", Category.BUG, Severity.MAJOR,
                    "Objects that are equal must have the same hashCode, or HashMap/HashSet will lose them.");
        }

        @Override
        public void check(SourceFile file, ReviewContext ctx) {
            Matcher m = EQUALS.matcher(file.codeText());
            if (m.find() && !HASH.matcher(file.codeText()).find()) {
                ctx.report(this, file.lineOf(m.start()), "equals() is overridden but hashCode() is not.",
                        "Also override hashCode(), e.g. return Objects.hash(field1, field2);");
            }
        }
    }

    static final class SwitchWithoutDefault extends BaseRule {
        private static final Pattern SWITCH = Pattern.compile("\\bswitch\\s*\\(");

        SwitchWithoutDefault() {
            super("SWITCH_NO_DEFAULT", "switch without default", Category.BUG, Severity.MINOR,
                    "A switch without default silently does nothing for unexpected values.");
        }

        @Override
        public boolean appliesTo(SourceFile file) {
            return file.isJava() || "javascript".equals(file.language()) || "typescript".equals(file.language());
        }

        @Override
        public void check(SourceFile file, ReviewContext ctx) {
            String code = file.codeText();
            Matcher m = SWITCH.matcher(code);
            while (m.find()) {
                int line = file.lineOf(m.start());
                String before = file.codeLine(line);
                int col = before.indexOf("switch");
                String prefix = col > 0 ? before.substring(0, col) : "";
                if (prefix.contains("=") || prefix.contains("return") || prefix.contains("->")) continue; // switch expression
                int close = CodeStructure.matchParen(code, m.end() - 1);
                if (close < 0) continue;
                int open = CodeStructure.skipSpaces(code, close + 1);
                if (open >= code.length() || code.charAt(open) != '{') continue;
                int end = MetricsCalculator.findMatchingBrace(code, open);
                if (end < 0) continue;
                String body = code.substring(open, end);
                if (!body.matches("(?s).*\\bdefault\\b.*")) {
                    ctx.report(this, line, "switch statement has no default branch.",
                            "Add a default: branch that handles or reports unexpected values.");
                }
            }
        }
    }

    static final class ResourceLeak extends BaseRule {
        private static final Pattern OPEN = Pattern.compile(
                "new\\s+(?:[\\w.]+\\.)?(FileInputStream|FileOutputStream|FileReader|FileWriter|BufferedReader|BufferedWriter"
                        + "|PrintWriter|Socket|ServerSocket|RandomAccessFile|ObjectInputStream|ObjectOutputStream"
                        + "|Scanner|ZipFile|JarFile)\\s*\\(");
        private static final Pattern CONNECTION = Pattern.compile("DriverManager\\.getConnection\\s*\\(|\\.createStatement\\s*\\(|\\.prepareStatement\\s*\\(");

        ResourceLeak() {
            super("RESOURCE_LEAK", "Resource may not be closed", Category.BUG, Severity.MAJOR,
                    "Files, sockets and DB connections that are not closed leak memory and file handles.");
        }

        @Override
        public void check(SourceFile file, ReviewContext ctx) {
            String code = file.codeText();
            checkPattern(file, ctx, code, OPEN);
            checkPattern(file, ctx, code, CONNECTION);
        }

        private void checkPattern(SourceFile file, ReviewContext ctx, String code, Pattern p) {
            Matcher m = p.matcher(code);
            while (m.find()) {
                int line = file.lineOf(m.start());
                String raw = file.rawLine(line);
                String trimmed = file.codeLine(line).trim();
                if (raw.contains("System.in") || raw.contains("System.out") || raw.contains("System.err")) continue;
                if (trimmed.startsWith("return ")) continue;
                if (CodeStructure.insideTryResources(code, m.start())) continue;
                if (isClosedLater(file, line)) continue;
                ctx.report(this, line, "Resource is opened without try-with-resources.",
                        "Wrap it: try (var in = new FileInputStream(f)) { ... } - it is closed automatically even on errors.");
            }
        }

        /** Accept the classic pattern: variable closed in a finally block. */
        private boolean isClosedLater(SourceFile file, int line) {
            Matcher v = Pattern.compile("(\\w+)\\s*=\\s*(new|\\w+\\.)").matcher(file.codeLine(line));
            if (!v.find()) return false;
            String var = v.group(1);
            Pattern close = Pattern.compile("\\b" + Pattern.quote(var) + "\\s*\\.\\s*close\\s*\\(");
            boolean sawFinally = false;
            for (int l = line + 1; l <= Math.min(file.lineCount(), line + 80); l++) {
                String c = file.codeLine(l);
                if (c.contains("finally")) sawFinally = true;
                if (sawFinally && close.matcher(c).find()) return true;
            }
            return false;
        }
    }
}
