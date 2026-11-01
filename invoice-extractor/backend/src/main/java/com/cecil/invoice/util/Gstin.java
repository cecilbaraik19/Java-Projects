package com.cecil.invoice.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GSTIN = the 15-character Indian GST number, e.g. 20AAICN4821K1Z5.
 *   2 digits  state code (20 = Jharkhand)
 *   10 chars  the PAN of the business
 *   1 char    entity number, then always 'Z', then 1 check character.
 * The last character is a checksum, so a mistyped or misread GSTIN can be caught.
 */
public final class Gstin {
    private Gstin() {}

    public static final Pattern PATTERN = Pattern.compile("(?<![A-Z0-9])(\\d{2}[A-Z]{5}\\d{4}[A-Z][1-9A-Z]Z[0-9A-Z])(?![A-Z0-9])");
    private static final String CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    public static final Map<String, String> STATES = Map.ofEntries(
            Map.entry("07", "Delhi"), Map.entry("09", "Uttar Pradesh"), Map.entry("10", "Bihar"),
            Map.entry("19", "West Bengal"), Map.entry("20", "Jharkhand"), Map.entry("21", "Odisha"),
            Map.entry("22", "Chhattisgarh"), Map.entry("24", "Gujarat"), Map.entry("27", "Maharashtra"),
            Map.entry("29", "Karnataka"), Map.entry("32", "Kerala"), Map.entry("33", "Tamil Nadu"),
            Map.entry("36", "Telangana"), Map.entry("06", "Haryana"), Map.entry("08", "Rajasthan"));

    /** The check character for the first 14 characters. */
    public static char checkChar(String first14) {
        int sum = 0;
        for (int i = 0; i < 14; i++) {
            int value = CHARS.indexOf(first14.charAt(i));
            int product = value * (i % 2 == 0 ? 1 : 2);
            sum += product / 36 + product % 36;
        }
        return CHARS.charAt((36 - sum % 36) % 36);
    }

    /** Right shape and right check character. */
    public static boolean valid(String g) {
        if (g == null || !PATTERN.matcher(g).matches()) return false;
        return checkChar(g.substring(0, 14)) == g.charAt(14);
    }

    /** Add the correct check character to a 14-character start (used for demo data). */
    public static String complete(String first14) { return first14 + checkChar(first14); }

    public static String state(String g) {
        if (g == null || g.length() < 2) return "";
        return STATES.getOrDefault(g.substring(0, 2), "State " + g.substring(0, 2));
    }

    public record Found(String gstin, int start) {}

    public static List<Found> findAll(String line) {
        List<Found> out = new ArrayList<>();
        if (line == null) return out;
        Matcher m = PATTERN.matcher(line.toUpperCase());
        while (m.find()) out.add(new Found(m.group(1), m.start()));
        return out;
    }
}
