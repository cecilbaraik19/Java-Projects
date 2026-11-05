package com.cecil.sql.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** One place for "now", so tests can fix the date. */
public final class Clock {
    private Clock() {}

    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static volatile Instant fixed = null;

    public static Instant now() { return fixed != null ? fixed : Instant.now(); }
    public static LocalDate today() { return LocalDate.ofInstant(now(), ZONE); }
    public static long millis() { return now().toEpochMilli(); }

    /** Tests only: freeze the clock (null to unfreeze). */
    public static void fix(Instant at) { fixed = at; }
}
