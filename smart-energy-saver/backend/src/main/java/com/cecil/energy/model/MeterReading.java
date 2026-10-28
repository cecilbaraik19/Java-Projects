package com.cecil.energy.model;

import com.cecil.energy.util.Json;
import java.util.LinkedHashMap;
import java.util.Map;

/** A number read from the main electricity meter (total kWh since the meter was fitted). */
public class MeterReading {
    public String id;
    /** yyyy-MM-dd */
    public String date;
    public double reading;
    public String note = "";
    public String by;

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("date", date);
        m.put("reading", reading);
        m.put("note", note);
        m.put("by", by);
        return m;
    }

    public static MeterReading fromMap(Map<String, Object> m) {
        MeterReading r = new MeterReading();
        r.id = Json.str(m, "id");
        r.date = Json.str(m, "date");
        r.reading = Json.num(m, "reading", 0);
        r.note = Json.str(m, "note", "");
        r.by = Json.str(m, "by");
        return r;
    }
}
