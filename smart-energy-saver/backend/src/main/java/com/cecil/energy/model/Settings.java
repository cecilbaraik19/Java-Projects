package com.cecil.energy.model;

import com.cecil.energy.util.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Electricity rates (tariff), peak hours and goals. One copy for the whole home / office.
 *
 * The default rates are SAMPLE values. Change them in the Settings page to match your own electricity bill.
 */
public class Settings {

    /** One price band: units up to "upTo" cost "rate" rupees each. upTo = 0 means "everything above". */
    public static class Slab {
        public double upTo;
        public double rate;

        public Slab(double upTo, double rate) {
            this.upTo = upTo;
            this.rate = rate;
        }
    }

    public String homeName = "My Home";
    public List<Slab> slabs = new ArrayList<>(List.of(
            new Slab(100, 5.50), new Slab(200, 6.50), new Slab(400, 7.50), new Slab(0, 8.50)));
    /** Fixed charge per month in rupees, paid even if no power is used. */
    public double fixedCharge = 100;
    /** Electricity duty / tax, % of the energy charge. */
    public double dutyPercent = 5;

    /** Peak hours (evening): units used here cost extra. Hours 0-23, end not included. */
    public int peakStart = 18;
    public int peakEnd = 22;
    public double peakSurchargePercent = 20;
    /** Off-peak hours (night): units used here get a discount. */
    public int offPeakStart = 22;
    public int offPeakEnd = 6;
    public double offPeakRebatePercent = 10;

    /** kg of CO2 for each kWh from the grid (India average is about 0.72). */
    public double co2PerKwh = 0.72;

    /** Monthly goals. */
    public double monthlyTargetKwh = 300;
    public double monthlyBudget = 2500;

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("homeName", homeName);
        List<Map<String, Object>> s = new ArrayList<>();
        for (Slab x : slabs) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("upTo", x.upTo);
            sm.put("rate", x.rate);
            s.add(sm);
        }
        m.put("slabs", s);
        m.put("fixedCharge", fixedCharge);
        m.put("dutyPercent", dutyPercent);
        m.put("peakStart", peakStart);
        m.put("peakEnd", peakEnd);
        m.put("peakSurchargePercent", peakSurchargePercent);
        m.put("offPeakStart", offPeakStart);
        m.put("offPeakEnd", offPeakEnd);
        m.put("offPeakRebatePercent", offPeakRebatePercent);
        m.put("co2PerKwh", co2PerKwh);
        m.put("monthlyTargetKwh", monthlyTargetKwh);
        m.put("monthlyBudget", monthlyBudget);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Settings fromMap(Map<String, Object> m) {
        Settings s = new Settings();
        s.homeName = Json.str(m, "homeName", s.homeName);
        List<Object> sl = Json.list(m, "slabs");
        if (!sl.isEmpty()) {
            s.slabs = new ArrayList<>();
            for (Object o : sl) {
                if (o instanceof Map) {
                    Map<String, Object> x = (Map<String, Object>) o;
                    s.slabs.add(new Slab(Json.num(x, "upTo", 0), Json.num(x, "rate", 0)));
                }
            }
        }
        s.fixedCharge = Json.num(m, "fixedCharge", s.fixedCharge);
        s.dutyPercent = Json.num(m, "dutyPercent", s.dutyPercent);
        s.peakStart = Json.integer(m, "peakStart", s.peakStart);
        s.peakEnd = Json.integer(m, "peakEnd", s.peakEnd);
        s.peakSurchargePercent = Json.num(m, "peakSurchargePercent", s.peakSurchargePercent);
        s.offPeakStart = Json.integer(m, "offPeakStart", s.offPeakStart);
        s.offPeakEnd = Json.integer(m, "offPeakEnd", s.offPeakEnd);
        s.offPeakRebatePercent = Json.num(m, "offPeakRebatePercent", s.offPeakRebatePercent);
        s.co2PerKwh = Json.num(m, "co2PerKwh", s.co2PerKwh);
        s.monthlyTargetKwh = Json.num(m, "monthlyTargetKwh", s.monthlyTargetKwh);
        s.monthlyBudget = Json.num(m, "monthlyBudget", s.monthlyBudget);
        return s;
    }

    /** True when hour h (0-23) is inside the range start..end. Handles ranges that cross midnight (22 -> 6). */
    public static boolean inRange(int h, int start, int end) {
        if (start == end) return false;
        if (start < end) return h >= start && h < end;
        return h >= start || h < end;
    }
}
