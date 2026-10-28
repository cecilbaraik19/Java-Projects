package com.cecil.energy.model;

import com.cecil.energy.util.Json;
import java.util.LinkedHashMap;
import java.util.Map;

/** One line in the activity history: "Rohan switched off Geyser", "Auto-off: Geyser after 30 min". */
public class Event {
    public String id;
    public String at;
    public String applianceId;
    public String text;
    public String by;
    /** ON, OFF, AUTO_OFF, EDIT, TIP, SETTINGS ... */
    public String kind;

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("at", at);
        m.put("applianceId", applianceId);
        m.put("text", text);
        m.put("by", by);
        m.put("kind", kind);
        return m;
    }

    public static Event fromMap(Map<String, Object> m) {
        Event e = new Event();
        e.id = Json.str(m, "id");
        e.at = Json.str(m, "at");
        e.applianceId = Json.str(m, "applianceId");
        e.text = Json.str(m, "text", "");
        e.by = Json.str(m, "by");
        e.kind = Json.str(m, "kind", "");
        return e;
    }
}
