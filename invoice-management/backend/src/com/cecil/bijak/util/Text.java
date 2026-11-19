package com.cecil.bijak.util;

import java.text.Normalizer;
import java.util.Locale;

/** Small helpers for text typed by people. */
public final class Text {
    private Text() { }

    /** Trims, removes control characters, squeezes spaces and cuts to max characters. null stays null. */
    public static String clean(String s, int max) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\t') sb.append(' ');
            else if (c >= 0x20 && c != 0x7f) sb.append(c);
        }
        String t = sb.toString().trim().replaceAll(" {2,}", " ");
        return t.length() > max ? t.substring(0, max).trim() : t;
    }

    /** Like clean, but keeps new lines (for longer notes). */
    public static String block(String s, int max) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || (c >= 0x20 && c != 0x7f)) sb.append(c);
            else if (c == '\t') sb.append(' ');
        }
        String t = sb.toString().trim().replaceAll("\n{3,}", "\n\n");
        return t.length() > max ? t.substring(0, max).trim() : t;
    }

    public static boolean blank(String s) { return s == null || s.isBlank(); }

    /** Lower case without accents and extra spaces: "  Node.JS " -> "node.js". Used to compare names. */
    public static String key(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    /** Edit distance (Levenshtein) - used for "did you mean ...?" suggestions. */
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
}
