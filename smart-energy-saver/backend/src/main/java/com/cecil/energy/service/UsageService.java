package com.cecil.energy.service;

import com.cecil.energy.insights.EnergyMath;
import com.cecil.energy.model.Appliance;
import com.cecil.energy.model.Enums;
import com.cecil.energy.model.Enums.UsageSource;
import com.cecil.energy.model.MeterReading;
import com.cecil.energy.model.UsageLog;
import com.cecil.energy.model.User;
import com.cecil.energy.store.DataStore;
import com.cecil.energy.util.Csv;
import com.cecil.energy.util.Json;
import com.cecil.energy.util.Num;
import com.cecil.energy.util.Passwords;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Usage records (how much each appliance used on each day) and main meter readings.
 *
 * Days with no record are filled in automatically from the appliance's normal hours ("estimate"),
 * so charts never have holes. A real measurement (live switch or a typed record) always wins over an estimate.
 */
public class UsageService {

    private final DataStore store;
    private volatile String filledUpTo = "";

    public UsageService(DataStore store) {
        this.store = store;
    }

    // ================================================================== usage records

    public List<Map<String, Object>> list(String applianceId, String roomId, String from, String to, String source, int limit) {
        synchronized (store.lock) {
            List<UsageLog> rows = filter(applianceId, roomId, from, to, source);
            rows.sort(Comparator.comparing((UsageLog u) -> u.date).reversed().thenComparing(u -> store.appliance(u.applianceId).name));
            List<Map<String, Object>> out = new ArrayList<>();
            double rate = AnalyticsService.ratePerUnit(store);
            for (UsageLog u : rows) {
                if (out.size() >= limit) break;
                out.add(view(u, rate));
            }
            return out;
        }
    }

    private List<UsageLog> filter(String applianceId, String roomId, String from, String to, String source) {
        List<UsageLog> rows = new ArrayList<>();
        for (UsageLog u : store.usage) {
            Appliance a = store.appliance(u.applianceId);
            if (a == null) continue;
            if (notEmpty(applianceId) && !applianceId.equals(u.applianceId)) continue;
            if (notEmpty(roomId) && !roomId.equals(a.roomId)) continue;
            if (notEmpty(from) && u.date.compareTo(from) < 0) continue;
            if (notEmpty(to) && u.date.compareTo(to) > 0) continue;
            if (notEmpty(source) && !source.equals(u.source.name())) continue;
            rows.add(u);
        }
        return rows;
    }

    private Map<String, Object> view(UsageLog u, double rate) {
        Map<String, Object> m = u.toMap();
        Appliance a = store.appliance(u.applianceId);
        m.put("applianceName", a == null ? "?" : a.name);
        m.put("roomName", a == null ? "" : store.roomName(a.roomId));
        m.put("totalKwh", Num.round(u.totalKwh(), 3));
        m.put("cost", Num.round(u.totalKwh() * rate, 2));
        m.put("byName", store.displayName(u.by));
        return m;
    }

    /** Adds or replaces a typed usage record for one appliance and day. */
    public Map<String, Object> saveManual(User me, String id, Map<String, Object> body) {
        synchronized (store.lock) {
            UsageLog existing = null;
            if (id != null) {
                for (UsageLog u : store.usage) if (u.id.equals(id)) existing = u;
                if (existing == null) throw ServiceException.notFound("No such record.");
                if (!me.role.canManage() && !me.username.equals(existing.by)) {
                    throw ServiceException.forbidden("You can only change records you added.");
                }
            }
            String applianceId = existing != null ? existing.applianceId : Json.str(body, "applianceId");
            Appliance a = store.appliance(applianceId);
            if (a == null) throw ServiceException.bad("Choose an appliance.");
            LocalDate date = existing != null && !body.containsKey("date") ? LocalDate.parse(existing.date) : parseDate(Json.str(body, "date"));
            if (date.isAfter(store.today())) throw ServiceException.bad("The date cannot be in the future.");
            if (date.isBefore(store.today().minusDays(400))) throw ServiceException.bad("The date is too far in the past.");
            double hours = Json.num(body, "hours", -1);
            if (hours < 0 || hours > 24) throw ServiceException.bad("Hours must be between 0 and 24.");
            int start = Json.integer(body, "startHour", a.usualStartHour);
            if (start < 0 || start > 23) throw ServiceException.bad("Start hour must be 0 to 23.");

            // only one record per appliance per day: replace the old one
            String d = date.toString();
            UsageLog same = null;
            for (UsageLog u : store.usage) if (u.applianceId.equals(a.id) && u.date.equals(d) && u != existing) same = u;
            if (same != null) store.usage.remove(same);
            if (existing != null) store.usage.remove(existing);

            EnergyMath.Day day = EnergyMath.day(a, store.settings, hours, start);
            UsageLog log = EnergyMath.toLog(existing != null ? existing.id : "us" + Passwords.randomId(), a, date, day, UsageSource.MANUAL, me.username);
            log.note = AuthService.clean(Json.str(body, "note", ""), 200);
            store.usage.add(log);
            store.saveUsage();
            return view(log, AnalyticsService.ratePerUnit(store));
        }
    }

    public void delete(User me, String id) {
        AuthService.requireManager(me);
        synchronized (store.lock) {
            if (!store.usage.removeIf(u -> u.id.equals(id))) throw ServiceException.notFound("No such record.");
            store.saveUsage();
        }
    }

    /** CSV file of usage records (same filters as the list). */
    public String csv(String applianceId, String roomId, String from, String to, String source) {
        synchronized (store.lock) {
            List<UsageLog> rows = filter(applianceId, roomId, from, to, source);
            rows.sort(Comparator.comparing((UsageLog u) -> u.date).thenComparing(u -> store.appliance(u.applianceId).name));
            double rate = AnalyticsService.ratePerUnit(store);
            StringBuilder sb = new StringBuilder("﻿"); // BOM so Excel shows ₹ and other characters correctly
            sb.append(Csv.row(List.of("Date", "Appliance", "Room", "Type", "Hours on", "Peak hours", "Running kWh",
                    "Standby kWh", "Total kWh", "Approx cost (Rs)", "CO2 (kg)", "Source", "Note")));
            for (UsageLog u : rows) {
                Appliance a = store.appliance(u.applianceId);
                sb.append(Csv.row(List.of(u.date, a.name, store.roomName(a.roomId), a.type.label, Num.round(u.hours, 2),
                        Num.round(u.peakHours, 2), Num.round(u.kwh, 3), Num.round(u.standbyKwh, 3), Num.round(u.totalKwh(), 3),
                        Num.round(u.totalKwh() * rate, 2), Num.round(u.totalKwh() * store.settings.co2PerKwh, 3),
                        u.source.name(), u.note)));
            }
            return sb.toString();
        }
    }

    // ================================================================== estimates

    /**
     * Fills days that have no record with an estimate from each appliance's normal hours.
     * Only fills days from when the appliance was added. Returns the number of records made.
     */
    public int autofill(LocalDate from, LocalDate to, String by) {
        synchronized (store.lock) {
            Set<String> have = new HashSet<>();
            for (UsageLog u : store.usage) have.add(u.applianceId + "|" + u.date);
            int made = 0;
            for (Appliance a : store.appliances) {
                LocalDate added = a.createdAt == null ? from : LocalDate.parse(a.createdAt.substring(0, 10));
                EnergyMath.Day day = EnergyMath.profileDay(a, store.settings);
                for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                    if (d.isBefore(added) || have.contains(a.id + "|" + d)) continue;
                    store.usage.add(EnergyMath.toLog("us" + Passwords.randomId(), a, d, day, UsageSource.ESTIMATE, by));
                    made++;
                }
            }
            if (made > 0) store.saveUsage();
            return made;
        }
    }

    /** Makes sure every day up to yesterday has records (looks back at most 62 days). Cheap to call often. */
    public int ensureFilled() {
        LocalDate yesterday = store.today().minusDays(1);
        if (yesterday.toString().equals(filledUpTo)) return 0;
        String last = "";
        synchronized (store.lock) {
            for (UsageLog u : store.usage) if (u.date.compareTo(last) > 0 && u.date.compareTo(yesterday.toString()) <= 0) last = u.date;
        }
        LocalDate from = last.isEmpty() ? yesterday.minusDays(6) : LocalDate.parse(last);
        if (from.isBefore(yesterday.minusDays(62))) from = yesterday.minusDays(62);
        int n = autofill(from, yesterday, "system");
        filledUpTo = yesterday.toString();
        return n;
    }

    // ================================================================== meter readings

    public List<Map<String, Object>> meterReadings() {
        synchronized (store.lock) {
            List<MeterReading> list = new ArrayList<>(store.meter);
            list.sort(Comparator.comparing((MeterReading m) -> m.date));
            List<Map<String, Object>> out = new ArrayList<>();
            MeterReading prev = null;
            for (MeterReading r : list) {
                Map<String, Object> m = r.toMap();
                m.put("byName", store.displayName(r.by));
                if (prev != null) {
                    double units = r.reading - prev.reading;
                    long days = LocalDate.parse(r.date).toEpochDay() - LocalDate.parse(prev.date).toEpochDay();
                    m.put("unitsSincePrevious", Num.round(units, 1));
                    m.put("days", days);
                    m.put("perDay", days > 0 ? Num.round(units / days, 2) : null);
                }
                out.add(0, m);
                prev = r;
            }
            return out;
        }
    }

    public Map<String, Object> addMeter(User me, Map<String, Object> body) {
        synchronized (store.lock) {
            LocalDate date = parseDate(Json.str(body, "date"));
            if (date.isAfter(store.today())) throw ServiceException.bad("The date cannot be in the future.");
            double reading = Json.num(body, "reading", -1);
            if (reading < 0 || reading > 10_000_000) throw ServiceException.bad("Enter the meter number (kWh).");
            String d = date.toString();
            MeterReading before = null, after = null; // the closest readings before and after this date
            for (MeterReading m : store.meter) {
                if (m.date.equals(d)) throw ServiceException.conflict("There is already a reading for that day. Delete it first.");
                if (m.date.compareTo(d) < 0 && (before == null || m.date.compareTo(before.date) > 0)) before = m;
                if (m.date.compareTo(d) > 0 && (after == null || m.date.compareTo(after.date) < 0)) after = m;
            }
            if (before != null && reading < before.reading) {
                throw ServiceException.bad("The reading is lower than the one on " + before.date + " (" + before.reading + "). A meter only goes up.");
            }
            if (after != null && reading > after.reading) {
                throw ServiceException.bad("The reading is higher than the later one on " + after.date + " (" + after.reading + ").");
            }
            MeterReading r = new MeterReading();
            r.id = "mr" + Passwords.randomId();
            r.date = d;
            r.reading = Num.round(reading, 1);
            r.note = AuthService.clean(Json.str(body, "note", ""), 200);
            r.by = me.username;
            store.meter.add(r);
            store.saveMeter();
            return r.toMap();
        }
    }

    public void deleteMeter(User me, String id) {
        AuthService.requireManager(me);
        synchronized (store.lock) {
            if (!store.meter.removeIf(m -> m.id.equals(id))) throw ServiceException.notFound("No such reading.");
            store.saveMeter();
        }
    }

    // ================================================================== helpers

    static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) throw ServiceException.bad("Choose a date.");
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw ServiceException.bad("Date must look like 2026-10-28.");
        }
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    /** For the usage filter dropdown. */
    public static UsageSource parseSource(String s) {
        return Enums.parse(UsageSource.class, s, null);
    }
}
