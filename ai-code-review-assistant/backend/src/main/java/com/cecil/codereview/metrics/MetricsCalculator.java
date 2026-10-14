package com.cecil.codereview.metrics;

import com.cecil.codereview.analyzer.SourceFile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Calculates line counts, methods and cyclomatic complexity for a file. */
public final class MetricsCalculator {
    private MetricsCalculator() { }

    private static final Pattern JAVA_METHOD = Pattern.compile(
            "(?m)(?:^|(?<=[;{}]))[ \\t]*((?:(?:public|protected|private|static|final|synchronized|abstract|native|default|strictfp)\\s+)*)"
                    + "(?:<[^>{};]*>\\s+)?"
                    + "([\\w.$]+(?:<[^{};()]*>)?(?:\\[\\])*)\\s+"
                    + "([A-Za-z_$][\\w$]*)\\s*\\(([^(){};]*)\\)\\s*"
                    + "(?:throws\\s+[\\w.$,\\s]+)?\\{");

    private static final Pattern JS_FUNCTION = Pattern.compile(
            "(?m)(?:function\\s+([A-Za-z_$][\\w$]*)\\s*\\(([^()]*)\\)\\s*\\{)"
                    + "|(?:^[ \\t]*(?:async\\s+)?([A-Za-z_$][\\w$]*)\\s*\\(([^()]*)\\)\\s*\\{)");

    private static final Pattern PY_DEF = Pattern.compile("(?m)^([ \\t]*)def\\s+(\\w+)\\s*\\(([^)]*)\\)");

    private static final Pattern JAVA_CLASS = Pattern.compile("(?<![.\\w])(class|interface|enum)\\s+[A-Za-z_]\\w*");
    private static final Pattern PY_CLASS = Pattern.compile("(?m)^[ \\t]*class\\s+\\w+");

    private static final Pattern C_DECISIONS = Pattern.compile("\\b(if|for|while|case|catch)\\b|&&|\\|\\|");
    private static final Pattern PY_DECISIONS = Pattern.compile("\\b(if|elif|for|while|except|and|or)\\b");

    private static final Set<String> NOT_METHOD_NAMES = new HashSet<>(Arrays.asList(
            "if", "for", "while", "switch", "catch", "synchronized", "return", "new", "else", "try", "do"));
    private static final Set<String> NOT_TYPES = new HashSet<>(Arrays.asList(
            "new", "return", "throw", "else", "case", "package", "import", "class", "interface", "enum"));

    public static FileMetrics calculate(SourceFile file) {
        int blank = 0;
        int comment = 0;
        int code = 0;
        for (int i = 1; i <= file.lineCount(); i++) {
            String raw = file.rawLine(i).trim();
            String c = file.codeLine(i).trim();
            if (raw.isEmpty()) blank++;
            else if (c.isEmpty() && file.lineHasComment(i)) comment++;
            else {
                code++;
                if (file.lineHasComment(i)) comment++;
            }
        }
        List<MethodInfo> methods;
        int classes;
        switch (file.language()) {
            case "java":
                methods = javaMethods(file);
                classes = count(JAVA_CLASS, file.codeText());
                break;
            case "javascript":
            case "typescript":
                methods = jsFunctions(file);
                classes = count(JAVA_CLASS, file.codeText());
                break;
            case "python":
                methods = pythonFunctions(file);
                classes = count(PY_CLASS, file.codeText());
                break;
            default:
                methods = new ArrayList<>();
                classes = 0;
        }
        return new FileMetrics(file.lineCount(), code, comment, blank, classes, methods);
    }

    private static int count(Pattern p, String text) {
        Matcher m = p.matcher(text);
        int c = 0;
        while (m.find()) c++;
        return c;
    }

    // ------------------------------------------------------------------ Java

    private static List<MethodInfo> javaMethods(SourceFile file) {
        List<MethodInfo> list = new ArrayList<>();
        String code = file.codeText();
        Matcher m = JAVA_METHOD.matcher(code);
        while (m.find()) {
            String modifiers = m.group(1) == null ? "" : m.group(1);
            String type = m.group(2);
            String name = m.group(3);
            if (NOT_METHOD_NAMES.contains(name) || NOT_TYPES.contains(type)) continue;
            int open = m.end() - 1;
            int close = findMatchingBrace(code, open);
            if (close < 0) continue;
            String body = code.substring(open + 1, close);
            list.add(new MethodInfo(name, file.lineOf(m.start(3)), file.lineOf(close),
                    countParams(m.group(4)), 1 + cDecisions(body), maxNesting(body),
                    modifiers.contains("public")));
        }
        return list;
    }

    // ------------------------------------------------------------ JavaScript

    private static List<MethodInfo> jsFunctions(SourceFile file) {
        List<MethodInfo> list = new ArrayList<>();
        String code = file.codeText();
        Matcher m = JS_FUNCTION.matcher(code);
        while (m.find()) {
            String name = m.group(1) != null ? m.group(1) : m.group(3);
            String params = m.group(1) != null ? m.group(2) : m.group(4);
            if (name == null || NOT_METHOD_NAMES.contains(name)) continue;
            int open = m.end() - 1;
            int close = findMatchingBrace(code, open);
            if (close < 0) continue;
            String body = code.substring(open + 1, close);
            list.add(new MethodInfo(name, file.lineOf(m.start()), file.lineOf(close),
                    countParams(params), 1 + cDecisions(body), maxNesting(body), true));
        }
        return list;
    }

    // ---------------------------------------------------------------- Python

    private static List<MethodInfo> pythonFunctions(SourceFile file) {
        List<MethodInfo> list = new ArrayList<>();
        String code = file.codeText();
        Matcher m = PY_DEF.matcher(code);
        while (m.find()) {
            int indent = m.group(1).replace("\t", "    ").length();
            int startLine = file.lineOf(m.start());
            int endLine = startLine;
            int maxIndent = indent;
            for (int ln = startLine + 1; ln <= file.lineCount(); ln++) {
                String line = file.codeLine(ln);
                if (line.trim().isEmpty()) continue;
                int ind = indentOf(line);
                if (ind <= indent) break;
                endLine = ln;
                maxIndent = Math.max(maxIndent, ind);
            }
            StringBuilder body = new StringBuilder();
            for (int ln = startLine + 1; ln <= endLine; ln++) body.append(file.codeLine(ln)).append('\n');
            int decisions = count(PY_DECISIONS, body.toString());
            int nesting = Math.max(0, (maxIndent - indent) / 4 - 1);
            String params = m.group(3).replaceAll("\\b(self|cls)\\b\\s*,?", "");
            list.add(new MethodInfo(m.group(2), startLine, endLine, countParams(params),
                    1 + decisions, nesting, !m.group(2).startsWith("_")));
        }
        return list;
    }

    private static int indentOf(String line) {
        int n = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ') n++;
            else if (c == '\t') n += 4;
            else break;
        }
        return n;
    }

    // ---------------------------------------------------------------- shared

    /** Counts decision points (if, loops, case, catch, &&, ||, ternary ?). */
    public static int cDecisions(String body) {
        int count = count(C_DECISIONS, body);
        for (int i = 0; i < body.length(); i++) {
            if (body.charAt(i) != '?') continue;
            char prev = previousNonSpace(body, i);
            char next = i + 1 < body.length() ? body.charAt(i + 1) : ' ';
            if (prev == '<' || prev == ',' || next == '>' || next == '?' || next == '.' || prev == '?') continue;
            count++;
        }
        return count;
    }

    private static char previousNonSpace(String s, int i) {
        for (int j = i - 1; j >= 0; j--) {
            if (!Character.isWhitespace(s.charAt(j))) return s.charAt(j);
        }
        return ' ';
    }

    /** Deepest brace level inside a body (0 = straight code). */
    public static int maxNesting(String body) {
        int depth = 0;
        int max = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '{') max = Math.max(max, ++depth);
            else if (c == '}') depth--;
        }
        return max;
    }

    /** Given the index of '{', returns the index of the matching '}' or -1. */
    public static int findMatchingBrace(String code, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static int countParams(String params) {
        if (params == null) return 0;
        String p = params.replaceAll("<[^<>]*>", "").trim();
        p = p.replaceAll("<[^<>]*>", "");
        if (p.isEmpty()) return 0;
        return p.split(",").length;
    }
}
