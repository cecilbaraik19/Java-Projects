package com.cecil.energy.service;

import com.cecil.energy.insights.BillCalculator;
import com.cecil.energy.model.Settings;
import com.cecil.energy.model.User;
import com.cecil.energy.store.DataStore;
import com.cecil.energy.util.Json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Electricity rates (tariff), peak hours, CO2 factor and monthly goals. */
public class SettingsService {

    private final DataStore store;

    public SettingsService(DataStore store) {
        this.store = store;
    }

    public Map<String, Object> get() {
        synchronized (store.lock) {
            return store.settings.toMap();
        }
    }

    /** Checks every value and saves. Only the energy manager or admin. */
    public Map<String, Object> update(User me, Map<String, Object> body) {
        AuthService.requireManager(me);
        Settings n = Settings.fromMap(body);
        n.homeName = AuthService.clean(n.homeName, 60);
        if (n.homeName.isEmpty()) throw ServiceException.bad("Enter a name for the home or office.");
        if (n.slabs.isEmpty() || n.slabs.size() > 8) throw ServiceException.bad("Add between 1 and 8 rate slabs.");
        double prev = 0;
        for (int i = 0; i < n.slabs.size(); i++) {
            Settings.Slab s = n.slabs.get(i);
            boolean last = i == n.slabs.size() - 1;
            if (s.rate < 0 || s.rate > 100) throw ServiceException.bad("Each rate must be between ₹0 and ₹100 per unit.");
            if (!last && s.upTo <= prev) throw ServiceException.bad("Slab limits must go up (for example 100, 200, 400).");
            if (last) s.upTo = 0; // the last slab covers everything above
            else prev = s.upTo;
        }
        check(n.fixedCharge, 0, 5000, "Fixed charge");
        check(n.dutyPercent, 0, 50, "Duty");
        check(n.peakSurchargePercent, 0, 100, "Peak extra");
        check(n.offPeakRebatePercent, 0, 100, "Off-peak discount");
        check(n.co2PerKwh, 0, 2, "CO2 per unit");
        check(n.monthlyTargetKwh, 0, 100000, "Monthly units target");
        check(n.monthlyBudget, 0, 10000000, "Monthly budget");
        for (int h : new int[] {n.peakStart, n.peakEnd, n.offPeakStart, n.offPeakEnd}) {
            if (h < 0 || h > 23) throw ServiceException.bad("Hours must be 0 to 23.");
        }
        for (int h = 0; h < 24; h++) {
            if (Settings.inRange(h, n.peakStart, n.peakEnd) && Settings.inRange(h, n.offPeakStart, n.offPeakEnd)) {
                throw ServiceException.bad("Peak hours and off-peak hours must not overlap.");
            }
        }
        synchronized (store.lock) {
            store.settings = n;
            store.addEvent("SETTINGS", null, me.displayName + " changed the rates and goals", me.username);
            store.saveSettings();
            store.saveEvents();
            return n.toMap();
        }
    }

    private static void check(double v, double min, double max, String name) {
        if (Double.isNaN(v) || v < min || v > max) throw ServiceException.bad(name + " must be between " + (long) min + " and " + (long) max + ".");
    }

    /** "What would N units cost?" with a slab-by-slab breakdown. */
    public Map<String, Object> billPreview(double units, double peakPercent) {
        synchronized (store.lock) {
            Settings s = store.settings;
            units = Math.max(0, Math.min(100000, units));
            double peak = units * Math.max(0, Math.min(100, peakPercent)) / 100.0;
            Map<String, Object> m = new LinkedHashMap<>(BillCalculator.bill(units, peak, 0, s).toMap());
            List<Map<String, Object>> lines = BillCalculator.slabLines(units, s.slabs);
            m.put("lines", lines);
            return m;
        }
    }
}
