package com.cecil.ngo.util;

import java.time.LocalDate;

/**
 * Money helpers. All amounts are whole rupees stored in a long (no paise, no rounding errors).
 *  - format(123456)      -> "₹1,23,456"           (Indian grouping: last 3 digits, then pairs)
 *  - inWords(123456)     -> "One Lakh Twenty Three Thousand Four Hundred Fifty Six Rupees Only"
 *  - financialYear(date) -> "2026-27"              (Indian financial year runs April to March)
 */
public final class Money {
    private Money() {}

    public static final long MAX_AMOUNT = 10_00_00_000L; // 10 crore - a sanity limit for one entry

    /** Checks an amount typed in a form. */
    public static long amount(Long v, String field) {
        if (v == null) throw new IllegalArgumentException(field + " is required");
        if (v <= 0) throw new IllegalArgumentException(field + " must be more than zero");
        if (v > MAX_AMOUNT) throw new IllegalArgumentException(field + " is too large (max ₹10 crore)");
        return v;
    }

    public static String format(long rupees) {
        boolean neg = rupees < 0;
        String s = Long.toString(Math.abs(rupees));
        StringBuilder out = new StringBuilder();
        if (s.length() <= 3) out.append(s);
        else {
            String last3 = s.substring(s.length() - 3);
            String rest = s.substring(0, s.length() - 3);
            StringBuilder r = new StringBuilder();
            int i = rest.length();
            while (i > 2) { r.insert(0, "," + rest.substring(i - 2, i)); i -= 2; }
            r.insert(0, rest.substring(0, i));
            out.append(r).append(',').append(last3);
        }
        return (neg ? "-₹" : "₹") + out;
    }

    private static final String[] ONES = {"", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
            "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen"};
    private static final String[] TENS = {"", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"};

    private static String below100(int n) {
        if (n < 20) return ONES[n];
        return TENS[n / 10] + (n % 10 > 0 ? " " + ONES[n % 10] : "");
    }

    private static String below1000(int n) {
        String out = "";
        if (n >= 100) out = ONES[n / 100] + " Hundred";
        int r = n % 100;
        if (r > 0) out += (out.isEmpty() ? "" : " ") + below100(r);
        return out;
    }

    /** Amount in words, the Indian way (crore, lakh, thousand), as printed on a receipt. */
    public static String inWords(long rupees) {
        if (rupees == 0) return "Zero Rupees Only";
        if (rupees < 0) return "Minus " + inWords(-rupees);
        StringBuilder sb = new StringBuilder();
        long crore = rupees / 1_00_00_000L;
        long rest = rupees % 1_00_00_000L;
        if (crore > 0) sb.append(crore >= 1000 ? inWords(crore).replace(" Rupees Only", "") : below1000((int) crore)).append(" Crore ");
        int lakh = (int) (rest / 1_00_000);
        int thousand = (int) (rest % 1_00_000 / 1000);
        int hundreds = (int) (rest % 1000);
        if (lakh > 0) sb.append(below100(lakh)).append(" Lakh ");
        if (thousand > 0) sb.append(below100(thousand)).append(" Thousand ");
        if (hundreds > 0) sb.append(below1000(hundreds)).append(' ');
        return sb.toString().trim() + " Rupees Only";
    }

    /** "2026-27" for any date from 1 April 2026 to 31 March 2027. */
    public static String financialYear(LocalDate d) {
        int start = d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
        return start + "-" + String.format("%02d", (start + 1) % 100);
    }

    /** First day (1 April) of the financial year that contains this date. */
    public static LocalDate fyStart(LocalDate d) {
        int start = d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
        return LocalDate.of(start, 4, 1);
    }

    /** Percent as a whole number, safe when the total is zero. */
    public static int percent(long part, long total) {
        if (total <= 0) return 0;
        return (int) Math.round(part * 100.0 / total);
    }
}
