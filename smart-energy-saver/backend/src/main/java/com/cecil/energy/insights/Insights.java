package com.cecil.energy.insights;

import com.cecil.energy.insights.BillCalculator.Bill;
import com.cecil.energy.insights.EnergyMath.Day;
import com.cecil.energy.model.Appliance;
import com.cecil.energy.model.Enums.ApplianceType;
import com.cecil.energy.model.Enums.Effort;
import com.cecil.energy.model.Enums.TipStatus;
import com.cecil.energy.model.MeterReading;
import com.cecil.energy.model.Settings;
import com.cecil.energy.model.TipRecord;
import com.cecil.energy.model.UsageLog;
import com.cecil.energy.store.DataStore;
import com.cecil.energy.util.Num;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The "smart" part of Smart Energy Saver. It looks at the appliances and their usage and works out:
 *
 *   - statistics for each appliance (average use, last week vs the weeks before)
 *   - savings tips, each with units, rupees and CO2 saved per month, cost and payback time
 *   - the home's energy score (0-100) with the reason for every point
 *   - alerts (over budget, unusual jump in use, appliance left on, peak hours ...)
 *
 * Everything here is plain rules and arithmetic, so every number can be explained in a viva.
 * The caller must hold store.lock while using this class.
 */
public class Insights {

    private final DataStore store;
    private final Settings s;
    private final LocalDate today;
    private final Map<String, ApplianceStats> stats = new HashMap<>();
    private HomeMonth last30;

    public Insights(DataStore store) {
        this.store = store;
        this.s = store.settings;
        this.today = store.today();
        computeStats();
    }

    // ================================================================== statistics

    /** Usage numbers for one appliance. "Last 30 days" means the 30 full days before today. */
    public static class ApplianceStats {
        public double kwh30;          // total kWh in last 30 days (running + standby)
        public double runKwh30;
        public double standbyKwh30;
        public double hours30;
        public double peakKwh30;
        public int daysWithData;
        public double last7Avg;       // average kWh per day in the last 7 days
        public double prev28Avg;      // average kWh per day in the 28 days before that
        public int prevDays;

        /** Average per day over the days that have data. */
        public double perDay() { return daysWithData == 0 ? 0 : kwh30 / daysWithData; }
        public double monthly() { return perDay() * 30; }
        public double avgHours() { return daysWithData == 0 ? 0 : hours30 / daysWithData; }
    }

    /** The home's use over the last 30 days, scaled to a 30-day month. */
    public static class HomeMonth {
        public double kwh;
        public double peakKwh;
        public double offPeakKwh;
        public double standbyKwh;
        public int days;

        public Bill bill(Settings s) { return BillCalculator.bill(kwh, peakKwh, offPeakKwh, s); }
    }

    private void computeStats() {
        LocalDate from30 = today.minusDays(30), to = today.minusDays(1);
        LocalDate from7 = today.minusDays(7), from35 = today.minusDays(35);
        String f30 = from30.toString(), f7 = from7.toString(), f35 = from35.toString(), t = to.toString();
        Map<String, Map<String, Boolean>> days = new HashMap<>();
        Map<String, Map<String, Boolean>> prevDays = new HashMap<>();
        Map<String, double[]> sums7 = new HashMap<>();
        HomeMonth hm = new HomeMonth();
        Map<String, Boolean> homeDays = new HashMap<>();
        for (Appliance a : store.appliances) stats.put(a.id, new ApplianceStats());
        for (UsageLog u : store.usage) {
            if (u.date.compareTo(f35) < 0 || u.date.compareTo(t) > 0) continue;
            ApplianceStats st = stats.get(u.applianceId);
            if (st == null) continue;
            double[] w = sums7.computeIfAbsent(u.applianceId, k -> new double[2]);
            if (u.date.compareTo(f7) >= 0) {
                w[0] += u.totalKwh();
            } else {
                w[1] += u.totalKwh();
                prevDays.computeIfAbsent(u.applianceId, k -> new HashMap<>()).put(u.date, true);
            }
            if (u.date.compareTo(f30) >= 0) {
                st.kwh30 += u.totalKwh();
                st.runKwh30 += u.kwh;
                st.standbyKwh30 += u.standbyKwh;
                st.hours30 += u.hours;
                st.peakKwh30 += u.peakKwh();
                days.computeIfAbsent(u.applianceId, k -> new HashMap<>()).put(u.date, true);
                hm.kwh += u.totalKwh();
                hm.peakKwh += u.peakKwh();
                hm.offPeakKwh += u.offPeakKwh();
                hm.standbyKwh += u.standbyKwh;
                homeDays.put(u.date, true);
            }
        }
        for (Appliance a : store.appliances) {
            ApplianceStats st = stats.get(a.id);
            st.daysWithData = days.getOrDefault(a.id, Map.of()).size();
            st.prevDays = prevDays.getOrDefault(a.id, Map.of()).size();
            double[] w = sums7.getOrDefault(a.id, new double[2]);
            st.last7Avg = w[0] / 7.0;
            st.prevDays = Math.min(28, st.prevDays);
            st.prev28Avg = st.prevDays == 0 ? 0 : w[1] / st.prevDays;
        }
        hm.days = homeDays.size();
        if (hm.days > 0 && hm.days < 30) {
            double k = 30.0 / hm.days; // scale a short history up to a month
            hm.kwh *= k; hm.peakKwh *= k; hm.offPeakKwh *= k; hm.standbyKwh *= k;
        }
        if (hm.days == 0) {
            // no history yet: use the appliances' normal daily profile
            for (Appliance a : store.appliances) {
                Day d = EnergyMath.profileDay(a, s);
                hm.kwh += d.total() * 30;
                hm.peakKwh += d.peakKwh() * 30;
                hm.offPeakKwh += d.offPeakKwh() * 30;
                hm.standbyKwh += d.standbyKwh * 30;
            }
        }
        last30 = hm;
    }

    public ApplianceStats stats(String applianceId) {
        return stats.getOrDefault(applianceId, new ApplianceStats());
    }

    public HomeMonth last30() { return last30; }

    /** Monthly kWh for an appliance: real data when there is some, otherwise its profile. */
    public double monthlyKwh(Appliance a) {
        ApplianceStats st = stats(a.id);
        if (st.daysWithData >= 3) return st.monthly();
        return EnergyMath.profileDay(a, s).total() * 30;
    }

    /**
     * Real use / profile use (running part). Tips are worked out from the profile and then scaled by this,
     * so an appliance that is really used less than its profile says gets a smaller saving.
     */
    private double usageRatio(Appliance a) {
        ApplianceStats st = stats(a.id);
        double profile = EnergyMath.profileDay(a, s).kwh;
        if (st.daysWithData < 3 || profile <= 0) return 1;
        return Num.clamp((st.runKwh30 / st.daysWithData) / profile, 0.3, 2.0);
    }

    /** Rupees saved per month if the home's monthly use drops by these amounts (uses the real slab bill). */
    public double rupeesSaved(double kwhLess, double peakLess, double offPeakLess) {
        HomeMonth b = last30;
        Bill before = b.bill(s);
        Bill after = BillCalculator.bill(Math.max(0, b.kwh - kwhLess), Math.max(0, b.peakKwh - peakLess),
                Math.max(0, b.offPeakKwh - offPeakLess), s);
        return before.total - after.total;
    }

    // ================================================================== savings tips

    /** One savings tip. Worked out fresh every time from the current data. */
    public static class Tip {
        public String key;
        public String rule;
        public String applianceId;
        public String applianceName;
        public String roomName;
        public String title;
        public String why;
        public String action;
        public Effort effort;
        public double kwhPerMonth;
        public double rupeesPerMonth;
        public double co2PerMonth;
        public double cost;
        /** Months until the saving pays back the cost (0 = free). null when it never pays back. */
        public Double paybackMonths;
        /** True when "Apply" changes the appliance settings in the app (not just a reminder). */
        public boolean changesSettings;
        public TipStatus status = TipStatus.NEW;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("rule", rule);
            m.put("applianceId", applianceId);
            m.put("applianceName", applianceName);
            m.put("roomName", roomName);
            m.put("title", title);
            m.put("why", why);
            m.put("action", action);
            m.put("effort", effort);
            m.put("effortLabel", effort.label);
            m.put("kwhPerMonth", Num.round(kwhPerMonth, 1));
            m.put("rupeesPerMonth", Math.round(rupeesPerMonth));
            m.put("co2PerMonth", Num.round(co2PerMonth, 1));
            m.put("cost", Math.round(cost));
            m.put("paybackMonths", paybackMonths == null ? null : Num.round(paybackMonths, 1));
            m.put("changesSettings", changesSettings);
            m.put("status", status);
            return m;
        }
    }

    /** All tips that are still open (not applied or dismissed), biggest rupee saving first. */
    public List<Tip> openTips() {
        List<Tip> out = new ArrayList<>();
        for (Tip t : allTips()) if (t.status == TipStatus.NEW) out.add(t);
        return out;
    }

    /** All tips, with their status from the saved decisions. */
    public List<Tip> allTips() {
        Map<String, TipRecord> records = new HashMap<>();
        for (TipRecord r : store.tips) records.put(r.key, r);
        List<Tip> out = new ArrayList<>();
        for (Appliance a : store.appliances) {
            for (Tip t : tipsFor(a)) {
                TipRecord r = records.get(t.key);
                if (r != null) t.status = r.status;
                out.add(t);
            }
        }
        out.sort(Comparator.comparingDouble((Tip t) -> -t.rupeesPerMonth).thenComparing(t -> t.key));
        return out;
    }

    /** Works out the tips for one appliance. */
    public List<Tip> tipsFor(Appliance a) {
        List<Tip> out = new ArrayList<>();
        Day p = EnergyMath.profileDay(a, s);
        double ratio = usageRatio(a);
        double runMonth = p.kwh * 30 * ratio;
        ApplianceStats st = stats(a.id);
        double standbyMonth = st.daysWithData >= 3 ? st.standbyKwh30 / st.daysWithData * 30 : p.standbyKwh * 30;

        // 1. Air conditioner set too cold
        if (a.type == ApplianceType.AIR_CONDITIONER && a.setTemp > 0 && a.setTemp < EnergyMath.AC_BEST_TEMP && runMonth > 0) {
            int deg = EnergyMath.AC_BEST_TEMP - a.setTemp;
            double kwh = runMonth * Math.min(0.5, EnergyMath.AC_SAVING_PER_DEGREE * deg);
            Tip t = tip(a, "AC_TEMP", "Set " + a.name + " to 24 °C instead of " + a.setTemp + " °C", Effort.EASY, 0, kwh,
                    peakShare(p) * kwh, offPeakShare(p) * kwh);
            t.why = "Every 1 °C warmer saves about 6% of the AC's power. 24 °C is the BEE recommended setting "
                    + "and still feels cool with a fan on.";
            t.action = "Change the AC remote to 24 °C (use a ceiling fan along with it).";
            t.changesSettings = true;
            out.add(t);
        }
        // 2. Standby (vampire) power
        if (a.standbyWatts >= 2 && standbyMonth >= 2.5) {
            double kwh = standbyMonth * 0.9;
            Tip t = tip(a, "STANDBY", "Cut standby power of " + a.name, Effort.LOW_COST, 700, kwh, 0, 0);
            t.why = a.name + " uses " + fmt(a.standbyWatts) + " W even when it looks off. That is about "
                    + fmt(standbyMonth) + " units a month for nothing.";
            t.action = "Switch it off at the wall socket, or put it on a smart plug (about ₹700) that cuts power at night.";
            t.changesSettings = true;
            out.add(t);
        }
        // 3. Old appliance with a low star rating
        if (a.starRating >= 1 && a.starRating <= 3 && a.type.upgradeCost > 0 && a.type != ApplianceType.LIGHT
                && a.type != ApplianceType.FAN && runMonth >= 15) {
            double newRun = runMonth * EnergyMath.starFactor(5) / EnergyMath.starFactor(a.starRating);
            double kwh = runMonth - newRun;
            double yearlyRs = rupeesSaved(kwh, 0, 0) * 12;
            // only when it pays back within 20 years (otherwise it is not a sensible tip)
            if (kwh >= 3 && yearlyRs > 0 && a.type.upgradeCost / yearlyRs <= 20) {
                Tip t = tip(a, "UPGRADE", "Replace " + a.starRating + "-star " + a.name + " with a 5-star model",
                        Effort.INVESTMENT, a.type.upgradeCost, kwh, peakShare(p) * kwh, offPeakShare(p) * kwh);
                t.why = "A 5-star model uses about " + Math.round((1 - 1 / EnergyMath.starFactor(a.starRating)) * 100)
                        + "% less power than a " + a.starRating + "-star one for the same work.";
                t.action = "When you replace it, buy a BEE 5-star model. Mark this done after buying.";
                t.changesSettings = true;
                out.add(t);
            }
        }
        // 4. Old bulbs / tube lights -> LED
        if (a.type == ApplianceType.LIGHT && a.wattsPerUnit() > 12 && runMonth > 0) {
            double newPerUnit = a.wattsPerUnit() > 30 ? 20 : 9;
            double newWatts = newPerUnit * a.quantity;
            double kwh = runMonth * (1 - newWatts / a.watts);
            double cost = a.quantity * (newPerUnit == 20 ? 350 : 120);
            Tip t = tip(a, "LED", "Change " + a.name + " to LED", Effort.LOW_COST, cost, kwh,
                    peakShare(p) * kwh, offPeakShare(p) * kwh);
            t.why = "Each light uses " + fmt(a.wattsPerUnit()) + " W. An LED gives the same light with about "
                    + fmt(newPerUnit) + " W and lasts much longer.";
            t.action = "Replace " + a.quantity + " light(s) with " + fmt(newPerUnit) + " W LED (about ₹" + Math.round(cost) + ").";
            t.changesSettings = true;
            out.add(t);
        }
        // 5. Old ceiling fans -> BLDC fans
        if (a.type == ApplianceType.FAN && a.wattsPerUnit() > 50 && runMonth > 0) {
            double newWatts = 28.0 * a.quantity;
            double kwh = runMonth * (1 - newWatts / a.watts);
            double cost = 3200.0 * a.quantity;
            Tip t = tip(a, "BLDC", "Change " + a.name + " to " + (a.quantity > 1 ? "BLDC fans" : "a BLDC fan"), Effort.INVESTMENT, cost, kwh,
                    peakShare(p) * kwh, offPeakShare(p) * kwh);
            t.why = "An ordinary fan uses about " + fmt(a.wattsPerUnit()) + " W. A BLDC (5-star) fan gives the same air with about 28 W.";
            t.action = "Replace with BLDC fan(s), about ₹3,200 each.";
            t.changesSettings = true;
            out.add(t);
        }
        // 6. Shiftable job running in peak hours
        if (a.type.shiftable && p.peakHours > 0.01 && (s.peakSurchargePercent > 0 || s.offPeakRebatePercent > 0) && a.hoursPerDay > 0) {
            int newStart = suggestedStart(a);
            Day q = EnergyMath.day(a, s, a.hoursPerDay, newStart);
            double peakLess = (p.peakKwh() - q.peakKwh()) * 30 * ratio;
            double offMore = (q.offPeakKwh() - p.offPeakKwh()) * 30 * ratio;
            if (peakLess > 0.1 || offMore > 0.1) {
                Tip t = tip(a, "PEAK_SHIFT", "Run " + a.name + " at " + hourText(newStart) + " instead of " + hourText(a.usualStartHour),
                        Effort.EASY, 0, 0, peakLess, -offMore);
                t.why = "Between " + hourText(s.peakStart) + " and " + hourText(s.peakEnd) + " each unit costs "
                        + fmt(s.peakSurchargePercent) + "% more. At night (" + hourText(s.offPeakStart) + "-" + hourText(s.offPeakEnd)
                        + ") it costs " + fmt(s.offPeakRebatePercent) + "% less. Same units, smaller bill.";
                t.action = "Use the appliance's delay timer, or start it at " + hourText(newStart) + ".";
                t.changesSettings = true;
                out.add(t);
            }
        }
        // 7. Water heater left on too long
        double avgHours = st.daysWithData >= 3 ? st.avgHours() : a.hoursPerDay;
        if (a.type == ApplianceType.WATER_HEATER && avgHours > 0.75) {
            double kwh = (avgHours - 0.5) * a.watts / 1000.0 * 30;
            Tip t = tip(a, "GEYSER_TIMER", "Set a 30-minute auto-off for " + a.name, Effort.EASY, 0, kwh,
                    peakShare(p) * kwh, offPeakShare(p) * kwh);
            t.why = "It runs about " + fmt(avgHours) + " hours a day. A full tank heats in about 20-30 minutes; "
                    + "after that it only keeps the water hot.";
            t.action = "Turn on auto-off after 30 minutes (the app will switch it off for you).";
            t.changesSettings = true;
            out.add(t);
        }
        // 8. Sudden jump in use (compared with the weeks before)
        if (isSpike(st)) {
            double kwh = (st.last7Avg - st.prev28Avg) * 30;
            Tip t = tip(a, "SPIKE", a.name + " is using " + Math.round((st.last7Avg / st.prev28Avg - 1) * 100)
                    + "% more than usual", Effort.EASY, 0, kwh, peakShare(p) * kwh, offPeakShare(p) * kwh);
            t.why = "Last 7 days: " + fmt(st.last7Avg) + " units a day. The 4 weeks before: " + fmt(st.prev28Avg) + " units a day.";
            t.action = a.type == ApplianceType.REFRIGERATOR
                    ? "Check the door rubber seal, defrost the freezer, keep 10 cm gap behind it and do not put hot food inside."
                    : "Check if it is being left on, or if it needs a service.";
            out.add(t);
        }
        return out;
    }

    /** A jump of more than 30% (and more than 0.25 units a day) over the 4 weeks before. */
    public static boolean isSpike(ApplianceStats st) {
        return st.prevDays >= 14 && st.prev28Avg > 0 && st.last7Avg > st.prev28Avg * 1.3
                && st.last7Avg - st.prev28Avg > 0.25;
    }

    /** A start hour that keeps the job inside off-peak time and ends when off-peak ends (e.g. 5:00 for a 1-hour job). */
    public int suggestedStart(Appliance a) {
        int len = (int) Math.ceil(Math.max(0.5, a.hoursPerDay));
        int offLen = ((s.offPeakEnd - s.offPeakStart) + 24) % 24;
        if (offLen == 0 || len > offLen) return s.offPeakStart;
        return ((s.offPeakEnd - len) % 24 + 24) % 24;
    }

    private double peakShare(Day d) { return d.kwh <= 0 ? 0 : d.peakKwh() / d.kwh; }

    private double offPeakShare(Day d) { return d.kwh <= 0 ? 0 : d.offPeakKwh() / d.kwh; }

    private Tip tip(Appliance a, String rule, String title, Effort effort, double cost, double kwh, double peakLess, double offPeakLess) {
        Tip t = new Tip();
        t.key = rule + ":" + a.id;
        t.rule = rule;
        t.applianceId = a.id;
        t.applianceName = a.name;
        t.roomName = store.roomName(a.roomId);
        t.title = title;
        t.effort = effort;
        t.cost = cost;
        t.kwhPerMonth = Math.max(0, kwh);
        t.rupeesPerMonth = Math.max(0, rupeesSaved(t.kwhPerMonth, peakLess, offPeakLess));
        t.co2PerMonth = t.kwhPerMonth * s.co2PerKwh;
        if (cost <= 0) t.paybackMonths = 0.0;
        else t.paybackMonths = t.rupeesPerMonth > 0.5 ? cost / t.rupeesPerMonth : null;
        return t;
    }

    /** Makes the change a tip suggests to the appliance settings. Returns a short description of the change. */
    public String applyTip(Tip t, Appliance a) {
        switch (t.rule) {
            case "AC_TEMP" -> { a.setTemp = EnergyMath.AC_BEST_TEMP; return "temperature set to 24 °C"; }
            case "STANDBY" -> { a.standbyWatts = 0; return "standby power cut (switched off at the wall / smart plug)"; }
            case "UPGRADE" -> {
                a.watts = Num.round(a.watts * EnergyMath.starFactor(5) / EnergyMath.starFactor(a.starRating), 0);
                a.starRating = 5;
                return "replaced with a 5-star model";
            }
            case "LED" -> {
                double per = a.wattsPerUnit() > 30 ? 20 : 9;
                a.watts = per * a.quantity;
                return "changed to " + fmt(per) + " W LED";
            }
            case "BLDC" -> { a.watts = 28.0 * a.quantity; return "changed to BLDC fan"; }
            case "PEAK_SHIFT" -> {
                a.usualStartHour = suggestedStart(a);
                return "start time moved to " + hourText(a.usualStartHour);
            }
            case "GEYSER_TIMER" -> {
                a.autoOffMinutes = 30;
                a.hoursPerDay = Math.min(a.hoursPerDay, 0.5);
                return "auto-off after 30 minutes";
            }
            default -> { return "marked as done"; }
        }
    }

    // ================================================================== energy score

    /** The home's energy score from 0 to 100, with the points for each part. */
    public Map<String, Object> score(double forecastKwh) {
        List<Map<String, Object>> factors = new ArrayList<>();
        int total = 0;
        HomeMonth hm = last30;

        // 1. Monthly target (30 points)
        double target = Math.max(1, s.monthlyTargetKwh);
        double r = forecastKwh / target;
        int p1 = r <= 0.9 ? 30 : r <= 1.0 ? 24 : r <= 1.1 ? 15 : r <= 1.25 ? 8 : 0;
        total += p1;
        factors.add(factor("Monthly target", p1, 30, "This month will be about " + fmt(forecastKwh) + " units; target is "
                + fmt(target) + " (" + Math.round(r * 100) + "%)."));

        // 2. Standby waste (15)
        double sb = hm.kwh > 0 ? hm.standbyKwh / hm.kwh * 100 : 0;
        int p2 = sb < 3 ? 15 : sb < 6 ? 10 : sb < 10 ? 5 : 0;
        total += p2;
        factors.add(factor("Standby waste", p2, 15, fmt(sb) + "% of the units are used by appliances on standby."));

        // 3. Peak-hour use (15)
        double pk = hm.kwh > 0 ? hm.peakKwh / hm.kwh * 100 : 0;
        int p3 = pk < 20 ? 15 : pk < 30 ? 10 : pk < 40 ? 5 : 0;
        total += p3;
        factors.add(factor("Peak-hour use", p3, 15, fmt(pk) + "% of the units are used in costly peak hours ("
                + hourText(s.peakStart) + "-" + hourText(s.peakEnd) + ")."));

        // 4. Efficient appliances (15): star rating weighted by how much each appliance uses
        double w = 0, sum = 0;
        for (Appliance a : store.appliances) {
            if (a.starRating <= 0) continue;
            double m = monthlyKwh(a);
            w += m;
            sum += m * a.starRating;
        }
        double avgStar = w > 0 ? sum / w : 5;
        int p4 = avgStar >= 4.5 ? 15 : avgStar >= 3.5 ? 10 : avgStar >= 2.5 ? 5 : 0;
        total += p4;
        factors.add(factor("Efficient appliances", p4, 15, "Average star rating (by units used) is " + fmt(avgStar) + " of 5."));

        // 5. AC settings (10)
        int acs = 0, good = 0;
        for (Appliance a : store.appliances) {
            if (a.type == ApplianceType.AIR_CONDITIONER) {
                acs++;
                if (a.setTemp == 0 || a.setTemp >= EnergyMath.AC_BEST_TEMP) good++;
            }
        }
        int p5 = acs == 0 || good == acs ? 10 : good > 0 ? 5 : 0;
        total += p5;
        factors.add(factor("AC temperature", p5, 10, acs == 0 ? "No air conditioner." : good + " of " + acs + " ACs are set to 24 °C or warmer."));

        // 6. Trend (15): last 30 days vs the 30 days before
        double prev = 0;
        String a60 = today.minusDays(60).toString(), a31 = today.minusDays(31).toString();
        double cur = 0;
        String a30 = today.minusDays(30).toString(), y = today.minusDays(1).toString();
        for (UsageLog u : store.usage) {
            if (u.date.compareTo(a60) >= 0 && u.date.compareTo(a31) <= 0) prev += u.totalKwh();
            else if (u.date.compareTo(a30) >= 0 && u.date.compareTo(y) <= 0) cur += u.totalKwh();
        }
        int p6;
        String trendText;
        if (prev <= 0) {
            p6 = 8;
            trendText = "Not enough history yet to compare with last month.";
        } else {
            double change = (cur - prev) / prev * 100;
            p6 = change <= -5 ? 15 : change <= 5 ? 8 : 0;
            trendText = "Last 30 days used " + fmt(Math.abs(change)) + "% " + (change <= 0 ? "less" : "more") + " than the 30 days before.";
        }
        total += p6;
        factors.add(factor("Trend", p6, 15, trendText));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("score", total);
        m.put("label", total >= 80 ? "EXCELLENT" : total >= 60 ? "GOOD" : total >= 40 ? "FAIR" : "POOR");
        m.put("factors", factors);
        return m;
    }

    private static Map<String, Object> factor(String name, int points, int max, String why) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("points", points);
        m.put("max", max);
        m.put("why", why);
        return m;
    }

    // ================================================================== alerts

    /** Things that need attention now. level: bad, warn or info. */
    public List<Map<String, Object>> alerts(double forecastKwh, double forecastBill) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (s.monthlyBudget > 0 && forecastBill > s.monthlyBudget) {
            out.add(alert("bad", "BUDGET", null, "Bill may cross your budget",
                    "Expected bill ₹" + Math.round(forecastBill) + " vs budget ₹" + Math.round(s.monthlyBudget) + "."));
        } else if (s.monthlyBudget > 0 && forecastBill > s.monthlyBudget * 0.9) {
            out.add(alert("warn", "BUDGET", null, "Close to your monthly budget",
                    "Expected bill ₹" + Math.round(forecastBill) + " of ₹" + Math.round(s.monthlyBudget) + "."));
        }
        if (s.monthlyTargetKwh > 0 && forecastKwh > s.monthlyTargetKwh) {
            out.add(alert("warn", "TARGET", null, "Above the monthly units target",
                    "This month may use " + fmt(forecastKwh) + " units; target is " + fmt(s.monthlyTargetKwh) + "."));
        }
        LocalDateTime now = store.now();
        boolean peakNow = Settings.inRange(now.getHour(), s.peakStart, s.peakEnd);
        for (Appliance a : store.appliances) {
            ApplianceStats st = stats(a.id);
            if (isSpike(st)) {
                out.add(alert("warn", "SPIKE", a.id, a.name + " is using more than usual",
                        fmt(st.last7Avg) + " units/day this week vs " + fmt(st.prev28Avg) + " before."));
            }
            if (a.on && a.onSince != null && a.hoursPerDay < 20) {
                LocalDateTime since = LocalDateTime.parse(a.onSince, DataStore.TIME);
                double h = Duration.between(since, now).toMinutes() / 60.0;
                double limit = Math.max(3, a.hoursPerDay * 2);
                if (h > limit) {
                    out.add(alert("bad", "LEFT_ON", a.id, a.name + " has been on for " + fmt(h) + " hours",
                            "Normally it runs about " + fmt(a.hoursPerDay) + " hours a day. Was it left on?"));
                }
            }
            if (peakNow && a.on && a.type.shiftable) {
                out.add(alert("warn", "PEAK_NOW", a.id, a.name + " is running in peak hours",
                        "Units cost " + fmt(s.peakSurchargePercent) + "% more until " + hourText(s.peakEnd) + "."));
            }
        }
        // meter check: tracked appliances vs the main meter over about the last 30 days
        Map<String, Object> gap = meterGap();
        if (gap != null) {
            double pct = ((Number) gap.get("gapPercent")).doubleValue();
            if (pct > 15) {
                out.add(alert("warn", "METER_GAP", null, "Some use is not tracked",
                        "The main meter shows " + fmt(pct) + "% more units than your appliances. Add the missing appliances."));
            }
        }
        MeterReading latest = null;
        for (MeterReading m : store.meter) if (latest == null || m.date.compareTo(latest.date) > 0) latest = m;
        if (latest == null || LocalDate.parse(latest.date).isBefore(today.minusDays(14))) {
            out.add(alert("info", "READ_METER", null, "Time to read the meter",
                    "Add a meter reading so the app can check that all use is tracked."));
        }
        return out;
    }

    /** Compares the main meter with the tracked appliances between the two readings ~30 days apart. */
    public Map<String, Object> meterGap() {
        List<MeterReading> list = new ArrayList<>(store.meter);
        list.sort(Comparator.comparing(m -> m.date));
        if (list.size() < 2) return null;
        MeterReading last = list.get(list.size() - 1);
        MeterReading first = null;
        LocalDate lastDate = LocalDate.parse(last.date);
        for (MeterReading m : list) {
            if (!LocalDate.parse(m.date).isAfter(lastDate.minusDays(28))) first = m;
        }
        if (first == null) first = list.get(0);
        if (first == last) return null;
        double meterUnits = last.reading - first.reading;
        double tracked = 0;
        // a reading is taken in the morning, so it counts the days from "first" up to the day before "last"
        String f = first.date, t = LocalDate.parse(last.date).minusDays(1).toString();
        for (UsageLog u : store.usage) if (u.date.compareTo(f) >= 0 && u.date.compareTo(t) <= 0) tracked += u.totalKwh();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", first.date);
        m.put("to", last.date);
        m.put("meterUnits", Num.round(meterUnits, 1));
        m.put("trackedUnits", Num.round(tracked, 1));
        m.put("untracked", Num.round(meterUnits - tracked, 1));
        m.put("gapPercent", meterUnits > 0 ? Num.round((meterUnits - tracked) / meterUnits * 100, 1) : 0.0);
        return m;
    }

    private static Map<String, Object> alert(String level, String kind, String applianceId, String title, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", level);
        m.put("kind", kind);
        m.put("applianceId", applianceId);
        m.put("title", title);
        m.put("text", text);
        return m;
    }

    // ================================================================== helpers

    public static String hourText(int h) {
        return String.format("%02d:00", ((h % 24) + 24) % 24);
    }

    /** 12.345 -> "12.3", 12.0 -> "12" */
    public static String fmt(double v) {
        double r = Num.round(v, 1);
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }
}
