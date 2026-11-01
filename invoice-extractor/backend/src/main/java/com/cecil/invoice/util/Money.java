package com.cecil.invoice.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading and writing money amounts as they appear on Indian invoices:
 * "Rs. 1,23,456.50", "INR 1500/-", "&#8377; 12,000", "(250.00)".
 */
public final class Money {
    private Money() {}

    /** One amount found in a line of text, with where it starts and ends. */
    public record Found(double value, int start, int end, String text) {}

    // digits with Indian (1,23,456) or western (123,456) commas, or plain digits, with up to 2 decimals
    private static final Pattern AMOUNT = Pattern.compile(
            "(?<![A-Za-z0-9/.:-])(?:(?:₹|Rs\\.?|INR|USD|\\$|EUR|€)\\s?)?\\(?((?:\\d{1,3}(?:,\\d{2,3})+|\\d+)(?:\\.\\d{1,2})?)\\)?(?:/-)?(?![0-9A-Za-z%]|\\.\\d|\\s?%|/\\d|-\\d)");

    /** Parse one amount. Returns null if the text is not an amount. */
    public static Double parse(String s) {
        if (s == null) return null;
        String t = s.strip()
                .replace("₹", "").replaceAll("(?i)^(rs\\.?|inr|usd|eur)", "").replace("$", "").replace("€", "")
                .replace("/-", "").replace(" ", "");
        boolean negative = false;
        if (t.startsWith("(") && t.endsWith(")")) { negative = true; t = t.substring(1, t.length() - 1); }
        else if (t.startsWith("-")) { negative = true; t = t.substring(1); }
        if (!t.matches("(?:\\d{1,3}(?:,\\d{2,3})+|\\d+)(?:\\.\\d{1,2})?")) return null;
        double v = Double.parseDouble(t.replace(",", ""));
        return negative ? -v : v;
    }

    /** Every amount in a line, left to right. Numbers followed by % are skipped. */
    public static List<Found> findAll(String line) {
        List<Found> out = new ArrayList<>();
        if (line == null) return out;
        Matcher m = AMOUNT.matcher(line);
        while (m.find()) {
            try {
                out.add(new Found(Double.parseDouble(m.group(1).replace(",", "")), m.start(), m.end(), m.group()));
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    /** Round to paise (2 decimals). */
    public static double r2(double v) { return Math.round(v * 100.0) / 100.0; }

    /** 1234567.5 -> "12,34,567.50" (Indian grouping). */
    public static String format(double v) {
        boolean neg = v < 0;
        long paise = Math.round(Math.abs(v) * 100);
        String whole = Long.toString(paise / 100);
        String dec = String.format("%02d", paise % 100);
        StringBuilder sb = new StringBuilder();
        int n = whole.length();
        if (n <= 3) sb.append(whole);
        else {
            String head = whole.substring(0, n - 3);
            String tail = whole.substring(n - 3);
            StringBuilder h = new StringBuilder();
            for (int i = 0; i < head.length(); i++) {
                if (i > 0 && (head.length() - i) % 2 == 0) h.append(',');
                h.append(head.charAt(i));
            }
            sb.append(h).append(',').append(tail);
        }
        return (neg ? "-" : "") + sb + "." + dec;
    }

    /** Value stored in a field: plain digits with 2 decimals, "15340.00". */
    public static String plain(double v) { return String.format(java.util.Locale.ROOT, "%.2f", r2(v)); }

    public static boolean near(double a, double b, double tolerance) { return Math.abs(a - b) <= tolerance + 1e-9; }
}
