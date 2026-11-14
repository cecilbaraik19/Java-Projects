package com.cecil.kaksh.service;

import com.cecil.kaksh.util.Clock;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Dates and times written the way people in India read them, e.g. "Mon 16 Nov, 10:30–11:30". */
public final class Format {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH).withZone(Clock.ZONE);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH).withZone(Clock.ZONE);

    private Format() { }

    public static String time(long ms) { return TIME.format(Instant.ofEpochMilli(ms)); }

    public static String day(long ms) { return DAY.format(Instant.ofEpochMilli(ms)); }

    public static String when(long start, long end) { return day(start) + ", " + time(start) + "–" + time(end); }
}
