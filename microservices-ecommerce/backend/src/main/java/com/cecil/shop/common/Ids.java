package com.cecil.shop.common;

import java.security.SecureRandom;

/** Small id helpers. */
public final class Ids {
    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Ids() {}

    /** A new 12-character trace id, e.g. "3f2a9c01b7de". */
    public static String trace() {
        char[] c = new char[12];
        for (int i = 0; i < c.length; i++) c[i] = HEX[RNG.nextInt(16)];
        return new String(c);
    }

    /** Only letters, digits and dashes, at most 40 characters (used to clean trace ids coming from outside). */
    public static String clean(String s) {
        if (s == null || s.isEmpty() || s.length() > 40) return null;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (!(Character.isLetterOrDigit(ch) || ch == '-')) return null;
        }
        return s;
    }
}
