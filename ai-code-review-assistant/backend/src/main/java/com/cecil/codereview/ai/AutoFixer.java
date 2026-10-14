package com.cecil.codereview.ai;

import com.cecil.codereview.analyzer.SourceFile;
import com.cecil.codereview.model.Issue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies only SAFE, mechanical fixes. Anything that needs a human decision
 * (SQL injection, empty catch, ...) is never changed automatically.
 *
 * Supported: STRING_EQUALITY, UNUSED_IMPORT, WEAK_HASH, SIZE_EQUALS_ZERO, TRAILING_WHITESPACE.
 */
public final class AutoFixer {
    private AutoFixer() { }

    public static final class Change {
        public final int line;
        public final String ruleId;
        public final String before;
        public final String after;

        Change(int line, String ruleId, String before, String after) {
            this.line = line;
            this.ruleId = ruleId;
            this.before = before;
            this.after = after;
        }
    }

    public static final class FixResult {
        public final String fixedText;
        public final List<Change> changes;

        FixResult(String fixedText, List<Change> changes) {
            this.fixedText = fixedText;
            this.changes = changes;
        }

        public boolean changed() { return !changes.isEmpty(); }
    }

    private static final String LIT = "(\"(?:[^\"\\\\]|\\\\.)*\")";
    private static final String OPERAND = "([A-Za-z_][\\w.]*(?:\\(\\))?)";
    private static final Pattern VAR_EQ_LIT = Pattern.compile(OPERAND + "\\s*(==|!=)\\s*" + LIT);
    private static final Pattern LIT_EQ_VAR = Pattern.compile(LIT + "\\s*(==|!=)\\s*" + OPERAND);
    private static final Pattern SIZE_ZERO = Pattern.compile(
            "([A-Za-z_][\\w.]*(?:\\(\\))?)\\.(size|length)\\(\\)\\s*(==|!=|>)\\s*0\\b");
    private static final Pattern WEAK_JAVA = Pattern.compile("(MessageDigest\\.getInstance\\(\\s*)\"(MD5|MD2|SHA-?1)\"");
    private static final Pattern WEAK_PY = Pattern.compile("hashlib\\.(md5|sha1)\\(");

    public static FixResult fix(SourceFile file, List<Issue> issues) {
        List<String> lines = new ArrayList<>(file.rawLines());
        List<Change> changes = new ArrayList<>();
        Set<Integer> deleteLines = new HashSet<>();
        boolean trimAll = false;

        for (Issue issue : issues) {
            int idx = issue.line() - 1;
            if (idx < 0 || idx >= lines.size()) continue;
            String before = lines.get(idx);
            String after = before;
            switch (issue.ruleId()) {
                case "STRING_EQUALITY":
                    after = fixStringEquality(before);
                    break;
                case "WEAK_HASH":
                    after = WEAK_JAVA.matcher(before).replaceAll("$1\"SHA-256\"");
                    after = WEAK_PY.matcher(after).replaceAll("hashlib.sha256(");
                    break;
                case "SIZE_EQUALS_ZERO":
                    after = fixSizeZero(before);
                    break;
                case "UNUSED_IMPORT":
                    deleteLines.add(idx);
                    changes.add(new Change(issue.line(), issue.ruleId(), before, "(line removed)"));
                    continue;
                case "TRAILING_WHITESPACE":
                    trimAll = true;
                    continue;
                default:
                    continue;
            }
            if (!after.equals(before)) {
                lines.set(idx, after);
                changes.add(new Change(issue.line(), issue.ruleId(), before, after));
            }
        }

        if (trimAll) {
            int count = 0;
            for (int i = 0; i < lines.size(); i++) {
                String t = rtrim(lines.get(i));
                if (!t.equals(lines.get(i))) {
                    lines.set(i, t);
                    count++;
                }
            }
            if (count > 0) changes.add(new Change(0, "TRAILING_WHITESPACE", count + " line(s)", "trailing spaces removed"));
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (deleteLines.contains(i)) continue;
            sb.append(lines.get(i));
            if (i < lines.size() - 1) sb.append('\n');
        }
        if (file.rawText().endsWith("\n") && sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
        changes.sort((a, b) -> Integer.compare(a.line, b.line));
        return new FixResult(sb.toString(), changes);
    }

    static String fixStringEquality(String line) {
        String out = line;
        Matcher m = VAR_EQ_LIT.matcher(out);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            if ("null".equals(m.group(1))) continue;
            String rep = ("!=".equals(m.group(2)) ? "!" : "") + m.group(3) + ".equals(" + m.group(1) + ")";
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        out = sb.toString();

        m = LIT_EQ_VAR.matcher(out);
        sb = new StringBuffer();
        while (m.find()) {
            if ("null".equals(m.group(3))) continue;
            String rep = ("!=".equals(m.group(2)) ? "!" : "") + m.group(1) + ".equals(" + m.group(3) + ")";
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        out = sb.toString();

        return out;
    }

    static String fixSizeZero(String line) {
        Matcher m = SIZE_ZERO.matcher(line);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String rep = ("==".equals(m.group(3)) ? "" : "!") + m.group(1) + ".isEmpty()";
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String rtrim(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }
}
