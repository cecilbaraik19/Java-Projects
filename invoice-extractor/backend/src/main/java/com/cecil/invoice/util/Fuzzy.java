package com.cecil.invoice.util;

import java.util.Locale;

/**
 * "How alike are two pieces of text?" Used to match a vendor name even when
 * it is spelled a little differently ("Bytline Computers" vs "ByteLine Computers Pvt Ltd")
 * and to find labels that were misread ("lnvoice N0" vs "invoice no").
 */
public final class Fuzzy {
    private Fuzzy() {}

    /** Levenshtein distance: the fewest single-letter changes to turn a into b. */
    public static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    /** 1.0 = the same, 0.0 = nothing alike. */
    public static double similarity(String a, String b) {
        if (a == null || b == null) return 0;
        if (a.isEmpty() && b.isEmpty()) return 1;
        int max = Math.max(a.length(), b.length());
        return 1.0 - (double) distance(a, b) / max;
    }

    /** A company name without "Pvt Ltd", "M/s", dots and capitals, for comparing. */
    public static String companyKey(String name) {
        if (name == null) return "";
        String s = name.toLowerCase(Locale.ROOT).replaceAll("^m/s\\.?\\s*", "")
                .replaceAll("[^a-z0-9& ]", " ")
                .replaceAll("\\b(private|pvt|limited|ltd|llp|the|co|company|inc)\\b", " ")
                .replaceAll("\\s+", " ").trim();
        return s;
    }

    /** Similarity of two company names after cleaning them. */
    public static double sameCompany(String a, String b) {
        String x = companyKey(a), y = companyKey(b);
        if (x.isEmpty() || y.isEmpty()) return 0;
        if (x.equals(y)) return 1;
        if (x.startsWith(y + " ") || y.startsWith(x + " ")) return 0.9;
        return similarity(x, y);
    }
}
