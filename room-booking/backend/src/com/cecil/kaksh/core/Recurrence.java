package com.cecil.kaksh.core;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns "every weekday, 10 times" into a list of dates and times. The clock time stays the same on every date.
 *
 *   NONE      just the one meeting
 *   DAILY     every day the office is open (Monday to Saturday)
 *   WEEKDAYS  Monday to Friday
 *   WEEKLY    the same weekday every week
 */
public final class Recurrence {
    public static final Set<String> KINDS = Set.of("NONE", "DAILY", "WEEKDAYS", "WEEKLY");

    public record Slot(long start, long end) { }

    private Recurrence() { }

    public static List<Slot> expand(long start, long end, String repeat, int count) {
        List<Slot> out = new ArrayList<>();
        if (repeat == null || repeat.equals("NONE") || count <= 1) {
            out.add(new Slot(start, end));
            return out;
        }
        LocalDate day = Rules.dayOf(start);
        LocalTime from = Rules.zoned(start).toLocalTime();
        LocalTime to = Rules.zoned(end).toLocalTime();
        int guard = 0;
        while (out.size() < count && guard++ < 500) {
            if (fits(day, repeat)) out.add(new Slot(Rules.at(day, from), Rules.at(day, to)));
            day = repeat.equals("WEEKLY") ? day.plusWeeks(1) : day.plusDays(1);
        }
        return out;
    }

    private static boolean fits(LocalDate d, String repeat) {
        DayOfWeek w = d.getDayOfWeek();
        return switch (repeat) {
            case "WEEKDAYS" -> w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY;
            default -> Rules.openDay(d);
        };
    }
}
