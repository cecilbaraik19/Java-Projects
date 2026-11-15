package com.cecil.sangam.core;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds @names in a message: "@lena can you check ORB-12?" → {"lena"}.
 * Also finds task keys like ORB-12, so the website can turn them into links.
 */
public final class Mentions {
    /** "@" at the start or after a space or bracket, then the username (letters, digits, dot, dash, underscore). */
    private static final Pattern AT = Pattern.compile("(?<![A-Za-z0-9_.])@([A-Za-z][A-Za-z0-9._-]{1,30})");
    public static final Pattern TASK_KEY = Pattern.compile("\\b([A-Z]{2,5})-(\\d{1,5})\\b");

    private Mentions() { }

    /** The usernames written with @, lower case, in order, no repeats. Includes the words "here" and "team". */
    public static Set<String> names(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        Matcher m = AT.matcher(text);
        while (m.find()) {
            String n = m.group(1).toLowerCase();
            while (n.endsWith(".") || n.endsWith("-") || n.endsWith("_")) n = n.substring(0, n.length() - 1); // "@lena." at the end of a sentence
            out.add(n);
        }
        return out;
    }

    /** Task keys mentioned, like "ORB-12". */
    public static Set<String> taskKeys(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        Matcher m = TASK_KEY.matcher(text);
        while (m.find()) out.add(m.group(1) + "-" + m.group(2));
        return out;
    }
}
