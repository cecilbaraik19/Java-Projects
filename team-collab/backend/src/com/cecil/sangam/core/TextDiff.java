package com.cecil.sangam.core;

import com.cecil.sangam.util.Json;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Line-by-line comparing and merging of documents.
 *
 *  diff   – which lines were added / removed between two versions. Uses the Longest Common Subsequence (LCS),
 *           computed with dynamic programming (common first and last lines are skipped first, so it is fast).
 *  merge3 – "3-way merge": two people started from the same version (the BASE) and both saved. We compare
 *           base→mine and base→theirs. Where only one of them changed a part, we take that change. Where both
 *           changed the same part in different ways, it is a CONFLICT and a person has to choose.
 *           This is the same idea Git uses.
 */
public final class TextDiff {
    public static final int MAX_LINES = 2000;

    private TextDiff() { }

    public static List<String> lines(String text) {
        if (text == null || text.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(text.replace("\r\n", "\n").split("\n", -1)));
    }

    public static String join(List<String> lines) { return String.join("\n", lines); }

    /** For each line of a: the matching line number in b (as part of one longest common subsequence), or -1. */
    public static int[] match(List<String> a, List<String> b) {
        int n = a.size(), m = b.size();
        int[] out = new int[n];
        Arrays.fill(out, -1);
        int pre = 0;
        while (pre < n && pre < m && a.get(pre).equals(b.get(pre))) { out[pre] = pre; pre++; }
        int suf = 0;
        while (suf < n - pre && suf < m - pre && a.get(n - 1 - suf).equals(b.get(m - 1 - suf))) {
            out[n - 1 - suf] = m - 1 - suf;
            suf++;
        }
        int rn = n - pre - suf, rm = m - pre - suf;
        if (rn == 0 || rm == 0) return out;
        // L[i][j] = LCS length of a[pre+i ..] and b[pre+j ..]  (filled from the end)
        int w = rm + 1;
        int[] L = new int[(rn + 1) * w];
        for (int i = rn - 1; i >= 0; i--)
            for (int j = rm - 1; j >= 0; j--)
                L[i * w + j] = a.get(pre + i).equals(b.get(pre + j)) ? L[(i + 1) * w + j + 1] + 1
                        : Math.max(L[(i + 1) * w + j], L[i * w + j + 1]);
        int i = 0, j = 0;
        while (i < rn && j < rm) {
            if (a.get(pre + i).equals(b.get(pre + j))) { out[pre + i] = pre + j; i++; j++; }
            else if (L[(i + 1) * w + j] >= L[i * w + j + 1]) i++;
            else j++;
        }
        return out;
    }

    /** The changes from a to b: a list of {op: "same" | "del" | "add", text}. */
    public static List<Map<String, Object>> diff(List<String> a, List<String> b) {
        int[] m = match(a, b);
        List<Map<String, Object>> out = new ArrayList<>();
        int j = 0;
        for (int i = 0; i < a.size(); i++) {
            if (m[i] < 0) { out.add(Json.obj("op", "del", "text", a.get(i))); continue; }
            while (j < m[i]) out.add(Json.obj("op", "add", "text", b.get(j++)));
            out.add(Json.obj("op", "same", "text", a.get(i)));
            j++;
        }
        while (j < b.size()) out.add(Json.obj("op", "add", "text", b.get(j++)));
        return out;
    }

    /** Counts of added and removed lines, e.g. {3, 1}. */
    public static int[] stats(List<String> a, List<String> b) {
        int add = 0, del = 0;
        for (Map<String, Object> d : diff(a, b)) {
            if ("add".equals(d.get("op"))) add++;
            else if ("del".equals(d.get("op"))) del++;
        }
        return new int[] { add, del };
    }

    /** The result of a 3-way merge. When there are conflicts, "lines" holds the text with conflict markers. */
    public record Merge(List<String> lines, int conflicts, List<Map<String, Object>> parts) {
        public boolean clean() { return conflicts == 0; }
    }

    public static final String MINE = "<<<<<<< your version";
    public static final String SPLIT = "=======";
    public static final String THEIRS_PREFIX = ">>>>>>> ";

    public static Merge merge3(List<String> base, List<String> mine, List<String> theirs, String theirsName) {
        int[] ma = match(base, mine), mb = match(base, theirs);
        List<String> out = new ArrayList<>();
        List<Map<String, Object>> conflicts = new ArrayList<>();
        int i = 0, a = 0, b = 0, n = base.size();
        while (true) {
            int j = i;
            while (j < n && !(ma[j] >= a && mb[j] >= b)) j++;      // next line that both kept unchanged
            int ea = j < n ? ma[j] : mine.size(), eb = j < n ? mb[j] : theirs.size();
            List<String> o = base.subList(i, j), x = mine.subList(a, ea), y = theirs.subList(b, eb);
            List<String> lineByLine = o.size() == x.size() && o.size() == y.size() ? perLine(o, x, y) : null;
            if (x.equals(o)) out.addAll(y);                         // only they changed this part
            else if (y.equals(o) || x.equals(y)) out.addAll(x);     // only I changed it, or we did the same thing
            else if (lineByLine != null) out.addAll(lineByLine);    // we changed different lines next to each other
            else {                                                  // both changed the same lines differently
                conflicts.add(Json.obj("line", out.size() + 1, "base", new ArrayList<>(o), "mine", new ArrayList<>(x), "theirs", new ArrayList<>(y)));
                out.add(MINE);
                out.addAll(x);
                out.add(SPLIT);
                out.addAll(y);
                out.add(THEIRS_PREFIX + theirsName);
            }
            if (j >= n) break;
            out.add(base.get(j));
            i = j + 1;
            a = ea + 1;
            b = eb + 1;
        }
        return new Merge(out, conflicts.size(), conflicts);
    }

    /**
     * When a changed part has the same number of lines in all three versions, look at each line on its own:
     * I edited line 2 and you edited line 3 is not a real clash. Returns null if some line was changed by both.
     */
    private static List<String> perLine(List<String> o, List<String> x, List<String> y) {
        List<String> out = new ArrayList<>();
        for (int k = 0; k < o.size(); k++) {
            String ok = o.get(k), xk = x.get(k), yk = y.get(k);
            if (xk.equals(ok)) out.add(yk);
            else if (yk.equals(ok) || xk.equals(yk)) out.add(xk);
            else return null;
        }
        return out;
    }

    /** Does the text still contain conflict markers (somebody saved without choosing)? */
    public static boolean hasMarkers(List<String> lines) {
        for (String l : lines) if (l.equals(MINE) || l.startsWith(THEIRS_PREFIX)) return true;
        return false;
    }
}
