package com.cecil.swikriti.core;

import java.time.LocalDate;

/**
 * Document numbers like PR/26-27/0042: type / Indian financial year (1 April - 31 March) / running number.
 * The running number starts again at 0001 in every financial year, for every type.
 */
public final class DocNumber {
    private DocNumber() { }

    /** 18 Nov 2026 -> "26-27";  2 Feb 2027 -> "26-27";  1 Apr 2027 -> "27-28". */
    public static String fy(LocalDate d) {
        int start = d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
        return String.format("%02d-%02d", start % 100, (start + 1) % 100);
    }

    public static String format(String code, String fy, long n) { return code + "/" + fy + "/" + String.format("%04d", n); }
}
