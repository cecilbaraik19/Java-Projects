package com.cecil.bijak.core;

import java.time.LocalDate;

/**
 * The Indian financial year runs from 1 April to 31 March. 10 Nov 2026 is in "26-27"; 10 Feb 2027 is too.
 * Invoice numbers start again from 1 every financial year and must have no gaps:
 *     KVP/26-27/0042   (series prefix / year / running number)
 * A GST invoice number may have at most 16 characters - checked when the prefix is set.
 */
public final class FinYear {
    private FinYear() { }

    /** "26-27" for any date from 1 Apr 2026 to 31 Mar 2027. */
    public static String of(LocalDate d) {
        int start = d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
        return String.format("%02d-%02d", start % 100, (start + 1) % 100);
    }

    public static LocalDate start(LocalDate d) {
        int y = d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
        return LocalDate.of(y, 4, 1);
    }

    public static String number(String prefix, LocalDate d, long n) {
        return prefix + "/" + of(d) + "/" + String.format("%04d", n);
    }

    /** Problem with a series prefix (1-6 letters/digits) or null. */
    public static String prefixProblem(String p) {
        if (p == null || !p.matches("[A-Z0-9]{1,5}")) return "A prefix has 1 to 5 capital letters or digits, like KVP.";
        if (number(p, LocalDate.of(2026, 4, 1), 9999).length() > 16) return "The invoice number would be longer than 16 characters.";
        return null;
    }
}
