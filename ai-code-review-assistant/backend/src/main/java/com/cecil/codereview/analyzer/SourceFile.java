package com.cecil.codereview.analyzer;

import com.cecil.codereview.metrics.FileMetrics;
import com.cecil.codereview.metrics.MetricsCalculator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A loaded source file.
 *
 * It keeps two versions of the text:
 *  - raw text: exactly what is in the file
 *  - code text: same length, but comments and the inside of string literals
 *    are replaced with spaces. Rules use this so that words inside comments or
 *    strings do not cause false alarms.
 */
public final class SourceFile {
    private final String displayName;
    private final Path path;
    private final String language;
    private final String rawText;
    private final String codeText;
    private final List<String> rawLines;
    private final List<String> codeLines;
    private final boolean[] hasComment;
    private final int[] lineStarts;
    private FileMetrics metrics;

    private SourceFile(String displayName, Path path, String text) {
        this.displayName = displayName;
        this.path = path;
        this.language = detectLanguage(displayName);
        this.rawText = text.replace("\r\n", "\n").replace('\r', '\n');
        this.rawLines = splitLines(rawText);
        this.hasComment = new boolean[rawLines.size() + 2];
        this.codeText = strip(rawText, language, hasComment);
        this.codeLines = splitLines(codeText);
        this.lineStarts = computeLineStarts(rawText);
    }

    public static SourceFile fromPath(Path path, String displayName) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return new SourceFile(displayName, path, new String(bytes, StandardCharsets.UTF_8));
    }

    public static SourceFile fromText(String displayName, String text) {
        return new SourceFile(displayName, null, text);
    }

    // ---------------------------------------------------------------- getters

    public String displayName() { return displayName; }
    public Path path() { return path; }
    public String language() { return language; }
    public boolean isJava() { return "java".equals(language); }
    public String rawText() { return rawText; }
    public String codeText() { return codeText; }
    public List<String> rawLines() { return rawLines; }
    public List<String> codeLines() { return codeLines; }
    public int lineCount() { return rawLines.size(); }

    /** 1-based line access to the original text. */
    public String rawLine(int line) {
        return line >= 1 && line <= rawLines.size() ? rawLines.get(line - 1) : "";
    }

    /** 1-based line access to the comment/string-free text. */
    public String codeLine(int line) {
        return line >= 1 && line <= codeLines.size() ? codeLines.get(line - 1) : "";
    }

    /** true when the given 1-based line contains (part of) a comment. */
    public boolean lineHasComment(int line) {
        return line >= 1 && line < hasComment.length && hasComment[line];
    }

    /** Converts a character offset in the text into a 1-based line number. */
    public int lineOf(int offset) {
        int lo = 0;
        int hi = lineStarts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (lineStarts[mid] <= offset) lo = mid; else hi = mid - 1;
        }
        return lo + 1;
    }

    public synchronized FileMetrics metrics() {
        if (metrics == null) metrics = MetricsCalculator.calculate(this);
        return metrics;
    }

    // ---------------------------------------------------------------- helpers

    static String detectLanguage(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String ext = dot >= 0 ? lower.substring(dot + 1) : "";
        switch (ext) {
            case "java": return "java";
            case "js": case "jsx": case "mjs": return "javascript";
            case "ts": case "tsx": return "typescript";
            case "py": return "python";
            default: return ext.isEmpty() ? "text" : ext;
        }
    }

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines.add(text.substring(start, i));
                start = i + 1;
            }
        }
        if (start < text.length() || lines.isEmpty()) lines.add(text.substring(start));
        return Collections.unmodifiableList(lines);
    }

    private static int[] computeLineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') starts.add(i + 1);
        }
        int[] arr = new int[starts.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = starts.get(i);
        return arr;
    }

    private enum State { CODE, LINE_COMMENT, BLOCK_COMMENT, STRING, CHAR, TEXT_BLOCK }

    /**
     * Blanks out comments and string contents while keeping every newline and
     * every character position, so line/column numbers still match.
     */
    static String strip(String text, String language, boolean[] commentLines) {
        boolean slashComments = !"python".equals(language);
        boolean hashComments = "python".equals(language);
        boolean known = "java".equals(language) || "javascript".equals(language)
                || "typescript".equals(language) || "python".equals(language);
        if (!known) return text;

        StringBuilder out = new StringBuilder(text.length());
        State state = State.CODE;
        char quote = '"';
        int line = 1;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            char next = i + 1 < n ? text.charAt(i + 1) : '\0';
            if (c == '\n') {
                out.append('\n');
                line++;
                if (state == State.LINE_COMMENT) state = State.CODE;
                if (state == State.STRING || state == State.CHAR) state = State.CODE; // broken literal
                continue;
            }
            switch (state) {
                case CODE:
                    if (slashComments && c == '/' && next == '/') {
                        state = State.LINE_COMMENT;
                        mark(commentLines, line);
                        out.append("  ");
                        i++;
                    } else if (slashComments && c == '/' && next == '*') {
                        state = State.BLOCK_COMMENT;
                        mark(commentLines, line);
                        out.append("  ");
                        i++;
                    } else if (hashComments && c == '#') {
                        state = State.LINE_COMMENT;
                        mark(commentLines, line);
                        out.append(' ');
                    } else if ((c == '"' || (hashComments && c == '\'')) && next == c
                            && i + 2 < n && text.charAt(i + 2) == c) {
                        state = State.TEXT_BLOCK;
                        quote = c;
                        out.append(c).append(c).append(c);
                        i += 2;
                    } else if (c == '"' || c == '`' || (!"java".equals(language) && c == '\'')) {
                        state = State.STRING;
                        quote = c;
                        out.append(c);
                    } else if (c == '\'') {
                        state = State.CHAR;
                        out.append(c);
                    } else {
                        out.append(c);
                    }
                    break;
                case LINE_COMMENT:
                    mark(commentLines, line);
                    out.append(' ');
                    break;
                case BLOCK_COMMENT:
                    mark(commentLines, line);
                    if (c == '*' && next == '/') {
                        state = State.CODE;
                        out.append("  ");
                        i++;
                    } else {
                        out.append(' ');
                    }
                    break;
                case STRING:
                    if (c == '\\' && next != '\n') {
                        out.append("  ");
                        i++;
                    } else if (c == quote) {
                        state = State.CODE;
                        out.append(c);
                    } else {
                        out.append(' ');
                    }
                    break;
                case CHAR:
                    if (c == '\\' && next != '\n') {
                        out.append("  ");
                        i++;
                    } else if (c == '\'') {
                        state = State.CODE;
                        out.append(c);
                    } else {
                        out.append(' ');
                    }
                    break;
                case TEXT_BLOCK:
                    if (c == '\\' && next != '\n') {
                        out.append("  ");
                        i++;
                    } else if (c == quote && next == quote && i + 2 < n && text.charAt(i + 2) == quote) {
                        state = State.CODE;
                        out.append(c).append(c).append(c);
                        i += 2;
                    } else {
                        out.append(' ');
                    }
                    break;
                default:
                    out.append(c);
            }
        }
        return out.toString();
    }

    private static void mark(boolean[] commentLines, int line) {
        if (line < commentLines.length) commentLines[line] = true;
    }
}
