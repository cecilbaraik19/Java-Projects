package com.cecil.energy.model;

import com.cecil.energy.model.Enums.ApplianceType;
import com.cecil.energy.util.Json;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One appliance (or a group of the same kind, like "4 LED bulbs").
 *
 * watts        = power when running, for all units together (4 bulbs x 9 W = 36 W).
 * standbyWatts = power used while "off" but still plugged in (TV red light, set-top box).
 * hoursPerDay  = normal running time per day. Used for estimates and for the savings planner.
 * usualStartHour = the hour it normally starts (0-23). Tells us how much of the use falls in peak hours.
 */
public class Appliance {
    public String id;
    public String name;
    public ApplianceType type = ApplianceType.OTHER;
    public String roomId;
    public double watts;
    public double standbyWatts;
    public int quantity = 1;
    /** BEE star rating 1-5, or 0 when the appliance has no rating. */
    public int starRating;
    public double hoursPerDay;
    public int usualStartHour = 19;
    /** Air conditioners only: temperature setting in deg C (0 = not used). */
    public int setTemp;
    /** Switch off by itself after this many minutes (0 = never). Like a smart plug timer. */
    public int autoOffMinutes;
    public boolean on;
    /** When it was switched on (ISO date-time), or null. */
    public String onSince;
    public String onBy;
    public String notes = "";
    public String createdAt;

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("type", type);
        m.put("roomId", roomId);
        m.put("watts", watts);
        m.put("standbyWatts", standbyWatts);
        m.put("quantity", quantity);
        m.put("starRating", starRating);
        m.put("hoursPerDay", hoursPerDay);
        m.put("usualStartHour", usualStartHour);
        m.put("setTemp", setTemp);
        m.put("autoOffMinutes", autoOffMinutes);
        m.put("on", on);
        m.put("onSince", onSince);
        m.put("onBy", onBy);
        m.put("notes", notes);
        m.put("createdAt", createdAt);
        return m;
    }

    public static Appliance fromMap(Map<String, Object> m) {
        Appliance a = new Appliance();
        a.id = Json.str(m, "id");
        a.name = Json.str(m, "name", "Appliance");
        a.type = Enums.parse(ApplianceType.class, Json.str(m, "type"), ApplianceType.OTHER);
        a.roomId = Json.str(m, "roomId");
        a.watts = Json.num(m, "watts", 0);
        a.standbyWatts = Json.num(m, "standbyWatts", 0);
        a.quantity = Math.max(1, Json.integer(m, "quantity", 1));
        a.starRating = Json.integer(m, "starRating", 0);
        a.hoursPerDay = Json.num(m, "hoursPerDay", 0);
        a.usualStartHour = Json.integer(m, "usualStartHour", 19);
        a.setTemp = Json.integer(m, "setTemp", 0);
        a.autoOffMinutes = Json.integer(m, "autoOffMinutes", 0);
        a.on = Json.bool(m, "on", false);
        a.onSince = Json.str(m, "onSince");
        a.onBy = Json.str(m, "onBy");
        a.notes = Json.str(m, "notes", "");
        a.createdAt = Json.str(m, "createdAt");
        return a;
    }

    /** Power of one unit (a single bulb, a single fan). */
    public double wattsPerUnit() {
        return watts / Math.max(1, quantity);
    }
}
