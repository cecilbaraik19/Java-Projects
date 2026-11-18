package com.cecil.swikriti.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Line-by-line difference between two revisions, using the LONGEST COMMON SUBSEQUENCE (LCS):
 * the lines both texts share in the same order stay; the rest are "removed" (old only) or "added" (new only).
 *
 * Dynamic programming: L[i][j] = length of the LCS of old[i..] and new[j..]
 *     L[i][j] = L[i+1][j+1] + 1                 if old[i] equals new[j]
 *             = max(L[i+1][j], L[i][j+1])       otherwise
 * Then one walk from (0,0) reads the answer. Time and memory O(n x m) - fine for documents of a few hundred lines.
 */
public final class Diff {
    public enum Kind { SAME, ADD, DEL }

    /** One line of the result. oldNo / newNo are 1-based line numbers (0 = not in that side). */
    public record Line(Kind kind, int oldNo, int newNo, String text) { }

    public static final int MAX_CELLS = 4_000_000;

    private Diff() { }

    public static List<Line> lines(String a, String b) {
        String[] x = split(a), y = split(b);
        int n = x.length, m = y.length;
        List<Line> out = new ArrayList<>();
        if ((long) n * m > MAX_CELLS) {   // too big for the table: show everything as removed + added
            for (int i = 0; i < n; i++) out.add(new Line(Kind.DEL, i + 1, 0, x[i]));
            for (int j = 0; j < m; j++) out.add(new Line(Kind.ADD, 0, j + 1, y[j]));
            return out;
        }
        int[][] L = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--)
            for (int j = m - 1; j >= 0; j--)
                L[i][j] = x[i].equals(y[j]) ? L[i + 1][j + 1] + 1 : Math.max(L[i + 1][j], L[i][j + 1]);
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (x[i].equals(y[j])) { out.add(new Line(Kind.SAME, i + 1, j + 1, x[i])); i++; j++; }
            else if (L[i + 1][j] >= L[i][j + 1]) { out.add(new Line(Kind.DEL, i + 1, 0, x[i])); i++; }
            else { out.add(new Line(Kind.ADD, 0, j + 1, y[j])); j++; }
        }
        while (i < n) { out.add(new Line(Kind.DEL, i + 1, 0, x[i])); i++; }
        while (j < m) { out.add(new Line(Kind.ADD, 0, j + 1, y[j])); j++; }
        return out;
    }

    /** Number of changed lines (added + removed). */
    public static int changes(List<Line> d) {
        int c = 0;
        for (Line l : d) if (l.kind != Kind.SAME) c++;
        return c;
    }

    static String[] split(String s) {
        if (s == null || s.isEmpty()) return new String[0];
        return s.replace("\r", "").split("\n", -1);
    }
}
