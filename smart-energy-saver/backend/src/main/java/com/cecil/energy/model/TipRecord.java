package com.cecil.energy.model;

import com.cecil.energy.model.Enums.TipStatus;
import com.cecil.energy.util.Json;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What someone did with one savings tip (applied it or dismissed it).
 * Tips themselves are worked out fresh every time; only this decision is saved.
 */
public class TipRecord {
    /** Same key as the tip, e.g. "AC_TEMP:ap12". */
    public String key;
    public String title;
    public TipStatus status = TipStatus.NEW;
    public String at;
    public String by;
    /** Saving per month that the tip promised when it was applied. */
    public double kwhPerMonth;
    public double rupeesPerMonth;

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("title", title);
        m.put("status", status);
        m.put("at", at);
        m.put("by", by);
        m.put("kwhPerMonth", kwhPerMonth);
        m.put("rupeesPerMonth", rupeesPerMonth);
        return m;
    }

    public static TipRecord fromMap(Map<String, Object> m) {
        TipRecord t = new TipRecord();
        t.key = Json.str(m, "key");
        t.title = Json.str(m, "title", "");
        t.status = Enums.parse(TipStatus.class, Json.str(m, "status"), TipStatus.NEW);
        t.at = Json.str(m, "at");
        t.by = Json.str(m, "by");
        t.kwhPerMonth = Json.num(m, "kwhPerMonth", 0);
        t.rupeesPerMonth = Json.num(m, "rupeesPerMonth", 0);
        return t;
    }
}
