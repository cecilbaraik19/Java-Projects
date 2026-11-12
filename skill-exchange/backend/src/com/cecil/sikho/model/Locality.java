package com.cecil.sikho.model;

import com.cecil.sikho.util.Json;

import java.util.Map;

/** A neighbourhood of the city (for example Lalpur or Kanke) with the map position of its centre. */
public class Locality implements Json.Writable {
    public String id;
    public String name;
    public double lat;
    public double lng;

    public Locality() { }

    public Locality(String id, String name, double lat, double lng) {
        this.id = id; this.name = name; this.lat = lat; this.lng = lng;
    }

    @Override
    public Map<String, Object> toJson() { return Json.obj("id", id, "name", name, "lat", lat, "lng", lng); }

    public static Locality fromJson(Map<String, Object> m) {
        return new Locality(Json.str(m, "id"), Json.str(m, "name"), Json.dbl(m, "lat", 0), Json.dbl(m, "lng", 0));
    }
}
