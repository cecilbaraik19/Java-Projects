package com.cecil.kaushal.core;

import com.cecil.kaushal.util.Text;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/**
 * The 5-step proficiency scale and SKILL DECAY ("half-life").
 *
 *   1 Aware · 2 Working · 3 Practitioner · 4 Advanced · 5 Expert
 *
 * A skill you have not used for a long time fades: after every "half-life" months without use it counts one level
 * lower (never below 1). Example: Java verified at 4, half-life 18 months, last used 20 months ago -> counts as 3.
 * Using it again ("I used it this month") brings the full level back. The verified level itself is never changed.
 */
public final class Levels {
    public static final String[] NAMES = { "None", "Aware", "Working", "Practitioner", "Advanced", "Expert" };
    public static final String[] MEANING = {
            "",
            "Knows what it is and when to use it; has read about it or followed a tutorial.",
            "Can do simple tasks with help and examples; has used it on real work a few times.",
            "Works on their own on normal tasks; knows the usual problems and how to fix them.",
            "Handles hard cases, reviews others' work, makes design choices, teaches it.",
            "Recognised go-to person; sets standards, solves what nobody else can."
    };

    private Levels() { }

    public static String name(int level) { return level >= 0 && level <= 5 ? NAMES[level] : "?"; }

    /** Whole months from the month "yyyy-mm" to today (0 if in the future or missing). */
    public static int monthsSince(String yearMonth, LocalDate today) {
        if (yearMonth == null || yearMonth.isBlank()) return 0;
        YearMonth ym = YearMonth.parse(yearMonth);
        long m = ChronoUnit.MONTHS.between(ym, YearMonth.from(today));
        return (int) Math.max(0, m);
    }

    /** The level after decay. base 0 stays 0. halfLife 0 or less = never fades. */
    public static int effective(int base, String lastUsed, int halfLife, LocalDate today) {
        if (base <= 0) return 0;
        if (halfLife <= 0) return base;
        int steps = monthsSince(lastUsed, today) / halfLife;
        return Math.max(1, base - steps);
    }

    /** True when the skill already counts lower than verified, or will lose a level soon (last quarter of the half-life). */
    public static boolean fading(int base, String lastUsed, int halfLife, LocalDate today) {
        if (base <= 1 || halfLife <= 0) return false;
        int eff = effective(base, lastUsed, halfLife, today);
        int m = monthsSince(lastUsed, today);
        return eff < base || (eff > 1 && (m % halfLife) >= Math.ceil(halfLife * 0.75));
    }

    /** "3", "expert", "Advanced" ... -> 1..5, or -1 if not a level. */
    public static int parse(String s) {
        if (s == null) return -1;
        String k = Text.key(s);
        if (k.matches("[1-5]")) return Integer.parseInt(k);
        for (int i = 1; i <= 5; i++) if (NAMES[i].toLowerCase().equals(k)) return i;
        return -1;
    }
}
