package com.cecil.bijak.core;

import com.cecil.bijak.model.Recurring;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * Dates for repeating invoices.
 *
 * The wanted day (anchorDay) is kept apart from the next date, so month-ends behave like people expect:
 *     anchor 31, monthly:  31 Jan -> 28 Feb -> 31 Mar -> 30 Apr -> 31 May
 * (If we only added one month each time, 31 Jan -> 28 Feb -> 28 Mar -> 28 Apr: the bill would slowly move.)
 */
public final class Schedule {
    private Schedule() { }

    /** The anchor day in the given month, cut to the month's last day when it is shorter. */
    public static LocalDate inMonth(int year, int month, int anchorDay) {
        LocalDate first = LocalDate.of(year, month, 1);
        return first.withDayOfMonth(Math.min(anchorDay, first.lengthOfMonth()));
    }

    /** The first run on or after `from`. */
    public static LocalDate first(Recurring.Every every, int anchor, LocalDate from) {
        if (every == Recurring.Every.WEEKLY) return from.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(anchor)));
        LocalDate d = inMonth(from.getYear(), from.getMonthValue(), anchor);
        if (d.isBefore(from)) d = step(every, anchor, d);
        return d;
    }

    /** The run after `current`. */
    public static LocalDate step(Recurring.Every every, int anchor, LocalDate current) {
        return switch (every) {
            case WEEKLY -> current.plusWeeks(1);
            case MONTHLY -> monthsLater(current, 1, anchor);
            case QUARTERLY -> monthsLater(current, 3, anchor);
            case YEARLY -> monthsLater(current, 12, anchor);
        };
    }

    private static LocalDate monthsLater(LocalDate d, int months, int anchor) {
        LocalDate m = d.withDayOfMonth(1).plusMonths(months);
        return inMonth(m.getYear(), m.getMonthValue(), anchor);
    }

    /** Problem with the anchor day for this repeat, or null. */
    public static String anchorProblem(Recurring.Every every, int anchor) {
        if (every == Recurring.Every.WEEKLY) return anchor >= 1 && anchor <= 7 ? null : "For weekly bills pick a day of the week.";
        return anchor >= 1 && anchor <= 31 ? null : "The day of the month must be 1 to 31.";
    }

    /** "On the 31st of every month (last day in shorter months)". */
    public static String describe(Recurring.Every every, int anchor) {
        if (every == Recurring.Every.WEEKLY) {
            String d = DayOfWeek.of(Math.max(1, Math.min(7, anchor))).name();
            return "Every " + d.charAt(0) + d.substring(1).toLowerCase();
        }
        String what = switch (every) {
            case MONTHLY -> "every month";
            case QUARTERLY -> "every 3 months";
            default -> "every year";
        };
        String s = "On the " + ordinal(anchor) + " " + what;
        if (anchor > 28) s += " (last day in shorter months)";
        return s;
    }

    public static String ordinal(int n) {
        int m100 = n % 100;
        String suf = (m100 >= 11 && m100 <= 13) ? "th" : switch (n % 10) { case 1 -> "st"; case 2 -> "nd"; case 3 -> "rd"; default -> "th"; };
        return n + suf;
    }
}
