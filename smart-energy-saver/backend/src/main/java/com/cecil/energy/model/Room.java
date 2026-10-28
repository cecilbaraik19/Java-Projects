package com.cecil.energy.model;

import com.cecil.energy.util.Json;
import java.util.LinkedHashMap;
import java.util.Map;

/** A room or area of the home / office, such as "Kitchen". Appliances belong to a room. */
public class Room {
    public String id;
    public String name;
    public int sortOrder;

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("sortOrder", sortOrder);
        return m;
    }

    public static Room fromMap(Map<String, Object> m) {
        Room r = new Room();
        r.id = Json.str(m, "id");
        r.name = Json.str(m, "name", "Room");
        r.sortOrder = Json.integer(m, "sortOrder", 0);
        return r;
    }
}
