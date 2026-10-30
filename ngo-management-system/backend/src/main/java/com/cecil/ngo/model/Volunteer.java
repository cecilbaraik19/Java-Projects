package com.cecil.ngo.model;

import com.cecil.ngo.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A person who gives their time to the NGO. */
public class Volunteer implements Json.JsonAble {
    public String id;
    public String name;
    public String phone;
    public String email;
    public String city;
    public List<String> skills = new ArrayList<>();
    /** WEEKDAYS, WEEKENDS or ANY. */
    public String availability = "ANY";
    public String joinedOn;
    public boolean active = true;

    @Override
    public Object toJson() {
        return Json.map("id", id, "name", name, "phone", phone, "email", email, "city", city, "skills", skills,
                "availability", availability, "joinedOn", joinedOn, "active", active);
    }

    public static Volunteer fromJson(Map<String, Object> m) {
        Volunteer v = new Volunteer();
        v.id = Json.str(m, "id");
        v.name = Json.str(m, "name");
        v.phone = Json.str(m, "phone", "");
        v.email = Json.str(m, "email", "");
        v.city = Json.str(m, "city", "");
        v.skills = new ArrayList<>(Json.strings(m, "skills"));
        v.availability = Json.str(m, "availability", "ANY");
        v.joinedOn = Json.str(m, "joinedOn");
        v.active = Json.bool(m, "active", true);
        return v;
    }
}
