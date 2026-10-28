package com.cecil.energy.insights;

import com.cecil.energy.model.Settings;
import com.cecil.energy.model.Settings.Slab;
import com.cecil.energy.util.Num;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out an electricity bill the same way the electricity board does:
 *
 * 1. Slab (telescopic) rates: the first 100 units at one price, the next 100 at a higher price, and so on.
 *    Example with 100 @ 5.50, 200 @ 6.50: 150 units = 100 x 5.50 + 50 x 6.50 = 550 + 325 = Rs 875.
 * 2. Time of day: units used in peak hours cost peak% more; units in off-peak night hours get a discount.
 * 3. Electricity duty (tax) = duty% of the energy charge.
 * 4. Plus a fixed charge every month.
 */
public final class BillCalculator {

    private BillCalculator() { }

    /** Energy charge from the slabs only. */
    public static double slabCharge(double units, List<Slab> slabs) {
        double left = Math.max(0, units);
        double previousLimit = 0;
        double charge = 0;
        for (Slab s : slabs) {
            if (left <= 0) break;
            double size = s.upTo <= 0 ? left : Math.max(0, s.upTo - previousLimit);
            double used = Math.min(left, size);
            charge += used * s.rate;
            left -= used;
            if (s.upTo > 0) previousLimit = s.upTo;
        }
        if (left > 0 && !slabs.isEmpty()) charge += left * slabs.get(slabs.size() - 1).rate; // no open-ended slab given
        return charge;
    }

    /** The full bill for a month. peakUnits and offPeakUnits are parts of totalUnits. */
    public static Bill bill(double totalUnits, double peakUnits, double offPeakUnits, Settings s) {
        Bill b = new Bill();
        b.units = Math.max(0, totalUnits);
        b.energyCharge = slabCharge(b.units, s.slabs);
        b.averageRate = b.units > 0 ? b.energyCharge / b.units : (s.slabs.isEmpty() ? 0 : s.slabs.get(0).rate);
        b.peakExtra = Math.max(0, peakUnits) * b.averageRate * s.peakSurchargePercent / 100.0;
        b.offPeakDiscount = Math.max(0, offPeakUnits) * b.averageRate * s.offPeakRebatePercent / 100.0;
        double beforeDuty = b.energyCharge + b.peakExtra - b.offPeakDiscount;
        b.duty = beforeDuty * s.dutyPercent / 100.0;
        b.fixedCharge = s.fixedCharge;
        b.total = b.fixedCharge + beforeDuty + b.duty;
        return b;
    }

    /** Lines that show how each slab was charged (for the Settings page example). */
    public static List<Map<String, Object>> slabLines(double units, List<Slab> slabs) {
        List<Map<String, Object>> out = new ArrayList<>();
        double left = Math.max(0, units);
        double previousLimit = 0;
        for (Slab s : slabs) {
            if (left <= 0) break;
            double size = s.upTo <= 0 ? left : Math.max(0, s.upTo - previousLimit);
            double used = Math.min(left, size);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("from", previousLimit);
            m.put("to", s.upTo <= 0 ? null : s.upTo);
            m.put("units", Num.round(used, 2));
            m.put("rate", s.rate);
            m.put("amount", Num.round(used * s.rate, 2));
            out.add(m);
            left -= used;
            if (s.upTo > 0) previousLimit = s.upTo;
        }
        return out;
    }

    /** A worked-out bill. */
    public static class Bill {
        public double units;
        public double energyCharge;
        public double averageRate;
        public double peakExtra;
        public double offPeakDiscount;
        public double duty;
        public double fixedCharge;
        public double total;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("units", Num.round(units, 1));
            m.put("energyCharge", Num.round(energyCharge, 2));
            m.put("averageRate", Num.round(averageRate, 2));
            m.put("peakExtra", Num.round(peakExtra, 2));
            m.put("offPeakDiscount", Num.round(offPeakDiscount, 2));
            m.put("duty", Num.round(duty, 2));
            m.put("fixedCharge", Num.round(fixedCharge, 2));
            m.put("total", Num.round(total, 2));
            return m;
        }
    }
}
