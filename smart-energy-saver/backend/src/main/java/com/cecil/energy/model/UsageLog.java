package com.cecil.energy.model;

import com.cecil.energy.model.Enums.UsageSource;
import com.cecil.energy.util.Json;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Energy used by one appliance on one day.
 *
 * kwh        = energy while running   (watts x hours / 1000)
 * standbyKwh = energy while on standby (standby watts x the other hours / 1000)
 * peakHours  = how many of the running hours were inside the peak-rate time (costs more)
 * offPeakHours = how many of the running hours were inside the night off-peak time (costs less)
 */
public class UsageLog {
    public String id;
    public String applianceId;
    /** yyyy-MM-dd */
    public String date;
    public double hours;
    public double peakHours;
    public double offPeakHours;
    public double kwh;
    public double standbyKwh;
    public UsageSource source = UsageSource.MANUAL;
    public String note = "";
    public String by;

    public double totalKwh() {
        return kwh + standbyKwh;
    }

    /** Running energy that fell in peak hours. */
    public double peakKwh() {
        return hours <= 0 ? 0 : kwh * Math.min(1, peakHours / hours);
    }

    /** Running energy that fell in off-peak hours. */
    public double offPeakKwh() {
        return hours <= 0 ? 0 : kwh * Math.min(1, offPeakHours / hours);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("applianceId", applianceId);
        m.put("date", date);
        m.put("hours", hours);
        m.put("peakHours", peakHours);
        m.put("offPeakHours", offPeakHours);
        m.put("kwh", kwh);
        m.put("standbyKwh", standbyKwh);
        m.put("source", source);
        m.put("note", note);
        m.put("by", by);
        return m;
    }

    public static UsageLog fromMap(Map<String, Object> m) {
        UsageLog u = new UsageLog();
        u.id = Json.str(m, "id");
        u.applianceId = Json.str(m, "applianceId");
        u.date = Json.str(m, "date");
        u.hours = Json.num(m, "hours", 0);
        u.peakHours = Json.num(m, "peakHours", 0);
        u.offPeakHours = Json.num(m, "offPeakHours", 0);
        u.kwh = Json.num(m, "kwh", 0);
        u.standbyKwh = Json.num(m, "standbyKwh", 0);
        u.source = Enums.parse(UsageSource.class, Json.str(m, "source"), UsageSource.MANUAL);
        u.note = Json.str(m, "note", "");
        u.by = Json.str(m, "by");
        return u;
    }
}
