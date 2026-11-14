package com.cecil.kaksh.util;

/** Small text helpers. */
public final class Text {
    private Text() { }

    /** Trims, removes control characters, squeezes spaces and cuts to max length. Null stays null. */
    public static String clean(String s, int max) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder();
        boolean space = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                if (!space && sb.length() > 0) sb.append(' ');
                space = true;
            } else {
                sb.append(c);
                space = false;
            }
        }
        String out = sb.toString().trim();
        return out.length() > max ? out.substring(0, max).trim() : out;
    }

    public static boolean blank(String s) { return s == null || s.isBlank(); }
}
