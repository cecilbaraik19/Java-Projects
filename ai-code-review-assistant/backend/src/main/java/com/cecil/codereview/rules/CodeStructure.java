package com.cecil.codereview.rules;

import com.cecil.codereview.analyzer.SourceFile;
import com.cecil.codereview.metrics.MetricsCalculator;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small helpers that understand a little bit of brace-language structure. */
final class CodeStructure {
    private CodeStructure() { }

    private static final Pattern LOOP_HEAD = Pattern.compile("\\b(for|while)\\s*\\(");
    private static final Pattern DO_HEAD = Pattern.compile("\\bdo\\s*\\{");

    /** Returns an array where index = line number and value = true if that line is inside a loop body. */
    static boolean[] loopLines(SourceFile file) {
        boolean[] inLoop = new boolean[file.lineCount() + 2];
        String code = file.codeText();
        Matcher m = LOOP_HEAD.matcher(code);
        while (m.find()) {
            int closeParen = matchParen(code, m.end() - 1);
            if (closeParen < 0) continue;
            int i = skipSpaces(code, closeParen + 1);
            if (i >= code.length()) continue;
            int bodyStart = i;
            int bodyEnd;
            if (code.charAt(i) == '{') {
                bodyEnd = MetricsCalculator.findMatchingBrace(code, i);
            } else if (code.charAt(i) == ';') {
                continue; // do { } while (...);  or empty loop
            } else {
                bodyEnd = code.indexOf(';', i);
            }
            if (bodyEnd < 0) continue;
            mark(file, inLoop, bodyStart, bodyEnd);
        }
        Matcher d = DO_HEAD.matcher(code);
        while (d.find()) {
            int open = d.end() - 1;
            int close = MetricsCalculator.findMatchingBrace(code, open);
            if (close > 0) mark(file, inLoop, open, close);
        }
        return inLoop;
    }

    private static void mark(SourceFile file, boolean[] arr, int from, int to) {
        int a = file.lineOf(from);
        int b = file.lineOf(to);
        for (int l = a; l <= b && l < arr.length; l++) arr[l] = true;
    }

    static int skipSpaces(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    /** Given the index of '(', returns the index of the matching ')' or -1. */
    static int matchParen(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /** true if the offset sits inside the (...) header of a try-with-resources. */
    static boolean insideTryResources(String code, int offset) {
        int from = Math.max(0, offset - 600);
        String before = code.substring(from, offset);
        int idx = before.lastIndexOf("try");
        while (idx >= 0) {
            int p = skipSpaces(before, idx + 3);
            if (p < before.length() && before.charAt(p) == '(') {
                int depth = 0;
                for (int i = p; i < before.length(); i++) {
                    char c = before.charAt(i);
                    if (c == '(') depth++;
                    else if (c == ')') depth--;
                }
                return depth > 0;
            }
            idx = before.lastIndexOf("try", idx - 1);
        }
        return false;
    }
}
