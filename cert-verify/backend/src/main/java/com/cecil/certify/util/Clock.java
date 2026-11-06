package com.cecil.certify.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** One place to ask "what time is it?". Tests can fix the time so results never change. */
public final class Clock {
    private Clock() {}

    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static volatile Long fixedMillis = null;

    public static long now() {
        Long f = fixedMillis;
        return f != null ? f : System.currentTimeMillis();
    }

    public static LocalDate today() {
        return Instant.ofEpochMilli(now()).atZone(ZONE).toLocalDate();
    }

    /** For tests only. Pass null to go back to the real clock. */
    public static void fix(Long millis) {
        fixedMillis = millis;
    }

    /** For tests only: move a fixed clock forward. */
    public static void advance(long millis) {
        if (fixedMillis != null) fixedMillis += millis;
    }
}
