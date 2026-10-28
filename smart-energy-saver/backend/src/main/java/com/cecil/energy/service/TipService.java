package com.cecil.energy.service;

import com.cecil.energy.insights.Insights;
import com.cecil.energy.insights.Insights.Tip;
import com.cecil.energy.model.Appliance;
import com.cecil.energy.model.Enums.TipStatus;
import com.cecil.energy.model.TipRecord;
import com.cecil.energy.model.User;
import com.cecil.energy.store.DataStore;
import com.cecil.energy.util.Num;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Savings tips: list them, apply them, dismiss them, bring them back.
 * Applying a tip records the promised saving (for the "Savings achieved" report) and,
 * where it makes sense, changes the appliance settings (e.g. AC to 24 °C).
 */
public class TipService {

    private final DataStore store;

    public TipService(DataStore store) {
        this.store = store;
    }

    public Map<String, Object> list() {
        synchronized (store.lock) {
            Insights in = new Insights(store);
            List<Map<String, Object>> open = new ArrayList<>();
            double kwh = 0, rs = 0, co2 = 0, freeRs = 0;
            for (Tip t : in.openTips()) {
                open.add(t.toMap());
                kwh += t.kwhPerMonth;
                rs += t.rupeesPerMonth;
                co2 += t.co2PerMonth;
                if (t.cost <= 0) freeRs += t.rupeesPerMonth;
            }
            List<Map<String, Object>> done = new ArrayList<>();
            double savedKwh = 0, savedRs = 0;
            for (TipRecord r : store.tips) {
                Map<String, Object> m = r.toMap();
                m.put("byName", store.displayName(r.by));
                done.add(m);
                if (r.status == TipStatus.APPLIED) {
                    savedKwh += r.kwhPerMonth;
                    savedRs += r.rupeesPerMonth;
                }
            }
            done.sort((a, b) -> String.valueOf(b.get("at")).compareTo(String.valueOf(a.get("at"))));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("open", open);
            m.put("done", done);
            m.put("potentialKwh", Num.round(kwh, 1));
            m.put("potentialRupees", Math.round(rs));
            m.put("potentialRupeesYear", Math.round(rs * 12));
            m.put("potentialCo2", Num.round(co2, 1));
            m.put("freeRupees", Math.round(freeRs));
            m.put("savedKwh", Num.round(savedKwh, 1));
            m.put("savedRupees", Math.round(savedRs));
            return m;
        }
    }

    /** Applies a tip. Only the energy manager or admin can do this (it can change appliance settings). */
    public Map<String, Object> apply(User me, String key) {
        AuthService.requireManager(me);
        synchronized (store.lock) {
            Tip t = find(key);
            if (t.status != TipStatus.NEW) throw ServiceException.conflict("This tip was already handled.");
            Appliance a = store.appliance(t.applianceId);
            String change = new Insights(store).applyTip(t, a);
            TipRecord r = record(t, TipStatus.APPLIED, me);
            store.addEvent("TIP", a.id, me.displayName + " applied tip \"" + t.title + "\" (" + change + ")", me.username);
            store.saveAppliances();
            store.saveTips();
            store.saveEvents();
            Map<String, Object> m = r.toMap();
            m.put("change", change);
            return m;
        }
    }

    public Map<String, Object> dismiss(User me, String key) {
        AuthService.requireManager(me);
        synchronized (store.lock) {
            Tip t = find(key);
            if (t.status != TipStatus.NEW) throw ServiceException.conflict("This tip was already handled.");
            TipRecord r = record(t, TipStatus.DISMISSED, me);
            store.saveTips();
            return r.toMap();
        }
    }

    /** Removes the decision, so the tip shows again if it still applies. Does not undo setting changes. */
    public void restore(User me, String key) {
        AuthService.requireManager(me);
        synchronized (store.lock) {
            if (!store.tips.removeIf(r -> r.key.equals(key))) throw ServiceException.notFound("No such tip.");
            store.saveTips();
        }
    }

    private Tip find(String key) {
        for (TipRecord r : store.tips) {
            if (r.key.equals(key)) throw ServiceException.conflict("This tip was already handled.");
        }
        for (Tip t : new Insights(store).allTips()) if (t.key.equals(key)) return t;
        throw ServiceException.notFound("This tip no longer applies. Refresh the page.");
    }

    private TipRecord record(Tip t, TipStatus status, User me) {
        TipRecord r = new TipRecord();
        r.key = t.key;
        r.title = t.title;
        r.status = status;
        r.at = store.nowText();
        r.by = me.username;
        r.kwhPerMonth = Num.round(t.kwhPerMonth, 1);
        r.rupeesPerMonth = Math.round(t.rupeesPerMonth);
        store.tips.removeIf(x -> x.key.equals(t.key));
        store.tips.add(r);
        return r;
    }
}
