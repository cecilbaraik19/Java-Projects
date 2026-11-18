package com.cecil.swikriti.core;

import com.cecil.swikriti.util.Clock;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/**
 * Office hours: Monday to Friday, 09:30 to 18:00 India time (8.5 hours a day), without holidays.
 * Deadlines ("SLA 16 hours") count ONLY these hours: a task given at 17:00 on Friday with 2 hours is due
 * at 10:30 on Monday, not at 19:00 on Friday.
 */
public final class BusinessCalendar {
    public static final LocalTime OPEN = LocalTime.of(9, 30);
    public static final LocalTime CLOSE = LocalTime.of(18, 0);
    public static final long DAY_MINUTES = 510;    // 8.5 hours

    private final Set<LocalDate> holidays = new TreeSet<>();

    public BusinessCalendar(Collection<String> holidayDates) {
        if (holidayDates != null) for (String d : holidayDates) holidays.add(LocalDate.parse(d));
    }

    public boolean workingDay(LocalDate d) {
        DayOfWeek w = d.getDayOfWeek();
        return w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY && !holidays.contains(d);
    }

    /** The moment `hours` office hours after `start`. */
    public Instant addHours(Instant start, double hours) {
        long minutes = Math.round(hours * 60);
        ZonedDateTime t = start.atZone(Clock.ZONE);
        t = toOffice(t);
        int guard = 0;
        while (guard++ < 5000) {
            ZonedDateTime close = t.with(CLOSE);
            long left = java.time.Duration.between(t, close).toMinutes();
            if (minutes <= left) return t.plusMinutes(minutes).toInstant();
            minutes -= left;
            t = toOffice(close.plusMinutes(1));
        }
        throw new IllegalStateException("No working days found");
    }

    /** Moves a time forward to the next moment the office is open (or keeps it if it is open now). */
    ZonedDateTime toOffice(ZonedDateTime t) {
        for (int i = 0; i < 400; i++) {
            LocalDate d = t.toLocalDate();
            if (workingDay(d)) {
                if (t.toLocalTime().isBefore(OPEN)) return t.with(OPEN).withSecond(0).withNano(0);
                if (t.toLocalTime().isBefore(CLOSE)) return t;
            }
            t = d.plusDays(1).atTime(OPEN).atZone(Clock.ZONE);
        }
        throw new IllegalStateException("No working days found");
    }

    /** Office hours between two moments (0 if b is before a). Used for "how long did the stage take". */
    public double hoursBetween(Instant a, Instant b) {
        if (!b.isAfter(a)) return 0;
        ZonedDateTime x = a.atZone(Clock.ZONE), y = b.atZone(Clock.ZONE);
        long minutes = 0;
        for (LocalDate d = x.toLocalDate(); !d.isAfter(y.toLocalDate()); d = d.plusDays(1)) {
            if (!workingDay(d)) continue;
            ZonedDateTime open = d.atTime(OPEN).atZone(Clock.ZONE), close = d.atTime(CLOSE).atZone(Clock.ZONE);
            ZonedDateTime s = x.isAfter(open) ? x : open, e = y.isBefore(close) ? y : close;
            if (e.isAfter(s)) minutes += java.time.Duration.between(s, e).toMinutes();
        }
        return minutes / 60.0;
    }
}
