package com.cecil.energy.insights;

import com.cecil.energy.model.Appliance;
import com.cecil.energy.model.Enums.ApplianceType;
import com.cecil.energy.model.Enums.UsageSource;
import com.cecil.energy.model.Settings;
import com.cecil.energy.model.UsageLog;
import com.cecil.energy.util.Num;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The basic energy maths, in one place.
 *
 *   kWh (units) = watts x hours / 1000
 *   A 1000 W heater running for 1 hour uses 1 kWh = 1 unit on the bill.
 */
public final class EnergyMath {

    /** Air conditioner: every 1 deg C warmer setting uses about 6% less power (BEE guidance). */
    public static final double AC_SAVING_PER_DEGREE = 0.06;
    /** Recommended AC setting (BEE default is 24 deg C). */
    public static final int AC_BEST_TEMP = 24;

    private EnergyMath() { }

    /** How much more power a lower star rating uses, compared with a 5-star model (5-star = 1.00). */
    public static double starFactor(int stars) {
        return switch (stars) {
            case 5 -> 1.00;
            case 4 -> 1.10;
            case 3 -> 1.21;
            case 2 -> 1.33;
            case 1 -> 1.46;
            default -> 1.25; // not rated: treat as about average
        };
    }

    /** Running energy for one day at a given setting. */
    public static double runningKwh(double watts, double hours) {
        return watts * hours / 1000.0;
    }

    /** Standby energy for one day: plugged in but not running for (24 - hours) hours. */
    public static double standbyKwh(double standbyWatts, double hours) {
        return standbyWatts * Math.max(0, 24 - hours) / 1000.0;
    }

    /**
     * How many hours of the time [startHour, startHour + hours) fall inside the clock range [rangeStart, rangeEnd).
     * Works for times that go past midnight. Example: start 20, 3 hours, peak 18-22 -> 2 hours in peak.
     */
    public static double hoursInRange(double startHour, double hours, int rangeStart, int rangeEnd) {
        double total = 0;
        double t = startHour;
        double end = startHour + Math.min(24, Math.max(0, hours));
        while (t < end - 1e-9) {
            double next = Math.min(end, Math.floor(t) + 1);
            int hourOfDay = ((int) Math.floor(t)) % 24;
            if (Settings.inRange(hourOfDay, rangeStart, rangeEnd)) total += next - t;
            t = next;
        }
        return total;
    }

    /** One day's use for an appliance at its normal settings (its "profile"). */
    public static Day profileDay(Appliance a, Settings s) {
        return day(a, s, a.hoursPerDay, a.usualStartHour);
    }

    /** One day's use if the appliance runs for "hours" starting at "startHour". */
    public static Day day(Appliance a, Settings s, double hours, double startHour) {
        Day d = new Day();
        d.hours = Num.clamp(hours, 0, 24);
        d.peakHours = hoursInRange(startHour, d.hours, s.peakStart, s.peakEnd);
        d.offPeakHours = hoursInRange(startHour, d.hours, s.offPeakStart, s.offPeakEnd);
        d.kwh = runningKwh(a.watts, d.hours);
        d.standbyKwh = standbyKwh(a.standbyWatts, d.hours);
        return d;
    }

    /** Makes a usage record for a date from a Day. */
    public static UsageLog toLog(String id, Appliance a, LocalDate date, Day d, UsageSource src, String by) {
        UsageLog u = new UsageLog();
        u.id = id;
        u.applianceId = a.id;
        u.date = date.toString();
        u.hours = Num.round(d.hours, 3);
        u.peakHours = Num.round(d.peakHours, 3);
        u.offPeakHours = Num.round(d.offPeakHours, 3);
        u.kwh = Num.round(d.kwh, 4);
        u.standbyKwh = Num.round(d.standbyKwh, 4);
        u.source = src;
        u.by = by;
        return u;
    }

    /**
     * Splits a running period (switched on at "from", off at "to") into days,
     * and counts peak and off-peak hours in each day. Used by the live on/off switch.
     */
    public static Map<LocalDate, double[]> splitByDay(LocalDateTime from, LocalDateTime to, Settings s) {
        Map<LocalDate, double[]> out = new LinkedHashMap<>(); // date -> {hours, peakHours, offPeakHours}
        if (to == null || from == null || !to.isAfter(from)) return out;
        LocalDateTime t = from;
        while (t.isBefore(to)) {
            LocalDateTime nextHour = t.withMinute(0).withSecond(0).withNano(0).plusHours(1);
            LocalDateTime next = nextHour.isBefore(to) ? nextHour : to;
            double h = Duration.between(t, next).toMillis() / 3_600_000.0;
            double[] v = out.computeIfAbsent(t.toLocalDate(), k -> new double[3]);
            v[0] += h;
            if (Settings.inRange(t.getHour(), s.peakStart, s.peakEnd)) v[1] += h;
            if (Settings.inRange(t.getHour(), s.offPeakStart, s.offPeakEnd)) v[2] += h;
            t = next;
        }
        return out;
    }

    /** Should this appliance type be shown with a temperature setting? */
    public static boolean hasTemp(ApplianceType t) {
        return t == ApplianceType.AIR_CONDITIONER;
    }

    /** One day's numbers. */
    public static class Day {
        public double hours;
        public double peakHours;
        public double offPeakHours;
        public double kwh;
        public double standbyKwh;

        public double total() { return kwh + standbyKwh; }

        public double peakKwh() { return hours <= 0 ? 0 : kwh * peakHours / hours; }

        public double offPeakKwh() { return hours <= 0 ? 0 : kwh * offPeakHours / hours; }
    }
}
