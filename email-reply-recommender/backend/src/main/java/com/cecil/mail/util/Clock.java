package com.cecil.mail.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * One place to ask "what time is it?". Tests can freeze the clock so results are always the same.
 */
public final class Clock {

    private static volatile Instant fixed;

    private Clock() { }

    public static Instant now() { return fixed != null ? fixed : Instant.now(); }

    public static long millis() { return now().toEpochMilli(); }

    public static LocalDateTime localNow() { return LocalDateTime.ofInstant(now(), ZoneId.systemDefault()); }

    /** Used by tests only. Pass null to go back to the real clock. */
    public static void fix(Instant instant) { fixed = instant; }
}
