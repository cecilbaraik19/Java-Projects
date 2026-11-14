package com.cecil.kaksh.core;

import com.cecil.kaksh.model.User;
import com.cecil.kaksh.service.Problem;
import com.cecil.kaksh.util.Clock;
import com.cecil.kaksh.util.Json;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Map;

/**
 * The office rules for booking, in one place.
 *
 *   Open Monday to Saturday, 08:00 to 20:00 (India time). Closed on Sunday.
 *   Meetings start and end on a quarter hour (:00, :15, :30, :45).
 *   15 minutes to 4 hours long (the facility manager may book up to 10 hours, for example a training day).
 *   Up to 60 days ahead, never in the past.
 *   Fair use: one person may hold at most 15 hours of meetings (as organiser) in one week (Monday to Sunday).
 *   Check-in opens 10 minutes before the start. Nobody checked in 15 minutes after the start = a no-show:
 *   the room is given back.
 */
public final class Rules {
    public static final LocalTime OPEN = LocalTime.of(8, 0);
    public static final LocalTime CLOSE = LocalTime.of(20, 0);
    public static final int STEP_MIN = 15;
    public static final int MIN_MIN = 15;
    public static final int MAX_MIN = 240;
    public static final int MAX_MIN_ADMIN = 600;
    public static final int AHEAD_DAYS = 60;
    public static final int WEEK_QUOTA_MIN = 15 * 60;
    public static final int CHECKIN_EARLY_MIN = 10;
    public static final int NO_SHOW_MIN = 15;
    public static final int MAX_REPEAT = 26;
    public static final int MAX_PEOPLE = 60;

    public static final long MINUTE = 60_000L;

    private Rules() { }

    public static boolean openDay(LocalDate d) { return d.getDayOfWeek() != DayOfWeek.SUNDAY; }

    public static ZonedDateTime zoned(long ms) { return Instant.ofEpochMilli(ms).atZone(Clock.ZONE); }

    public static LocalDate dayOf(long ms) { return zoned(ms).toLocalDate(); }

    public static long at(LocalDate d, LocalTime t) { return d.atTime(t).atZone(Clock.ZONE).toInstant().toEpochMilli(); }

    public static long opens(LocalDate d) { return at(d, OPEN); }

    public static long closes(LocalDate d) { return at(d, CLOSE); }

    /** The next quarter hour at or after this moment. */
    public static long ceilStep(long ms) {
        long step = STEP_MIN * MINUTE;
        return ((ms + step - 1) / step) * step;
    }

    /** The quarter hour at or before this moment. (India time is UTC+5:30, a whole number of quarter hours.) */
    public static long floorStep(long ms) {
        long step = STEP_MIN * MINUTE;
        return Math.floorDiv(ms, step) * step;
    }

    public static boolean onStep(long ms) {
        ZonedDateTime z = zoned(ms);
        return z.getSecond() == 0 && z.getNano() == 0 && z.getMinute() % STEP_MIN == 0;
    }

    /** The next day the office is open (today counts if it is open). */
    public static LocalDate nextOpenDay(LocalDate d) {
        while (!openDay(d)) d = d.plusDays(1);
        return d;
    }

    /** Throws a friendly Problem if this time is not allowed. Used for every new booking and every change. */
    public static void checkTime(long start, long end, User who) {
        if (end <= start) throw Problem.bad("The meeting must end after it starts.");
        if (!onStep(start) || !onStep(end)) throw Problem.bad("Please start and end on a quarter hour (:00, :15, :30 or :45).");
        long minutes = (end - start) / MINUTE;
        int max = who != null && who.admin() ? MAX_MIN_ADMIN : MAX_MIN;
        if (minutes < MIN_MIN) throw Problem.bad("A meeting is at least " + MIN_MIN + " minutes.");
        if (minutes > max) throw Problem.bad("A meeting can be at most " + (max / 60) + " hours.");
        long now = Clock.millis();
        if (start < now - 5 * MINUTE) throw Problem.bad("That time is already over.");
        LocalDate d = dayOf(start);
        if (!dayOf(end - 1).equals(d)) throw Problem.bad("A meeting must start and end on the same day.");
        if (!openDay(d)) throw Problem.bad("The office is closed on Sunday.");
        if (start < opens(d) || end > closes(d))
            throw Problem.bad("Rooms can be booked from " + OPEN + " to " + CLOSE + ".");
        if (d.isAfter(Clock.today().plusDays(AHEAD_DAYS))) throw Problem.bad("You can book up to " + AHEAD_DAYS + " days ahead.");
    }

    /** The rules as data, for the website. */
    public static Map<String, Object> asJson() {
        return Json.obj("open", OPEN.toString(), "close", CLOSE.toString(), "openDays", "Mon–Sat", "stepMin", STEP_MIN,
                "minMin", MIN_MIN, "maxMin", MAX_MIN, "maxMinAdmin", MAX_MIN_ADMIN, "aheadDays", AHEAD_DAYS,
                "weekQuotaMin", WEEK_QUOTA_MIN, "checkinEarlyMin", CHECKIN_EARLY_MIN, "noShowMin", NO_SHOW_MIN,
                "maxRepeat", MAX_REPEAT, "maxPeople", MAX_PEOPLE);
    }
}
