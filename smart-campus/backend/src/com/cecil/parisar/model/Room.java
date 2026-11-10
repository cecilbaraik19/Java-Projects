package com.cecil.parisar.model;

import com.cecil.parisar.util.Json;

import java.util.Map;

/**
 * A room inside a building. acOn and lightsOn are "smart switches": the campus admin (or the energy-saving
 * rule) can turn them off from the website - that is the IoT "control" part (sensing + acting).
 */
public class Room implements Json.Writable {
    public String id;          // "R-101"
    public String buildingId;
    public String name;        // "Classroom A-101"
    public String kind;        // CLASSROOM, LAB, LIBRARY, SEMINAR, HOSTEL, OFFICE, CAFE, SERVER
    public int capacity;
    public boolean hasAC;
    public boolean acOn;
    public boolean lightsOn;

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "buildingId", buildingId, "name", name, "kind", kind, "capacity", capacity,
                "hasAC", hasAC, "acOn", acOn, "lightsOn", lightsOn);
    }

    public static Room fromJson(Map<String, Object> m) {
        Room r = new Room();
        r.id = Json.str(m, "id");
        r.buildingId = Json.str(m, "buildingId");
        r.name = Json.str(m, "name");
        r.kind = Json.str(m, "kind", "CLASSROOM");
        r.capacity = (int) Json.num(m, "capacity", 40);
        r.hasAC = Json.bool(m, "hasAC", false);
        r.acOn = Json.bool(m, "acOn", false);
        r.lightsOn = Json.bool(m, "lightsOn", false);
        return r;
    }
}
