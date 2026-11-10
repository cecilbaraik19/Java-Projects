package com.cecil.parisar.ai;

import com.cecil.parisar.model.Point;
import com.cecil.parisar.store.DataStore;
import com.cecil.parisar.util.Clock;
import com.cecil.parisar.util.Json;
import com.cecil.parisar.util.Stats;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI energy forecast for the next 12 hours: "seasonal profile x current level".
 *
 *   1. Profile: for every hour of the day, the average power of the past 14 days - separately for
 *      weekdays and weekends (a campus uses much less power on a Sunday).
 *   2. Level: how the last 3 hours compare with their profile (e.g. 1.10 = today is 10% busier than usual).
 *   3. Forecast for hour h = profile(h) x level.  The band around it = +/- 1.5 x the typical error.
 *   4. Back-test: we "forecast" yesterday using only the days before it and measure the error (MAPE).
 */
public class EnergyForecaster {
    public static final double TARIFF = 8.5;        // rupees per kWh (made-up commercial tariff)
    public static final double GRID_CO2 = 0.716;    // kg CO2 per kWh, India grid average (CEA)

    private final DataStore store;

    public EnergyForecaster(DataStore store) { this.store = store; }

    /** Hourly power (kW) of one meter, or of all meters added up (deviceId = null). time -> kW */
    public Map<Long, Double> series(String deviceId) {
        Map<Long, Double> out = new java.util.TreeMap<>();
        synchronized (store) {
            store.devices.values().stream().filter(d -> "ENERGY".equals(d.type) && (deviceId == null || d.id.equals(deviceId)))
                    .forEach(d -> {
                        for (Point p : store.hourlyOf(d.id)) {
                            double v = p.get("power");
                            if (!Double.isNaN(v)) out.merge(p.t, v, Double::sum);
                        }
                    });
        }
        return out;
    }

    static int hourOf(long t) { return ZonedDateTime.ofInstant(Instant.ofEpochMilli(t), Clock.ZONE).getHour(); }

    static boolean weekend(long t) {
        DayOfWeek d = ZonedDateTime.ofInstant(Instant.ofEpochMilli(t), Clock.ZONE).getDayOfWeek();
        return d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY;
    }

    /** Average power by (weekend?, hour) using only points before 'until'. */
    static Map<String, Double> profile(Map<Long, Double> s, long until) {
        Map<String, double[]> acc = new HashMap<>();
        for (Map.Entry<Long, Double> e : s.entrySet()) {
            if (e.getKey() >= until) continue;
            String k = (weekend(e.getKey()) ? "we" : "wd") + hourOf(e.getKey());
            double[] a = acc.computeIfAbsent(k, x -> new double[2]);
            a[0] += e.getValue();
            a[1]++;
        }
        Map<String, Double> p = new HashMap<>();
        for (Map.Entry<String, double[]> e : acc.entrySet()) p.put(e.getKey(), e.getValue()[0] / e.getValue()[1]);
        return p;
    }

    static double profileAt(Map<String, Double> p, long t) {
        Double v = p.get((weekend(t) ? "we" : "wd") + hourOf(t));
        if (v == null) v = p.get((weekend(t) ? "wd" : "we") + hourOf(t));
        return v == null ? Double.NaN : v;
    }

    /** Level factor: actual / profile over the last 3 complete hours before 'at', limited to 0.6 .. 1.5. */
    static double level(Map<Long, Double> s, Map<String, Double> p, long at) {
        double act = 0, exp = 0;
        for (int k = 1; k <= 3; k++) {
            long t = at - k * DataStore.HOUR_MS;
            Double a = s.get(t);
            double e = profileAt(p, t);
            if (a == null || Double.isNaN(e)) continue;
            act += a;
            exp += e;
        }
        return exp <= 0 ? 1 : Stats.clamp(act / exp, 0.6, 1.5);
    }

    public Map<String, Object> forecast(String deviceId, int hours) {
        Map<Long, Double> s = series(deviceId);
        long now = Clock.millis();
        long hourStart = now - Math.floorMod(now, DataStore.HOUR_MS);
        Map<String, Double> prof = profile(s, hourStart);
        double lvl = level(s, prof, hourStart);
        // back-test on the last complete 24 hours
        long btStart = hourStart - 24 * DataStore.HOUR_MS;
        Map<String, Double> btProf = profile(s, btStart);
        double btLvl = level(s, btProf, btStart);
        List<Double> errors = new ArrayList<>(), absPct = new ArrayList<>();
        List<Object> backtest = new ArrayList<>();
        for (long t = btStart; t < hourStart; t += DataStore.HOUR_MS) {
            Double a = s.get(t);
            double f = profileAt(btProf, t) * btLvl;
            if (a == null || Double.isNaN(f)) continue;
            errors.add(a - f);
            if (a > 1) absPct.add(Math.abs(a - f) / a * 100);
            backtest.add(Json.obj("t", t, "actual", Stats.round1(a), "forecast", Stats.round1(f)));
        }
        double band = 1.5 * (errors.isEmpty() ? 0 : Stats.std(errors));
        List<Object> fc = new ArrayList<>();
        double peak = 0;
        long peakAt = 0;
        double kwhNext = 0;
        for (int h = 0; h < hours; h++) {
            long t = hourStart + h * DataStore.HOUR_MS;
            double f = profileAt(prof, t) * lvl;
            if (Double.isNaN(f)) continue;
            if (f > peak) { peak = f; peakAt = t; }
            kwhNext += f;
            fc.add(Json.obj("t", t, "kw", Stats.round1(f), "low", Stats.round1(Math.max(0, f - band)), "high", Stats.round1(f + band)));
        }
        List<Object> past = new ArrayList<>();
        long from = hourStart - 36 * DataStore.HOUR_MS;
        for (Map.Entry<Long, Double> e : s.entrySet()) if (e.getKey() >= from) past.add(Json.obj("t", e.getKey(), "kw", Stats.round1(e.getValue())));
        double today = kwhBetween(s, startOfDay(now), now), yesterday = kwhBetween(s, startOfDay(now) - 24 * DataStore.HOUR_MS, startOfDay(now));
        return Json.obj("deviceId", deviceId, "level", Stats.round2(lvl), "past", past, "forecast", fc,
                "peakKw", Stats.round1(peak), "peakAt", peakAt, "kwhNext", Math.round(kwhNext),
                "mape", absPct.isEmpty() ? null : Stats.round1(Stats.mean(absPct)), "backtest", backtest,
                "kwhToday", Math.round(today), "kwhYesterday", Math.round(yesterday),
                "costToday", Math.round(today * TARIFF), "co2Today", Math.round(today * GRID_CO2),
                "method", "Seasonal profile (14 days, weekday/weekend) x level of the last 3 hours");
    }

    static long startOfDay(long t) {
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(t), Clock.ZONE).toLocalDate().atStartOfDay(Clock.ZONE).toInstant().toEpochMilli();
    }

    /** kWh = average kW in each hour x 1 hour, added up (the current hour counts only the part that has passed). */
    static double kwhBetween(Map<Long, Double> s, long from, long to) {
        double kwh = 0;
        for (Map.Entry<Long, Double> e : s.entrySet()) {
            long t = e.getKey();
            if (t < from || t >= to) continue;
            double part = Math.min(1.0, (to - t) / (double) DataStore.HOUR_MS);
            kwh += e.getValue() * part;
        }
        return kwh;
    }

    /** kWh per building per day for the last 14 days (for the analytics page). */
    public List<Object> dailyByBuilding() {
        List<Object> out = new ArrayList<>();
        synchronized (store) {
            store.devices.values().stream().filter(d -> "ENERGY".equals(d.type)).forEach(d -> {
                Map<Long, Double> s = series(d.id);
                List<Object> days = new ArrayList<>();
                long today = startOfDay(Clock.millis());
                for (int k = 13; k >= 0; k--) {
                    long from = today - k * 24 * DataStore.HOUR_MS;
                    days.add(Json.obj("day", from, "kwh", Math.round(kwhBetween(s, from, Math.min(from + 24 * DataStore.HOUR_MS, Clock.millis())))));
                }
                out.add(Json.obj("deviceId", d.id, "buildingId", d.buildingId, "building", store.buildings.get(d.buildingId).name, "days", days));
            });
        }
        return out;
    }
}
