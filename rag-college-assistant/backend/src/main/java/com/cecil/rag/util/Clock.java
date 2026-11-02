package com.cecil.rag.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * One place that says "what time is it now". Tests can fix the time so results never change.
 */
public final class Clock {
    private Clock() {}

    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static volatile Instant fixed = null;

    public static long now() { return fixed != null ? fixed.toEpochMilli() : System.currentTimeMillis(); }
    public static LocalDate today() { return Instant.ofEpochMilli(now()).atZone(ZONE).toLocalDate(); }
    public static void fix(Instant at) { fixed = at; }
    public static void unfix() { fixed = null; }
    public static Instant fixedOrNull() { return fixed; }

    public static LocalDate dateOf(long millis) { return Instant.ofEpochMilli(millis).atZone(ZONE).toLocalDate(); }
    public static int hourOf(long millis) { return Instant.ofEpochMilli(millis).atZone(ZONE).getHour(); }
}
