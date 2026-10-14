package com.cecil.codereview.rules;

import com.cecil.codereview.analyzer.ReviewContext;
import com.cecil.codereview.analyzer.Rule;
import com.cecil.codereview.analyzer.SourceFile;
import com.cecil.codereview.config.ReviewConfig;
import com.cecil.codereview.metrics.MethodInfo;
import com.cecil.codereview.model.Category;
import com.cecil.codereview.model.Severity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Performance, maintainability, style and documentation rules. */
public final class QualityRules {
    private QualityRules() { }

    public static List<Rule> all() {
        List<Rule> rules = new ArrayList<>();
        // performance
        rules.add(new StringConcatInLoop());
        rules.add(new ExpensiveObjectInLoop());
        rules.add(new LinePatternRule("SIZE_EQUALS_ZERO", "size() == 0 instead of isEmpty()", Category.PERFORMANCE,
                Severity.INFO, "isEmpty() is clearer and for some collections faster than size() == 0.",
                "\\.(size|length)\\s*\\(\\s*\\)\\s*(==|!=|>)\\s*0\\b", false,
                "Emptiness is checked with size()/length() and 0.",
                "Use isEmpty() / !isEmpty().", true, "java"));
        // maintainability (driven by metrics)
        rules.add(new MethodMetricRule("LONG_METHOD", "Long method", Severity.MINOR,
                "Long methods are hard to read, test and reuse."));
        rules.add(new MethodMetricRule("HIGH_COMPLEXITY", "High cyclomatic complexity", Severity.MAJOR,
                "Many branches mean many paths to test and a higher chance of hidden bugs."));
        rules.add(new MethodMetricRule("TOO_MANY_PARAMETERS", "Too many parameters", Severity.MINOR,
                "Long parameter lists are easy to call in the wrong order."));
        rules.add(new MethodMetricRule("DEEP_NESTING", "Deeply nested code", Severity.MINOR,
                "Code nested many levels deep is hard to follow."));
        rules.add(new LargeFile());
        rules.add(new DuplicateCode());
        rules.add(new UnusedImport());
        rules.add(new UnusedPrivateField());
        rules.add(new LinePatternRule("WILDCARD_IMPORT", "Wildcard import", Category.MAINTAINABILITY, Severity.INFO,
                "import x.* hides where classes come from and can cause name clashes.",
                "^\\s*import\\s+(static\\s+)?[\\w.]+\\.\\*\\s*;", false, "Wildcard import is used.",
                "Import only the classes you need.", false, "java"));
        rules.add(new MagicNumber());
        rules.add(new TodoComment());
        rules.add(new CommentedOutCode());
        // style
        rules.add(new LongLine());
        rules.add(new TrailingWhitespace());
        rules.add(new NamingConvention());
        rules.add(new MissingBraces());
        rules.add(new SystemOutUsage());
        rules.add(new LinePatternRule("CONSOLE_LOG", "console.log left in code", Category.BEST_PRACTICE,
                Severity.INFO, "Debug logging left in production code leaks data and clutters the console.",
                "\\bconsole\\.(log|debug)\\s*\\(", false, "console.log() left in code.",
                "Remove it or use a proper logger with log levels.", false, "javascript", "typescript"));
        rules.add(new LinePatternRule("VAR_DECLARATION", "var instead of let/const", Category.BEST_PRACTICE,
                Severity.INFO, "var is function-scoped and can cause confusing bugs.",
                "(^|[;{\\s])var\\s+\\w+", false, "'var' is used.", "Use const (or let if it is reassigned).",
                false, "javascript", "typescript"));
        // documentation
        rules.add(new MissingDocumentation());
        return rules;
    }

    // ------------------------------------------------------------ performance

    static final class StringConcatInLoop extends BaseRule {
        private static final Pattern DECL = Pattern.compile("\\bString\\s+([a-zA-Z_]\\w*)\\s*[=;]");

        StringConcatInLoop() {
            super("STRING_CONCAT_IN_LOOP", "String concatenation in loop", Category.PERFORMANCE, Severity.MINOR,
                    "Each += on a String copies the whole text, so loops become very slow (O(n^2)).");
        }

        @Override
        public void check(SourceFile file, ReviewContext ctx) {
            Set<String> vars = new HashSet<>();
            Matcher d = DECL.matcher(file.codeText());
            while (d.find()) vars.add(d.group(1));
            if (vars.isEmpty()) return;
            boolean[] loop = CodeStructure.loopLines(file);
            for (int i = 1; i <= file.lineCount(); i++) {
                if (!loop[i]) continue;
                String code = file.codeLine(i);
                for (String v : vars) {
                    if (code.matches(".*\\b" + Pattern.quote(v) + "\\s*\\+=.*")
                            || code.matches(".*\\b" + Pattern.quote(v) + "\\s*=\\s*" + Pattern.quote(v) + "\\s*\\+.*")) {
                        ctx.report(this, i, "String '" + v + "' is built with += inside a loop.",
                                "Use a StringBuilder: sb.append(...); and call sb.toString() once after the loop.");
                        break;
                    }
                }
            }
        }
    }

    static final class ExpensiveObjectInLoop extends BaseRule {
        private static final Pattern P = Pattern.compile(
                "(Pattern\\.compile\\s*\\(|new\\s+(SimpleDateFormat|Random|SecureRandom|ObjectMapper|Gson|DecimalFormat)\\s*\\()");

        ExpensiveObjectInLoop() {
            super("EXPENSIVE_OBJECT_IN_LOOP", "Expensive object created in loop", Category.PERFORMANCE,
                    Severity.MINOR, "Objects like Pattern, SimpleDateFormat or ObjectMapper are costly to create.");
        }

        @Override
        public void check(SourceFile file, ReviewContext ctx) {
            boolean[] loop = CodeStructure.loopLines(file);
            for (int i = 1; i <= file.lineCount(); i++) {
                if (loop[i] && P.matcher(file.codeLine(i)).find()) {
                    ctx.report(this, i, "Expensive object is created on every loop iteration.",
                            "Create it once before the loop (or as a static final constant) and reuse it.");
                }
            }
        }
    }

    // -------------------------------------------------------- maintainability

    static final class MethodMetricRule extends BaseRule {
        MethodMetricRule(String id, String name, Severity sev, String description) {
            super(id, name, Category.MAINTAINABILITY, sev, description);
        }

        @Override
        public boolean appliesTo(SourceFile file) { return !file.metrics().methods().isEmpty(); }

        @Override
        public void check(SourceFile file, ReviewContext ctx) {
            ReviewConfig c = ctx.config();
            for (MethodInfo m : file.metrics().methods()) {
                switch (id()) {
                    case "LONG_METHOD":
                        if (m.length() > c.maxMethodLines()) {
                            ctx.report(this, m.startLine(), "Method '" + m.name() + "' is " + m.length()
                                    + " lines long (limit " + c.maxMethodLines() + ").",
                                    "Split it into smaller methods that each do one job (Extract Method refactoring).");
                        }
                        break;
                    case "HIGH_COMPLEXITY":
                        if (m.complexity() > c.maxComplexity()) {
                            ctx.report(this, m.startLine(), "Method '" + m.name() + "' has cyclomatic complexity "
                                    + m.complexity() + " (limit " + c.maxComplexity() + ").",
                                    "Use early returns (guard clauses), extract helper methods, or replace long if/else chains with a map or polymorphism.");
                        }
                        break;
                    case "TOO_MANY_PARAMETERS":
                        if (m.parameterCount() > c.maxParameters()) {
                            ctx.report(this, m.startLine(), "Method '" + m.name() + "' takes " + m.parameterCount()
                                    + " parameters (limit " + c.maxParameters() + ").",
                                    "Group related parameters into a small class/record (Parameter Object) or use a Builder.");
                        }
                        break;
                    case "DEEP_NESTING":
                        if (m.maxNesting() > c.maxNesting()) {
                            ctx.report(this, m.startLine(), "Method '" + m.name() + "' is nested " + m.maxNesting()
                                    + " levels deep (limit " + c.maxNesting() + ").",
                                    "Invert conditions and return early, or move inner blocks into their own methods.");
                        }
                        break;
                    default:
                }
            }
        }
    }

    static final class LargeFile extends BaseRule {
        LargeFile() {
            super("LARGE_FILE", "File / class too large", Category.MAINTAINABILITY, Severity.MINOR,
                    "Very large classes usually do too many jobs (God class).");
        }

        @Override
        public boolean appliesTo(SourceFile file) { return true; }

        @Override
        public void check(SourceFile file, ReviewContext ctx) {
            int limit = ctx.config().maxClassLines();
            if (file.lineCount() > limit) {
                ctx.report(this, 1, "File has " + file.lineCount() + " lines (limit " + limit + ").",
                        "Split it into smaller classes, each with a single responsibility.");
            }
        }
    }

    static final class DuplicateCode extends BaseRule {
        DuplicateCode() {
            super("DUPLICATE_CODE", "Duplicated code block", Category.MAINTAINABILITY, Severity.MINOR,
                    "Copy-pasted code must be fixed in many places, and someone always forgets one.");
        }

        @Override
        public boolean appliesTo(SourceFile file) { return !"text".equals(file.language()); }

        @Override
        public void check(SourceFile file, ReviewContext ctx) {
            int window = ctx.config().duplicateBlockLines();
            List<String> norm = new ArrayList<>();
            List<Integer> lineNo = new ArrayList<>();
            for (int i = 1; i <= file.lineCount(); i++) {
                String s = file.codeLine(i).trim().replaceAll("\\s+", " ");
                if (s.isEmpty() || s.matches("[{}();]*") || s.startsWith("import ") || s.startsWith("@")) continue;
                norm.add(s);
                lineNo.add(i);
            }
            Map<String, Integer> firstSeen = new HashMap<>();
            int skipUntil = -1;
            for (int i = 0; i + window <= norm.size(); i++) {
                StringBuilder key = new StringBuilder();
                for (int k = 0; k < window; k++) key.append(norm.get(i + k)).append('\n');
                String block = key.toString();
                if (block.length() < window * 8) continue; // ignore tiny trivial lines
                Integer first = firstSeen.get(block);
                if (first == null) {
                    firstSeen.put(block, i);
                } else if (i > skipUntil && i >= first + window) {
                    ctx.report(this, lineNo.get(i), window + "+ lines here duplicate lines starting at line "
                            + lineNo.get(first) + ".",
                            "Move the shared code into one method and call it from both places.");
                    skipUntil = i + window;
                }
            }
        }
    }

    static final class UnusedImport extends BaseRule {
        private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([\\w.]+)\\.(\\w+)\\s*;");

        UnusedImport() {
            super("UNUSED_IMPORT", "Unused import", Category.MAINTAINABILITY, Severity.MINOR,
                    "Unused imports add noise and slow down reading.");
        }

        @Override
        public boolean autoFixable() { return true; }

        @Override
        public void check(SourceFile file, ReviewContext ctx) {
            String code = file.codeText();
            Matcher m = IMPORT.matcher(code);
            while (m.find()) {
                String simple = m.group(3);
                String rest = code.substring(0, m.start()) + code.substring(m.end());
                // also count Javadoc {@link X} references in the raw text
                boolean used = Pattern.compile("\\b" + Pattern.quote(simple) + "\\b").matcher(rest).find()
                        || file.rawText().contains("{@link " + simple);
                if (!used) {
                    ctx.report(this, file.lineOf(m.start()), "Import '" + m.group(2) + "." + simple + "' is never used.",
                            "Delete this import line.");
                }
            }
        }
    }

    static final class UnusedPrivateField extends BaseRule {
        private static final Pattern FIELD = Pattern.compile(
                "(?m)^\\s*private\\s+(static\\s+)?(final\\s+)?(?!class|interface|enum|record)[\\w<>\\[\\],.? ]+?\\s+([a-zA-Z_]\\w*)\\s*(=[^;]*)?;");

        UnusedPrivateField() {
            super("UNUSED_PRIVATE_FIELD", "Unused private field", Category.MAINTAINABILITY, Severity.MINOR,
                    "A private field that is never read is dead code.");
        }

        @Override
        public void check(SourceFile file, ReviewContext ctx) {
            String code = file.codeText();
            Matcher m = FIELD.matcher(code);
            while (m.find()) {
                String name = m.group(3);
                if ("serialVersionUID".equals(name)) continue;
                Matcher uses = Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(code);
                int count = 0;
                while (uses.find()) count++;
                if (count <= 1) {
                    ctx.report(this, file.lineOf(m.start(3)), "Private field '" + name + "' is never used.",
                            "Remove it, or use it if it was forgotten.");
                }
            }
        }
    }

    static final class MagicNumber extends BaseRule {
        private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])(-?\\d+(\\.\\d+)?[LlFfDd]?)(?![\\w.])");
        private static final Set<String> ALLOWED = new HashSet<>(java.util.Arrays.asList(
                "0", "1", "-1", "2", "0.0", "1.0", "0L", "1L", "100", "10", "0.5"));

        MagicNumber() {
            super("MAGIC_NUMBER", "Magic number", Category.MAINTAINABILITY, Severity.INFO,
                    "Unnamed numbers hide their meaning. A named constant explains itself.");
        }

        @Override
        public void check(SourceFile file, ReviewContext ctx) {
            for (int i = 1; i <= file.lineCount(); i++) {
                String code = file.codeLine(i).trim();
                if (code.isEmpty() || code.contains("final ") || code.startsWith("@") || code.startsWith("case ")
                        || code.startsWith("import ") || code.startsWith("package ") || code.contains("serialVersionUID")) {
                    continue;
                }
                Matcher m = NUMBER.matcher(code);
                while (m.find()) {
                    String n = m.group(1);
                    if (ALLOWED.contains(n)) continue;
                    // ignore array sizes like new int[5] and indexes like a[3]
                    int s = m.start();
                    if (s > 0 && code.charAt(s - 1) == '[') continue;
                    ctx.report(this, i, "Magic number " + n + " is used directly.",
                            "Give it a meaningful name: private static final int MEANINGFUL_NAME = " + n + ";");
                    break;
                }
            }
        }
    }

    static final class TodoComment extends BaseRule {
        private static final Pattern P = Pattern.compile("\\b(TODO|FIXME|HACK|XXX)\\b");

        TodoComment() {
            super("TODO_COMMENT", "TODO / FIXME left in code", Category.MAINTAINABILITY, Severity.INFO,
                    "TODO and FIXME notes are unfinished work that is easy to forget.");
        }

        @Override
        public boolean appliesTo(SourceFile file) { return true; }

        @Override
        public void check(SourceFile file, ReviewContext ctx) {
            for (int i = 1; i <= file.lineCount(); i++) {
                if (!file.lineHasComment(i) && !"text".equals(file.language())) continue;
                Matcher m = P.matcher(file.rawLine(i));
                if (m.find()) {
                    ctx.report(this, i, m.group(1) + " comment found.",
                            "Finish the work, or move it to your issue tracker and link the ticket number here.");
                }
            }
        }
    }

    static final class CommentedOutCode extends BaseRule {
        private static final Pattern CODE_LIKE = Pattern.compile(
                "^\\s*//\\s*(.*;\\s*$|.*[{}]\\s*$|(if|for|while|return|int|String|System|public|private)\\b.*[;({])");

        CommentedOutCode() {
            super("COMMENTED_OUT_CODE", "Commented-out code", Category.MAINTAINABILITY, Severity.INFO,
                    "Old code left in comments confuses readers. Git already keeps the history.");
        }

        @Override
        public boolean appliesTo(SourceFile file) {
            return file.isJava() || "javascript".equals(file.language()) || "typescript".equals(file.language());
        }

        @Override
        public void check(SourceFile file, ReviewContext ctx) {
            int lastReported = -10;
            for (int i = 1; i <= file.lineCount(); i++) {
                if (CODE_LIKE.matcher(file.rawLine(i)).find()) {
                    if (i - lastReported > 1) {
                        ctx.report(this, i, "Commented-out code found.", "Delete it - version control remembers old code.");
                    }
                    lastReported = i;
                }
            }
        }
    }

    // ------------------------------------------------------------------ style

    static final class LongLine extends BaseRule {
        LongLine() {
            super("LONG_LINE", "Line too long", Category.STYLE, Severity.INFO,
                    "Very long lines need horizontal scrolling and are hard to review.");
        }

        @Override
        public boolean appliesTo(SourceFile file) { return true; }

        @Override
        public void check(SourceFile file, ReviewContext ctx) {
            int max = ctx.config().maxLineLength();
            for (int i = 1; i <= file.lineCount(); i++) {
                int len = file.rawLine(i).replace("\t", "    ").length();
                if (len > max) {
                    ctx.report(this, i, "Line is " + len + " characters (limit " + max + ").",
                            "Break the line after an operator or comma, or extract a variable.");
                }
            }
        }
    }

    static final class TrailingWhitespace extends BaseRule {
        TrailingWhitespace() {
            super("TRAILING_WHITESPACE", "Trailing whitespace", Category.STYLE, Severity.INFO,
                    "Spaces at the end of lines create noisy diffs.");
        }

        @Override
        public boolean autoFixable() { return true; }

        @Override
        public boolean appliesTo(SourceFile file) { return true; }

        @Override
        public void check(SourceFile file, ReviewContext ctx) {
            int first = -1;
            int count = 0;
            for (int i = 1; i <= file.lineCount(); i++) {
                String r = file.rawLine(i);
                if (!r.isEmpty() && Character.isWhitespace(r.charAt(r.length() - 1)) && !r.trim().isEmpty()) {
                    if (first < 0) first = i;
                    count++;
                }
            }
            if (count > 0) {
                ctx.report(this, first, count + " line(s) end with spaces/tabs (first one here).",
                        "Remove trailing whitespace (the --fix option does this automatically).");
            }
        }
    }

    static final class NamingConvention extends BaseRule {
        private static final Pattern CLASS = Pattern.compile("(?<![.\\w])(class|interface|enum)\\s+([A-Za-z_]\\w*)");
        private static final Pattern CONSTANT = Pattern.compile(
                "\\bstatic\\s+final\\s+(?:int|long|double|float|boolean|char|byte|short|String)\\s+([A-Za-z_]\\w*)\\s*=");
        private static final Pattern LOCAL_SNAKE = Pattern.compile("\\b(int|long|double|float|boolean|char|String|var)\\s+([a-z]+_[a-zA-Z0-9_]*)\\s*[=;]");

        NamingConvention() {
            super("NAMING_CONVENTION", "Naming convention", Category.STYLE, Severity.MINOR,
                    "Java naming: classes PascalCase, methods/variables camelCase, constants UPPER_SNAKE_CASE.");
        }

        @Override
        public void check(SourceFile file, ReviewContext ctx) {
            String code = file.codeText();
            Set<String> classNames = new HashSet<>();
            Matcher c = CLASS.matcher(code);
            while (c.find()) {
                String name = c.group(2);
                classNames.add(name);
                if (!name.matches("[A-Z][A-Za-z0-9]*")) {
                    ctx.report(this, file.lineOf(c.start(2)), "Type name '" + name + "' is not PascalCase.",
                            "Rename to " + pascal(name) + ".");
                }
            }
            for (MethodInfo m : file.metrics().methods()) {
                if (classNames.contains(m.name())) continue; // constructor
                if (!m.name().matches("[a-z][A-Za-z0-9]*")) {
                    ctx.report(this, m.startLine(), "Method name '" + m.name() + "' is not camelCase.",
                            "Rename to " + camel(m.name()) + ".");
                }
            }
            Matcher k = CONSTANT.matcher(code);
            while (k.find()) {
                String name = k.group(1);
                if (name.matches("[A-Z][A-Z0-9_]*") || name.matches("(?i)log(ger)?|serialVersionUID")) continue;
                ctx.report(this, file.lineOf(k.start(1)), "Constant '" + name + "' is not UPPER_SNAKE_CASE.",
                        "Rename to " + upperSnake(name) + ".");
            }
            Matcher l = LOCAL_SNAKE.matcher(code);
            while (l.find()) {
                ctx.report(this, file.lineOf(l.start(2)), "Variable '" + l.group(2) + "' uses snake_case.",
                        "Rename to " + camel(l.group(2)) + ".");
            }
        }

        static String camel(String s) {
            String p = pascal(s);
            return p.isEmpty() ? p : Character.toLowerCase(p.charAt(0)) + p.substring(1);
        }

        static String pascal(String s) {
            StringBuilder sb = new StringBuilder();
            boolean up = true;
            for (char ch : s.toCharArray()) {
                if (ch == '_' || ch == '-') { up = true; continue; }
                sb.append(up ? Character.toUpperCase(ch) : ch);
                up = false;
            }
            return sb.toString();
        }

        static String upperSnake(String s) {
            return s.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase();
        }
    }

    static final class MissingBraces extends BaseRule {
        private static final Pattern HEAD = Pattern.compile("^\\s*(}\\s*)?(if|else\\s+if|for|while)\\s*\\(");
        private static final Pattern ELSE = Pattern.compile("^\\s*(}\\s*)?else\\s*(?!if\\b)(\\S.*)?$");

        MissingBraces() {
            super("MISSING_BRACES", "if/for/while without braces", Category.STYLE, Severity.MINOR,
                    "Without { } it is easy to add a second line that is NOT inside the if (the famous goto fail bug).");
        }

        @Override
        public boolean appliesTo(SourceFile file) {
            return file.isJava() || "javascript".equals(file.language()) || "typescript".equals(file.language());
        }

        @Override
        public void check(SourceFile file, ReviewContext ctx) {
            String code = file.codeText();
            for (int i = 1; i <= file.lineCount(); i++) {
                String line = file.codeLine(i);
                Matcher h = HEAD.matcher(line);
                if (h.find()) {
                    int lineStart = offsetOfLine(file, i);
                    int close = CodeStructure.matchParen(code, lineStart + h.end() - 1);
                    if (close < 0) continue;
                    int next = CodeStructure.skipSpaces(code, close + 1);
                    if (next >= code.length()) continue;
                    char ch = code.charAt(next);
                    if (ch != '{' && ch != ';') {
                        // body on the next line is the risky "goto fail" shape; same-line is mostly style
                        boolean nextLine = file.lineOf(next) > file.lineOf(close);
                        ctx.report(this, i, "'" + h.group(2) + "' body is not wrapped in braces"
                                        + (nextLine ? " (body is on the next line)." : "."),
                                "Always use braces: if (cond) { doIt(); }",
                                nextLine ? Severity.MINOR : Severity.INFO);
                    }
                    continue;
                }
                Matcher e = ELSE.matcher(line);
                if (e.find()) {
                    String after = e.group(2) == null ? "" : e.group(2).trim();
                    if (after.startsWith("{")) continue;
                    if (after.isEmpty()) {
                        String nextLine = nextCodeLine(file, i);
                        if (nextLine.startsWith("{")) continue;
                    }
                    ctx.report(this, i, "'else' body is not wrapped in braces.", "Always use braces: else { ... }",
                            after.isEmpty() ? Severity.MINOR : Severity.INFO);
                }
            }
        }

        private static int offsetOfLine(SourceFile file, int line) {
            int off = 0;
            for (int l = 1; l < line; l++) off += file.rawLine(l).length() + 1;
            return off;
        }

        private static String nextCodeLine(SourceFile file, int line) {
            for (int l = line + 1; l <= file.lineCount(); l++) {
                String s = file.codeLine(l).trim();
                if (!s.isEmpty()) return s;
            }
            return "";
        }
    }

    static final class SystemOutUsage extends BaseRule {
        private static final Pattern OUT = Pattern.compile("System\\.(out|err)\\.print");
        private static final Pattern MAIN = Pattern.compile("static\\s+void\\s+main\\s*\\(");

        SystemOutUsage() {
            super("SYSTEM_OUT", "System.out used for logging", Category.BEST_PRACTICE, Severity.INFO,
                    "System.out has no log levels, timestamps or file output. Libraries/services should use a logger.");
        }

        @Override
        public void check(SourceFile file, ReviewContext ctx) {
            if (MAIN.matcher(file.codeText()).find()) return; // console programs may print
            int count = 0;
            int first = -1;
            for (int i = 1; i <= file.lineCount(); i++) {
                if (OUT.matcher(file.codeLine(i)).find()) {
                    if (first < 0) first = i;
                    count++;
                }
            }
            if (count > 0) {
                ctx.report(this, first, "System.out/err used " + count + " time(s) in a non-main class.",
                        "Use java.util.logging.Logger or SLF4J: private static final Logger LOG = Logger.getLogger(X.class.getName());");
            }
        }
    }

    // ---------------------------------------------------------- documentation

    static final class MissingDocumentation extends BaseRule {
        private static final Pattern PUBLIC_TYPE = Pattern.compile("(?m)^\\s*public\\s+(abstract\\s+|final\\s+)*(class|interface|enum)\\s+(\\w+)");

        MissingDocumentation() {
            super("MISSING_DOC", "Missing documentation", Category.DOCUMENTATION, Severity.INFO,
                    "Public classes and methods should explain what they do (Javadoc / docstring).");
        }

        @Override
        public boolean appliesTo(SourceFile file) { return file.isJava() || "python".equals(file.language()); }

        @Override
        public void check(SourceFile file, ReviewContext ctx) {
            if ("python".equals(file.language())) {
                for (MethodInfo m : file.metrics().methods()) {
                    if (!m.isPublic()) continue;
                    String next = "";
                    for (int l = m.startLine() + 1; l <= file.lineCount(); l++) {
                        if (!file.rawLine(l).trim().isEmpty()) { next = file.rawLine(l).trim(); break; }
                    }
                    if (!next.startsWith("\"\"\"") && !next.startsWith("'''")) {
                        ctx.report(this, m.startLine(), "Function '" + m.name() + "' has no docstring.",
                                "Add a short \"\"\"docstring\"\"\" saying what it does, its arguments and return value.");
                    }
                }
                return;
            }
            Matcher t = PUBLIC_TYPE.matcher(file.codeText());
            while (t.find()) {
                int line = file.lineOf(t.start(2));
                if (!hasJavadocAbove(file, line)) {
                    ctx.report(this, line, "Public type '" + t.group(3) + "' has no Javadoc.",
                            "Add /** One sentence about what this class is for. */ above it.");
                }
            }
            for (MethodInfo m : file.metrics().methods()) {
                if (m.isPublic() && m.length() > 3 && !hasJavadocAbove(file, m.startLine())
                        && !"main".equals(m.name()) && !file.rawLine(m.startLine() - 1).contains("@Override")) {
                    ctx.report(this, m.startLine(), "Public method '" + m.name() + "' has no Javadoc.",
                            "Add /** what it does, @param ..., @return ... */ above the method.");
                }
            }
        }

        private static boolean hasJavadocAbove(SourceFile file, int line) {
            for (int l = line - 1; l >= 1; l--) {
                String s = file.rawLine(l).trim();
                if (s.isEmpty() || s.startsWith("@")) continue;
                return s.endsWith("*/");
            }
            return false;
        }
    }
}
