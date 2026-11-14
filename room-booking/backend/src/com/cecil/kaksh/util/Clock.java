package com.cecil.kaksh.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The one place the app asks "what time is it?".
 * Tests fix the clock to a known moment so results never change from day to day.
 */
public final class Clock {
    /** India Standard Time - used for "today", due dates and month names. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private static volatile Instant fixed = null;

    private Clock() { }

    public static Instant now() {
        Instant f = fixed;
        return f != null ? f : Instant.now();
    }

    public static long millis() { return now().toEpochMilli(); }

    public static LocalDate today() { return LocalDate.ofInstant(now(), ZONE); }

    /** Tests only: freeze the clock (pass null to go back to the real time). */
    public static void fix(Instant at) { fixed = at; }

    /** The frozen time, or null when the clock is real (the demo-data builder puts it back when done). */
    public static Instant fixed() { return fixed; }

    /** Tests only: move a frozen clock forward. */
    public static void advanceMillis(long ms) {
        Instant f = fixed;
        if (f != null) fixed = f.plusMillis(ms);
    }
}
