package com.cecil.energy.service;

import com.cecil.energy.insights.BillCalculator;
import com.cecil.energy.insights.BillCalculator.Bill;
import com.cecil.energy.insights.EnergyMath;
import com.cecil.energy.insights.Insights;
import com.cecil.energy.insights.Insights.ApplianceStats;
import com.cecil.energy.insights.Insights.HomeMonth;
import com.cecil.energy.insights.Insights.Tip;
import com.cecil.energy.model.Appliance;
import com.cecil.energy.model.Enums.ApplianceType;
import com.cecil.energy.model.Enums.TipStatus;
import com.cecil.energy.model.Room;
import com.cecil.energy.model.Settings;
import com.cecil.energy.model.TipRecord;
import com.cecil.energy.model.UsageLog;
import com.cecil.energy.model.User;
import com.cecil.energy.store.DataStore;
import com.cecil.energy.util.Json;
import com.cecil.energy.util.Num;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Numbers for the dashboard, the reports page, one appliance's page and the savings planner. */
public class AnalyticsService {

    private final DataStore store;
    private final ApplianceService appliances;

    public AnalyticsService(DataStore store, ApplianceService appliances) {
        this.store = store;
        this.appliances = appliances;
    }

    /**
     * Average price of one unit right now (energy charge + time-of-day effect + duty, without the fixed charge),
     * based on the last 30 days. Used to show the cost of single records and of running appliances.
     */
    public static double ratePerUnit(DataStore store) {
        HomeMonth hm = new Insights(store).last30();
        Bill b = hm.bill(store.settings);
        if (b.units <= 0) return store.settings.slabs.isEmpty() ? 0 : store.settings.slabs.get(0).rate;
        return (b.total - b.fixedCharge) / b.units;
    }

    // ================================================================== dashboard

    public Map<String, Object> dashboard(User me) {
        synchronized (store.lock) {
            Settings s = store.settings;
            Insights in = new Insights(store);
            HomeMonth hm = in.last30();
            LocalDate today = store.today();
            LocalDateTime now = store.now();
            Bill bill30 = hm.bill(s);
            double rate = bill30.units > 0 ? (bill30.total - bill30.fixedCharge) / bill30.units : 0;

            // today so far = records for today + time the running appliances have been on today
            double todayKwh = 0, yesterdayKwh = 0;
            String t = today.toString(), y = today.minusDays(1).toString();
            for (UsageLog u : store.usage) {
                if (u.date.equals(t)) todayKwh += u.kwh; // standby for today is added below for the hours passed
                else if (u.date.equals(y)) yesterdayKwh += u.totalKwh();
            }
            double hoursPassed = now.getHour() + now.getMinute() / 60.0;
            double load = 0, standbyLoad = 0;
            List<Map<String, Object>> onNow = new ArrayList<>();
            for (Appliance a : store.appliances) {
                if (a.on) {
                    load += a.watts;
                    LocalDateTime since = LocalDateTime.parse(a.onSince, DataStore.TIME);
                    LocalDateTime fromToday = since.toLocalDate().isBefore(today) ? today.atStartOfDay() : since;
                    todayKwh += a.watts * Math.max(0, Duration.between(fromToday, now).toMinutes()) / 60000.0;
                    onNow.add(appliances.view(a));
                } else {
                    standbyLoad += a.standbyWatts;
                }
                todayKwh += a.standbyWatts * hoursPassed / 1000.0 * 0.9; // roughly the standby hours so far
            }
            boolean peakNow = Settings.inRange(now.getHour(), s.peakStart, s.peakEnd);
            boolean offPeakNow = Settings.inRange(now.getHour(), s.offPeakStart, s.offPeakEnd);
            double rateNow = rate * (1 + (peakNow ? s.peakSurchargePercent : offPeakNow ? -s.offPeakRebatePercent : 0) / 100.0);

            Forecast f = forecast(hm, today);
            Bill forecastBill = BillCalculator.bill(f.kwh, f.kwh * share(hm.peakKwh, hm.kwh), f.kwh * share(hm.offPeakKwh, hm.kwh), s);

            Map<String, Object> kpis = new LinkedHashMap<>();
            kpis.put("todayKwh", Num.round(todayKwh, 2));
            kpis.put("yesterdayKwh", Num.round(yesterdayKwh, 2));
            kpis.put("monthKwh", Num.round(f.monthToDate + todayKwh, 1));
            kpis.put("forecastKwh", Num.round(f.kwh, 1));
            kpis.put("targetKwh", s.monthlyTargetKwh);
            kpis.put("forecastBill", Math.round(forecastBill.total));
            kpis.put("budget", s.monthlyBudget);
            kpis.put("loadWatts", Math.round(load + standbyLoad));
            kpis.put("runningWatts", Math.round(load));
            kpis.put("standbyWatts", Math.round(standbyLoad));
            kpis.put("costPerHourNow", Num.round((load + standbyLoad) / 1000.0 * rateNow, 2));
            kpis.put("ratePerUnit", Num.round(rate, 2));
            kpis.put("rateNow", Num.round(rateNow, 2));
            kpis.put("peakNow", peakNow);
            kpis.put("offPeakNow", offPeakNow);
            kpis.put("co2Month", Num.round(f.kwh * s.co2PerKwh, 1));
            kpis.put("daysInMonth", today.lengthOfMonth());
            kpis.put("dayOfMonth", today.getDayOfMonth());

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("homeName", s.homeName);
            m.put("kpis", kpis);
            m.put("score", in.score(f.kwh));
            m.put("daily", dailySeries(today.minusDays(30), today.minusDays(1)));
            m.put("dailyTarget", Num.round(s.monthlyTargetKwh / 30.0, 2));
            m.put("byRoom", byRoom(in));
            m.put("split", split(hm));
            List<Map<String, Object>> top = topAppliances(in, rate);
            m.put("topAppliances", top.size() > 6 ? top.subList(0, 6) : top);
            List<Tip> tips = in.openTips();
            double potKwh = 0, potRs = 0;
            List<Map<String, Object>> tipMaps = new ArrayList<>();
            for (Tip tp : tips) {
                potKwh += tp.kwhPerMonth;
                potRs += tp.rupeesPerMonth;
                if (tipMaps.size() < 4) tipMaps.add(tp.toMap());
            }
            m.put("tips", tipMaps);
            m.put("tipCount", tips.size());
            m.put("potentialKwh", Num.round(potKwh, 1));
            m.put("potentialRupees", Math.round(potRs));
            m.put("alerts", in.alerts(f.kwh, forecastBill.total));
            m.put("onNow", onNow);
            m.put("events", appliances.events(null, 8));
            m.put("peakStart", s.peakStart);
            m.put("peakEnd", s.peakEnd);
            return m;
        }
    }

    private static double share(double part, double whole) {
        return whole <= 0 ? 0 : part / whole;
    }

    /** This month's units so far (without today) and the expected total for the month. */
    static class Forecast {
        double monthToDate;
        double kwh;
    }

    private Forecast forecast(HomeMonth hm, LocalDate today) {
        Forecast f = new Forecast();
        String first = today.withDayOfMonth(1).toString(), y = today.minusDays(1).toString();
        for (UsageLog u : store.usage) if (u.date.compareTo(first) >= 0 && u.date.compareTo(y) <= 0) f.monthToDate += u.totalKwh();
        int daysPast = today.getDayOfMonth() - 1;
        double perDay = daysPast >= 3 ? f.monthToDate / daysPast : hm.kwh / 30.0;
        f.kwh = f.monthToDate + perDay * (today.lengthOfMonth() - daysPast);
        return f;
    }

    private List<Map<String, Object>> dailySeries(LocalDate from, LocalDate to) {
        Map<String, double[]> byDay = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) byDay.put(d.toString(), new double[3]);
        for (UsageLog u : store.usage) {
            double[] v = byDay.get(u.date);
            if (v == null) continue;
            v[0] += u.totalKwh();
            v[1] += u.peakKwh();
            v[2] += u.standbyKwh;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, double[]> e : byDay.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            LocalDate d = LocalDate.parse(e.getKey());
            m.put("date", e.getKey());
            m.put("label", d.getDayOfMonth() + " " + d.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
            m.put("weekend", d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY);
            m.put("kwh", Num.round(e.getValue()[0], 2));
            m.put("peakKwh", Num.round(e.getValue()[1], 2));
            m.put("standbyKwh", Num.round(e.getValue()[2], 2));
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> byRoom(Insights in) {
        Map<String, Double> sums = new LinkedHashMap<>();
        List<Room> rooms = new ArrayList<>(store.rooms);
        rooms.sort(Comparator.comparingInt(r -> r.sortOrder));
        for (Room r : rooms) sums.put(r.id, 0.0);
        double total = 0;
        for (Appliance a : store.appliances) {
            double k = in.stats(a.id).kwh30;
            sums.merge(a.roomId, k, Double::sum);
            total += k;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Double> e : sums.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("roomId", e.getKey());
            m.put("name", store.roomName(e.getKey()));
            m.put("kwh", Num.round(e.getValue(), 1));
            m.put("share", total > 0 ? Num.round(e.getValue() / total * 100, 1) : 0.0);
            out.add(m);
        }
        out.sort(Comparator.comparingDouble((Map<String, Object> m) -> -((Number) m.get("kwh")).doubleValue()));
        return out;
    }

    private Map<String, Object> split(HomeMonth hm) {
        Map<String, Object> m = new LinkedHashMap<>();
        double running = hm.kwh - hm.standbyKwh;
        double normal = Math.max(0, running - hm.peakKwh - hm.offPeakKwh);
        m.put("peak", Num.round(hm.peakKwh, 1));
        m.put("normal", Num.round(normal, 1));
        m.put("offPeak", Num.round(hm.offPeakKwh, 1));
        m.put("standby", Num.round(hm.standbyKwh, 1));
        m.put("total", Num.round(hm.kwh, 1));
        return m;
    }

    private List<Map<String, Object>> topAppliances(Insights in, double rate) {
        List<Map<String, Object>> out = new ArrayList<>();
        double total = 0;
        for (Appliance a : store.appliances) total += in.stats(a.id).kwh30;
        for (Appliance a : store.appliances) {
            ApplianceStats st = in.stats(a.id);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.id);
            m.put("name", a.name);
            m.put("type", a.type);
            m.put("typeLabel", a.type.label);
            m.put("roomName", store.roomName(a.roomId));
            m.put("kwh", Num.round(st.kwh30, 1));
            m.put("standbyKwh", Num.round(st.standbyKwh30, 1));
            m.put("cost", Math.round(st.kwh30 * rate));
            m.put("share", total > 0 ? Num.round(st.kwh30 / total * 100, 1) : 0.0);
            m.put("avgHours", Num.round(st.avgHours(), 1));
            m.put("spike", Insights.isSpike(st));
            out.add(m);
        }
        out.sort(Comparator.comparingDouble((Map<String, Object> m) -> -((Number) m.get("kwh")).doubleValue()));
        return out;
    }

    // ================================================================== reports

    public Map<String, Object> reports(int months) {
        months = Math.max(3, Math.min(12, months));
        synchronized (store.lock) {
            Settings s = store.settings;
            Insights in = new Insights(store);
            HomeMonth hm = in.last30();
            LocalDate today = store.today();
            Bill bill30 = hm.bill(s);
            double rate = bill30.units > 0 ? (bill30.total - bill30.fixedCharge) / bill30.units : 0;

            // ---- monthly totals
            YearMonth cur = YearMonth.from(today);
            Map<YearMonth, double[]> mon = new LinkedHashMap<>(); // kwh, peak, offPeak, standby
            for (int i = months - 1; i >= 0; i--) mon.put(cur.minusMonths(i), new double[4]);
            Map<YearMonth, java.util.Set<String>> monthDays = new HashMap<>();
            for (UsageLog u : store.usage) {
                YearMonth ym = YearMonth.parse(u.date.substring(0, 7));
                double[] v = mon.get(ym);
                if (v == null) continue;
                v[0] += u.totalKwh();
                v[1] += u.peakKwh();
                v[2] += u.offPeakKwh();
                v[3] += u.standbyKwh;
                monthDays.computeIfAbsent(ym, k -> new java.util.HashSet<>()).add(u.date);
            }
            Forecast f = forecast(hm, today);
            List<Map<String, Object>> monthly = new ArrayList<>();
            for (Map.Entry<YearMonth, double[]> e : mon.entrySet()) {
                double[] v = e.getValue();
                Map<String, Object> m = new LinkedHashMap<>();
                YearMonth ym = e.getKey();
                boolean isCurrent = ym.equals(cur);
                m.put("month", ym.toString());
                m.put("label", ym.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + String.valueOf(ym.getYear()).substring(2));
                m.put("kwh", Num.round(v[0], 1));
                m.put("peakKwh", Num.round(v[1], 1));
                m.put("standbyKwh", Num.round(v[3], 1));
                m.put("days", monthDays.getOrDefault(ym, java.util.Set.of()).size());
                m.put("current", isCurrent);
                Bill b = BillCalculator.bill(v[0], v[1], v[2], s);
                m.put("bill", Math.round(b.total));
                m.put("co2", Num.round(v[0] * s.co2PerKwh, 1));
                if (isCurrent) {
                    m.put("forecastKwh", Num.round(f.kwh, 1));
                    m.put("forecastBill", Math.round(BillCalculator.bill(f.kwh, f.kwh * share(hm.peakKwh, hm.kwh),
                            f.kwh * share(hm.offPeakKwh, hm.kwh), s).total));
                }
                monthly.add(m);
            }

            // ---- by appliance type (last 30 days)
            Map<ApplianceType, Double> byType = new LinkedHashMap<>();
            for (Appliance a : store.appliances) byType.merge(a.type, in.stats(a.id).kwh30, Double::sum);
            List<Map<String, Object>> types = new ArrayList<>();
            for (Map.Entry<ApplianceType, Double> e : byType.entrySet()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", e.getKey());
                m.put("label", e.getKey().label);
                m.put("kwh", Num.round(e.getValue(), 1));
                m.put("cost", Math.round(e.getValue() * rate));
                types.add(m);
            }
            types.sort(Comparator.comparingDouble((Map<String, Object> m) -> -((Number) m.get("kwh")).doubleValue()));

            // ---- when is power used: 24 hourly bars from each appliance's normal profile, scaled to real use
            double[] hourly = new double[24];
            for (Appliance a : store.appliances) {
                ApplianceStats st = in.stats(a.id);
                double perHourKw = a.watts / 1000.0;
                double hours = st.daysWithData >= 3 ? st.avgHours() : a.hoursPerDay;
                double t = a.usualStartHour, end = a.usualStartHour + Math.min(24, hours);
                while (t < end - 1e-9) {
                    double next = Math.min(end, Math.floor(t) + 1);
                    hourly[((int) Math.floor(t)) % 24] += perHourKw * (next - t);
                    t = next;
                }
                for (int h = 0; h < 24; h++) hourly[h] += a.standbyWatts / 1000.0 * (1 - Math.min(24, hours) / 24.0);
            }
            List<Map<String, Object>> hours = new ArrayList<>();
            for (int h = 0; h < 24; h++) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("hour", h);
                m.put("kwh", Num.round(hourly[h], 3));
                m.put("peak", Settings.inRange(h, s.peakStart, s.peakEnd));
                m.put("offPeak", Settings.inRange(h, s.offPeakStart, s.offPeakEnd));
                hours.add(m);
            }

            // ---- weekday vs weekend (last 30 days)
            double wd = 0, we = 0;
            int wdn = 0, wen = 0;
            for (Map<String, Object> d : dailySeries(today.minusDays(30), today.minusDays(1))) {
                double k = ((Number) d.get("kwh")).doubleValue();
                if (Boolean.TRUE.equals(d.get("weekend"))) { we += k; wen++; } else { wd += k; wdn++; }
            }

            // ---- savings achieved from applied tips
            double savedKwh = 0, savedRs = 0;
            List<Map<String, Object>> applied = new ArrayList<>();
            for (TipRecord r : store.tips) {
                if (r.status != TipStatus.APPLIED) continue;
                savedKwh += r.kwhPerMonth;
                savedRs += r.rupeesPerMonth;
                Map<String, Object> m = r.toMap();
                m.put("byName", store.displayName(r.by));
                applied.add(m);
            }
            applied.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("at"))).reversed());

            // ---- this month vs the same days of last month
            int dayNo = today.getDayOfMonth() - 1;
            double thisPart = 0, lastPart = 0;
            if (dayNo >= 1) {
                String a1 = today.withDayOfMonth(1).toString(), a2 = today.minusDays(1).toString();
                LocalDate lm = today.minusMonths(1).withDayOfMonth(1);
                String b1 = lm.toString(), b2 = lm.plusDays(Math.min(dayNo, lm.lengthOfMonth()) - 1).toString();
                for (UsageLog u : store.usage) {
                    if (u.date.compareTo(a1) >= 0 && u.date.compareTo(a2) <= 0) thisPart += u.totalKwh();
                    else if (u.date.compareTo(b1) >= 0 && u.date.compareTo(b2) <= 0) lastPart += u.totalKwh();
                }
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("monthly", monthly);
            out.put("byType", types);
            out.put("byRoom", byRoom(in));
            out.put("topAppliances", topAppliances(in, rate));
            out.put("hourly", hours);
            out.put("split", split(hm));
            out.put("last30", Map.of("kwh", Num.round(hm.kwh, 1), "bill", bill30.toMap(),
                    "co2", Num.round(hm.kwh * s.co2PerKwh, 1), "trees", Math.round(hm.kwh * s.co2PerKwh * 12 / 21.0)));
            out.put("weekdayAvg", wdn == 0 ? 0 : Num.round(wd / wdn, 2));
            out.put("weekendAvg", wen == 0 ? 0 : Num.round(we / wen, 2));
            out.put("savedKwh", Num.round(savedKwh, 1));
            out.put("savedRupees", Math.round(savedRs));
            out.put("savedCo2", Num.round(savedKwh * s.co2PerKwh, 1));
            out.put("applied", applied);
            out.put("compare", Map.of("days", Math.max(0, dayNo), "thisMonth", Num.round(thisPart, 1), "lastMonth", Num.round(lastPart, 1)));
            Map<String, Object> gap = in.meterGap();
            out.put("meterGap", gap == null ? Map.of() : gap);
            out.put("ratePerUnit", Num.round(rate, 2));
            return out;
        }
    }

    // ================================================================== one appliance

    public Map<String, Object> appliance(String id) {
        synchronized (store.lock) {
            Appliance a = store.appliance(id);
            if (a == null) throw ServiceException.notFound("No such appliance.");
            Insights in = new Insights(store);
            ApplianceStats st = in.stats(a.id);
            HomeMonth hm = in.last30();
            Bill b = hm.bill(store.settings);
            double rate = b.units > 0 ? (b.total - b.fixedCharge) / b.units : 0;
            Map<String, Object> m = appliances.view(a);
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("kwh30", Num.round(st.kwh30, 1));
            stats.put("standbyKwh30", Num.round(st.standbyKwh30, 1));
            stats.put("cost30", Math.round(st.kwh30 * rate));
            stats.put("monthlyKwh", Num.round(in.monthlyKwh(a), 1));
            stats.put("monthlyCost", Math.round(in.monthlyKwh(a) * rate));
            stats.put("yearlyCost", Math.round(in.monthlyKwh(a) * rate * 12));
            stats.put("share", hm.kwh > 0 ? Num.round(st.kwh30 / hm.kwh * 100, 1) : 0);
            stats.put("avgHours", Num.round(st.avgHours(), 1));
            stats.put("last7Avg", Num.round(st.last7Avg, 2));
            stats.put("prev28Avg", Num.round(st.prev28Avg, 2));
            stats.put("spike", Insights.isSpike(st));
            stats.put("co2Month", Num.round(in.monthlyKwh(a) * store.settings.co2PerKwh, 1));
            stats.put("daysWithData", st.daysWithData);
            m.put("stats", stats);
            EnergyMath.Day d = EnergyMath.profileDay(a, store.settings);
            m.put("profile", Map.of("kwh", Num.round(d.kwh, 3), "standbyKwh", Num.round(d.standbyKwh, 3),
                    "peakHours", Num.round(d.peakHours, 2), "offPeakHours", Num.round(d.offPeakHours, 2)));
            // daily series for this appliance, last 30 days
            Map<String, Double> byDay = new LinkedHashMap<>();
            LocalDate today = store.today();
            for (LocalDate x = today.minusDays(30); x.isBefore(today); x = x.plusDays(1)) byDay.put(x.toString(), 0.0);
            List<UsageLog> recent = new ArrayList<>();
            for (UsageLog u : store.usage) {
                if (!u.applianceId.equals(a.id)) continue;
                byDay.computeIfPresent(u.date, (k, v) -> v + u.totalKwh());
                recent.add(u);
            }
            List<Map<String, Object>> daily = new ArrayList<>();
            for (Map.Entry<String, Double> e : byDay.entrySet()) {
                LocalDate x = LocalDate.parse(e.getKey());
                daily.add(Map.of("date", e.getKey(), "label", x.getDayOfMonth() + " " + x.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                        "kwh", Num.round(e.getValue(), 3)));
            }
            m.put("daily", daily);
            recent.sort(Comparator.comparing((UsageLog u) -> u.date).reversed());
            List<Map<String, Object>> logs = new ArrayList<>();
            for (UsageLog u : recent.subList(0, Math.min(14, recent.size()))) {
                Map<String, Object> lm = u.toMap();
                lm.put("totalKwh", Num.round(u.totalKwh(), 3));
                lm.put("cost", Num.round(u.totalKwh() * rate, 2));
                logs.add(lm);
            }
            m.put("logs", logs);
            Map<String, TipStatus> status = new HashMap<>();
            for (TipRecord r : store.tips) status.put(r.key, r.status);
            List<Map<String, Object>> tips = new ArrayList<>();
            for (Tip t : in.tipsFor(a)) {
                t.status = status.getOrDefault(t.key, TipStatus.NEW);
                tips.add(t.toMap());
            }
            m.put("tips", tips);
            m.put("events", appliances.events(a.id, 12));
            m.put("ratePerUnit", Num.round(rate, 2));
            return m;
        }
    }

    // ================================================================== savings planner ("what if")

    /**
     * "What if I change these settings?" Works out units, rupees and CO2 per month before and after.
     * body: { changes: [ {applianceId, hoursPerDay, setTemp, starRating, startHour, standbyOff, watts} ] }
     * Nothing is saved.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> plan(Map<String, Object> body) {
        synchronized (store.lock) {
            Settings s = store.settings;
            Insights in = new Insights(store);
            HomeMonth hm = in.last30();
            List<Map<String, Object>> rows = new ArrayList<>();
            double dK = 0, dP = 0, dO = 0;
            for (Object o : Json.list(body, "changes")) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> c = (Map<String, Object>) o;
                Appliance a = store.appliance(Json.str(c, "applianceId"));
                if (a == null) continue;
                EnergyMath.Day before = EnergyMath.profileDay(a, s);
                double hours = Num.clamp(Json.num(c, "hoursPerDay", a.hoursPerDay), 0, 24);
                int start = (int) Num.clamp(Json.integer(c, "startHour", a.usualStartHour), 0, 23);
                EnergyMath.Day after = EnergyMath.day(a, s, hours, start);
                double runFactor = 1;
                double newWatts = Json.num(c, "watts", a.watts);
                if (newWatts > 0 && a.watts > 0) runFactor *= newWatts / a.watts;
                int stars = Json.integer(c, "starRating", a.starRating);
                if (stars >= 1 && stars <= 5 && a.starRating >= 1 && stars != a.starRating) {
                    runFactor *= EnergyMath.starFactor(stars) / EnergyMath.starFactor(a.starRating);
                }
                int temp = Json.integer(c, "setTemp", a.setTemp);
                if (a.type == ApplianceType.AIR_CONDITIONER && a.setTemp > 0 && temp >= 16 && temp <= 30) {
                    runFactor *= Num.clamp(1 - EnergyMath.AC_SAVING_PER_DEGREE * (temp - a.setTemp), 0.4, 1.6);
                }
                after.kwh *= runFactor;
                if (Json.bool(c, "standbyOff", false)) after.standbyKwh = 0;
                double bK = before.total() * 30, aK = after.total() * 30;
                double bP = before.peakKwh() * 30, aP = after.peakKwh() * 30;
                double bO = before.offPeakKwh() * 30, aO = after.offPeakKwh() * 30;
                dK += bK - aK;
                dP += bP - aP;
                dO += bO - aO;
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("applianceId", a.id);
                r.put("name", a.name);
                r.put("beforeKwh", Num.round(bK, 1));
                r.put("afterKwh", Num.round(aK, 1));
                r.put("beforePeakKwh", Num.round(bP, 1));
                r.put("afterPeakKwh", Num.round(aP, 1));
                r.put("savedKwh", Num.round(bK - aK, 1));
                rows.add(r);
            }
            Bill before = hm.bill(s);
            Bill after = BillCalculator.bill(Math.max(0, hm.kwh - dK), Math.max(0, hm.peakKwh - dP), Math.max(0, hm.offPeakKwh - dO), s);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("rows", rows);
            out.put("before", before.toMap());
            out.put("after", after.toMap());
            out.put("savedKwh", Num.round(dK, 1));
            out.put("savedRupees", Math.round(before.total - after.total));
            out.put("savedRupeesYear", Math.round((before.total - after.total) * 12));
            out.put("savedCo2", Num.round(dK * s.co2PerKwh, 1));
            return out;
        }
    }
}
