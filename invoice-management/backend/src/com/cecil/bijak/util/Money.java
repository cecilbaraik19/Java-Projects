package com.cecil.bijak.util;

/**
 * Money is always kept as a whole number of PAISE in a long (₹1 = 100 paise).
 * Never in a double: 0.1 + 0.2 is not 0.3 in a double, but 10 + 20 paise is always 30 paise.
 *
 *   format(128050075)  -> "12,80,500.75"     (the Indian way: lakh and crore groups)
 *   parse("12,80,500.75") -> 128050075
 *   words(128050000)   -> "Rupees Twelve Lakh Eighty Thousand Five Hundred Only"
 */
public final class Money {
    private Money() { }

    /** Rounds a/b to the nearest whole number, halves away from zero (the way people round money). */
    public static long div(long a, long b) {
        if (b == 0) throw new ArithmeticException("divide by zero");
        if (b < 0) { a = -a; b = -b; }
        long q = a / b, r = a % b;
        if (Math.abs(r) * 2 >= b) q += a >= 0 ? 1 : -1;
        return q;
    }

    /** Percent of an amount in paise, rounded: pct(10000, 18.0) = 1800. The percent may have 2 decimals (2.5 %). */
    public static long pct(long paise, double percent) {
        long bp = Math.round(percent * 100);         // basis points: 18 % = 1800, 2.5 % = 250
        return div(paise * bp, 10_000);
    }

    /** 12,80,500.75 (no ₹ sign). Always two decimals. */
    public static String format(long paise) {
        boolean neg = paise < 0;
        long p = Math.abs(paise);
        String rupees = group(p / 100);
        String s = rupees + "." + String.format("%02d", p % 100);
        return neg ? "-" + s : s;
    }

    /** Basis points as a percent for text: 1800 -> "18%", 250 -> "2.5%". */
    public static String percent(int bp) {
        return (bp % 100 == 0 ? String.valueOf(bp / 100) : String.valueOf(bp / 100.0)) + "%";
    }

    /** ₹12,80,501 (rounded to the rupee, for dashboards). */
    public static String rupees(long paise) {
        long r = div(paise, 100);
        return (r < 0 ? "-₹" : "₹") + group(Math.abs(r));
    }

    /** Indian digit grouping: the last 3 digits, then groups of 2.  12345678 -> 1,23,45,678 */
    public static String group(long n) {
        String s = Long.toString(n);
        if (s.length() <= 3) return s;
        String last3 = s.substring(s.length() - 3);
        String rest = s.substring(0, s.length() - 3);
        StringBuilder sb = new StringBuilder();
        int first = rest.length() % 2;
        if (first == 1) sb.append(rest.charAt(0));
        for (int i = first; i < rest.length(); i += 2) {
            if (sb.length() > 0) sb.append(',');
            sb.append(rest, i, i + 2);
        }
        return sb + "," + last3;
    }

    /**
     * Reads an amount typed by a person: "12,500", "12500.5", "₹ 1,20,000.00". Returns paise.
     * Throws IllegalArgumentException with a friendly message when it is not an amount.
     */
    public static long parse(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Amount is missing.");
        String t = text.replace("₹", "").replace("Rs.", "").replace("Rs", "").replace(",", "").replace(" ", "").trim();
        if (!t.matches("-?\\d{1,13}(\\.\\d{1,2})?")) throw new IllegalArgumentException("\"" + text.trim() + "\" is not an amount (example: 12,500.50).");
        boolean neg = t.startsWith("-");
        if (neg) t = t.substring(1);
        String[] parts = t.split("\\.");
        long rupees = Long.parseLong(parts[0]);
        long paise = parts.length > 1 ? Long.parseLong((parts[1] + "0").substring(0, 2)) : 0;
        long v = rupees * 100 + paise;
        return neg ? -v : v;
    }

    // ------------------------------------------------------------------ amount in words (Indian system)

    private static final String[] ONES = { "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
            "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen" };
    private static final String[] TENS = { "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety" };

    /** "Rupees Twelve Lakh Eighty Thousand Five Hundred and Paise Seventy Five Only". Printed on every invoice. */
    public static String words(long paise) {
        if (paise < 0) return "Minus " + words(-paise);
        long rupees = paise / 100, p = paise % 100;
        StringBuilder sb = new StringBuilder("Rupees ");
        sb.append(rupees == 0 ? "Zero" : number(rupees));
        if (p > 0) sb.append(" and Paise ").append(below100((int) p));
        return sb.append(" Only").toString();
    }

    /** A whole number in Indian words: crore (1,00,00,000), lakh (1,00,000), thousand, hundred. */
    public static String number(long n) {
        if (n == 0) return "Zero";
        StringBuilder sb = new StringBuilder();
        long crore = n / 1_00_00_000L;
        n %= 1_00_00_000L;
        if (crore > 0) sb.append(number(crore)).append(" Crore");   // above 99 crore: "One Hundred Crore" etc.
        int lakh = (int) (n / 1_00_000), thousand = (int) (n / 1000 % 100), hundred = (int) (n / 100 % 10), rest = (int) (n % 100);
        if (lakh > 0) space(sb).append(below100(lakh)).append(" Lakh");
        if (thousand > 0) space(sb).append(below100(thousand)).append(" Thousand");
        if (hundred > 0) space(sb).append(ONES[hundred]).append(" Hundred");
        if (rest > 0) space(sb).append(sb.length() > 0 ? "and " : "").append(below100(rest));
        return sb.toString();
    }

    private static StringBuilder space(StringBuilder sb) { if (sb.length() > 0) sb.append(' '); return sb; }

    private static String below100(int n) {
        if (n < 20) return ONES[n];
        return TENS[n / 10] + (n % 10 > 0 ? " " + ONES[n % 10] : "");
    }
}
