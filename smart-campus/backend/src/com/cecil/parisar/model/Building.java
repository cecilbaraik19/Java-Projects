package com.cecil.parisar.model;

import com.cecil.parisar.util.Json;

import java.util.Map;

/** A building on the campus map. x, y, w, h place it on the 100 x 60 map grid. */
public class Building implements Json.Writable {
    public String id;        // "B-1"
    public String code;      // "AB-A" - short code printed on the map
    public String name;
    public String kind;      // ACADEMIC, LIBRARY, LAB, HOSTEL, ADMIN
    public int x, y, w, h;

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "code", code, "name", name, "kind", kind, "x", x, "y", y, "w", w, "h", h);
    }

    public static Building fromJson(Map<String, Object> m) {
        Building b = new Building();
        b.id = Json.str(m, "id");
        b.code = Json.str(m, "code");
        b.name = Json.str(m, "name");
        b.kind = Json.str(m, "kind", "ACADEMIC");
        b.x = (int) Json.num(m, "x", 0);
        b.y = (int) Json.num(m, "y", 0);
        b.w = (int) Json.num(m, "w", 10);
        b.h = (int) Json.num(m, "h", 8);
        return b;
    }
}
