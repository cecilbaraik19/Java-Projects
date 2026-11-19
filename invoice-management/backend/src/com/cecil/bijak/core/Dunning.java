package com.cecil.bijak.core;

import com.cecil.bijak.util.Money;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The reminder ladder ("dunning"): polite before the due date, firmer after it.
 *
 *     level 1   3 days BEFORE the due date   "Friendly reminder"
 *     level 2   ON the due date              "Payment due today"
 *     level 3   7 days late                  "Payment overdue"
 *     level 4   15 days late                 "Second notice"
 *     level 5   30 days late                 "Final notice" (with interest)
 *
 * The days can be changed in Settings. Each level is sent once. If Bijak was switched off for a while and several
 * levels are due at once, ONLY the highest is sent - the customer must not get three e-mails in one minute.
 *
 * Interest on late payment (if switched on) is simple interest from the day after the due date:
 *     interest = balance x yearly rate x days late / 365
 */
public final class Dunning {
    public static final List<Integer> DEFAULT_DAYS = List.of(-3, 0, 7, 15, 30);
    public static final String[] TITLES = { "", "Friendly reminder", "Payment due today", "Payment overdue", "Second notice", "Final notice" };

    private Dunning() { }

    /** Days after the due date (negative = before). */
    public static long daysLate(LocalDate due, LocalDate today) { return ChronoUnit.DAYS.between(due, today); }

    /** The highest level whose day has come (0 = none yet). `days` holds the offset of each level, in order. */
    public static int levelDue(List<Integer> days, LocalDate due, LocalDate today) {
        long late = daysLate(due, today);
        int level = 0;
        for (int i = 0; i < days.size(); i++) if (late >= days.get(i)) level = i + 1;
        return level;
    }

    /** Level to send now, or 0: something new is due and it is higher than what was already sent. */
    public static int toSend(List<Integer> days, LocalDate due, LocalDate today, int alreadySent) {
        int due_ = levelDue(days, due, today);
        return due_ > alreadySent ? due_ : 0;
    }

    /** Problem with the reminder days (must go up, between -30 and 365), or null. */
    public static String daysProblem(List<Integer> days) {
        if (days.size() != 5) return "Give 5 reminder days.";
        for (int i = 0; i < days.size(); i++) {
            if (days.get(i) < -30 || days.get(i) > 365) return "Reminder days must be between -30 and 365.";
            if (i > 0 && days.get(i) <= days.get(i - 1)) return "Each reminder must come after the one before it.";
        }
        return null;
    }

    /** Simple interest in paise. yearlyBp: 1800 = 18 % a year. */
    public static long interest(long balance, int yearlyBp, long daysLate) {
        if (balance <= 0 || yearlyBp <= 0 || daysLate <= 0) return 0;
        return Money.div(balance * yearlyBp * daysLate, 10_000L * 365);
    }
}
