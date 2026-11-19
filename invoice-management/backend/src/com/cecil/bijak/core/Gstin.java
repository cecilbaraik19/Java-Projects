package com.cecil.bijak.core;

import java.util.Map;
import java.util.TreeMap;

/**
 * Checks a GSTIN (Goods and Services Tax Identification Number), the 15-character tax number of an Indian business:
 *
 *     20   AABCK1234M   1    Z    5
 *     |    |            |    |    └ check character (calculated from the first 14)
 *     |    |            |    └ always Z (for now)
 *     |    |            └ registration number of this PAN in that state (1-9, then A-Z)
 *     |    └ PAN of the business (5 letters, 4 digits, 1 letter)
 *     └ state code (20 = Jharkhand)
 *
 * The check character uses the "Luhn mod 36" idea: each of the first 14 characters is a number 0-35; every
 * second one is doubled; for each product add (product / 36) + (product % 36); the check is (36 - sum % 36) % 36.
 * One typing mistake (or two swapped neighbours) almost always gives a different check character, so it is caught.
 */
public final class Gstin {
    private static final String CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /** State and union-territory codes used by GST. */
    public static final Map<String, String> STATES = new TreeMap<>();
    static {
        String[] s = {
            "01", "Jammu and Kashmir", "02", "Himachal Pradesh", "03", "Punjab", "04", "Chandigarh", "05", "Uttarakhand",
            "06", "Haryana", "07", "Delhi", "08", "Rajasthan", "09", "Uttar Pradesh", "10", "Bihar", "11", "Sikkim",
            "12", "Arunachal Pradesh", "13", "Nagaland", "14", "Manipur", "15", "Mizoram", "16", "Tripura", "17", "Meghalaya",
            "18", "Assam", "19", "West Bengal", "20", "Jharkhand", "21", "Odisha", "22", "Chhattisgarh", "23", "Madhya Pradesh",
            "24", "Gujarat", "26", "Dadra and Nagar Haveli and Daman and Diu", "27", "Maharashtra", "29", "Karnataka",
            "30", "Goa", "31", "Lakshadweep", "32", "Kerala", "33", "Tamil Nadu", "34", "Puducherry",
            "35", "Andaman and Nicobar Islands", "36", "Telangana", "37", "Andhra Pradesh", "38", "Ladakh"
        };
        for (int i = 0; i < s.length; i += 2) STATES.put(s[i], s[i + 1]);
    }

    private Gstin() { }

    /** The check character for the first 14 characters. */
    public static char checkChar(String first14) {
        int sum = 0;
        for (int i = 0; i < 14; i++) {
            int v = CHARS.indexOf(first14.charAt(i));
            if (v < 0) throw new IllegalArgumentException("Bad character");
            int product = v * (i % 2 == 0 ? 1 : 2);
            sum += product / 36 + product % 36;
        }
        return CHARS.charAt((36 - sum % 36) % 36);
    }

    /** Upper case without spaces: " 20aabck 1234m1z5 " -> "20AABCK1234M1Z5". */
    public static String normalize(String g) {
        return g == null ? "" : g.replaceAll("[\\s-]", "").toUpperCase();
    }

    /** Returns null when the GSTIN is valid, otherwise a message a person can understand. */
    public static String problem(String raw) {
        String g = normalize(raw);
        if (g.length() != 15) return "A GSTIN has 15 characters (this one has " + g.length() + ").";
        if (!g.matches("[0-9A-Z]{15}")) return "A GSTIN has only letters and digits.";
        String state = g.substring(0, 2);
        if (!STATES.containsKey(state)) return "\"" + state + "\" is not a GST state code (Jharkhand is 20).";
        if (!g.substring(2, 12).matches("[A-Z]{5}[0-9]{4}[A-Z]")) return "Characters 3 to 12 must be a PAN, like AABCK1234M.";
        if (g.charAt(12) == '0') return "Character 13 (the registration number) cannot be 0.";
        if (g.charAt(13) != 'Z') return "Character 14 is always Z.";
        char want = checkChar(g.substring(0, 14));
        if (g.charAt(14) != want) return "The last character (check digit) does not match - there is probably a typing mistake.";
        return null;
    }

    public static boolean valid(String g) { return problem(g) == null; }

    public static String stateOf(String g) { return normalize(g).substring(0, 2); }

    public static String pan(String g) { return normalize(g).substring(2, 12); }

    /** Makes a valid GSTIN from a state, PAN and registration number (used by the demo data and the tests). */
    public static String make(String state, String pan, char reg) {
        String first = state + pan + reg + "Z";
        return first + checkChar(first);
    }
}
