package com.cecil.invoice.util;

import java.util.Locale;

/**
 * Amounts in words, the Indian way (thousand, lakh, crore).
 *   toWords(12540.5)  -> "Rupees Twelve Thousand Five Hundred Forty and Fifty Paise Only"
 *   parse("Rupees Twelve Thousand Five Hundred Forty Only") -> 12540.0
 * Used to check that the "amount in words" on an invoice matches the total.
 */
public final class Words {
    private Words() {}

    private static final String[] ONES = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
    private static final String[] TENS = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};

    public static String toWords(double amount) {
        long paise = Math.round(amount * 100);
        long rupees = paise / 100;
        int p = (int) (paise % 100);
        String r = rupees == 0 ? "Zero" : cap(spell(rupees));
        String out = "Rupees " + r;
        if (p > 0) out += " and " + cap(below100(p)) + " Paise";
        return out + " Only";
    }

    private static String spell(long n) {
        StringBuilder sb = new StringBuilder();
        long crore = n / 10_000_000; n %= 10_000_000;
        long lakh = n / 100_000; n %= 100_000;
        long thousand = n / 1000; n %= 1000;
        long hundred = n / 100; n %= 100;
        if (crore > 0) sb.append(spell(crore)).append(" crore ");
        if (lakh > 0) sb.append(below100((int) lakh)).append(" lakh ");
        if (thousand > 0) sb.append(below100((int) thousand)).append(" thousand ");
        if (hundred > 0) sb.append(ONES[(int) hundred]).append(" hundred ");
        if (n > 0) sb.append(below100((int) n));
        return sb.toString().trim();
    }

    private static String below100(int n) {
        if (n < 20) return ONES[n];
        return TENS[n / 10] + (n % 10 == 0 ? "" : " " + ONES[n % 10]);
    }

    private static String cap(String s) {
        StringBuilder sb = new StringBuilder();
        for (String w : s.split(" ")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    /** Read an amount written in words. Returns null if no number words are found. */
    public static Double parse(String text) {
        if (text == null) return null;
        String t = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").trim();
        String rupeePart = t, paisePart = "";
        int pi = t.indexOf("paise");
        if (pi >= 0) {
            int and = t.lastIndexOf(" and ", pi);
            if (and >= 0) { rupeePart = t.substring(0, and); paisePart = t.substring(and + 5, pi); }
        }
        Long r = number(rupeePart);
        Long p = paisePart.isBlank() ? Long.valueOf(0) : number(paisePart);
        if (r == null) return null;
        return r + (p == null ? 0 : p) / 100.0;
    }

    private static Long number(String words) {
        long total = 0, current = 0;
        boolean any = false;
        for (String w : words.split(" ")) {
            int v = value(w);
            if (v >= 0) { current += v; any = true; continue; }
            switch (w) {
                case "hundred" -> { current = (current == 0 ? 1 : current) * 100; any = true; }
                case "thousand" -> { total += (current == 0 ? 1 : current) * 1000L; current = 0; any = true; }
                case "lakh", "lakhs", "lac", "lacs" -> { total += (current == 0 ? 1 : current) * 100_000L; current = 0; any = true; }
                case "crore", "crores" -> { total = (total + (current == 0 ? 1 : current)) * 10_000_000L; current = 0; any = true; }
                default -> { /* rupees, only, and ... are ignored */ }
            }
        }
        return any ? total + current : null;
    }

    private static int value(String w) {
        for (int i = 0; i < ONES.length; i++) if (ONES[i].equals(w)) return i;
        for (int i = 2; i < TENS.length; i++) if (TENS[i].equals(w)) return i * 10;
        if (w.equals("fourty")) return 40;
        return -1;
    }
}
