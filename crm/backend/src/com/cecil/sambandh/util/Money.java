package com.cecil.sambandh.util;

/** Money is kept as whole paise (a long) so there are no rounding errors. 100 paise = 1 rupee. */
public final class Money {
    private Money() { }

    /** "12,50,000.50" or "1250000.5" or "₹ 1,200" -> paise. Returns -1 if it is not a valid amount. */
    public static long parseRupees(String text) {
        if (text == null) return -1;
        String t = text.replace("₹", "").replace("Rs.", "").replace("Rs", "").replace(",", "").trim();
        if (!t.matches("\\d{1,10}(\\.\\d{1,2})?")) return -1;
        String[] parts = t.split("\\.");
        long rupees = Long.parseLong(parts[0]);
        long paise = 0;
        if (parts.length == 2) paise = Long.parseLong((parts[1] + "0").substring(0, 2));
        return rupees * 100 + paise;
    }

    /** paise -> "₹12,50,000" (Indian grouping: lakh and crore). Paise shown only when not zero. */
    public static String format(long paise) {
        boolean neg = paise < 0;
        long p = Math.abs(paise);
        long rupees = p / 100;
        long rest = p % 100;
        String digits = Long.toString(rupees);
        StringBuilder sb = new StringBuilder();
        if (digits.length() <= 3) {
            sb.append(digits);
        } else {
            String last3 = digits.substring(digits.length() - 3);
            String head = digits.substring(0, digits.length() - 3);
            StringBuilder h = new StringBuilder();
            for (int i = 0; i < head.length(); i++) {
                if (i > 0 && (head.length() - i) % 2 == 0) h.append(',');
                h.append(head.charAt(i));
            }
            sb.append(h).append(',').append(last3);
        }
        if (rest != 0) sb.append('.').append(String.format("%02d", rest));
        return (neg ? "-₹" : "₹") + sb;
    }
}
