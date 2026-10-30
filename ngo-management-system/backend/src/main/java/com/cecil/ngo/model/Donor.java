package com.cecil.ngo.model;

import com.cecil.ngo.model.Enums.DonorType;
import com.cecil.ngo.util.Json;

import java.util.Map;

/** A person or organisation that gives money to the NGO. */
public class Donor implements Json.JsonAble {
    public String id;
    public String name;
    public DonorType type;
    public String email;
    public String phone;
    public String city;
    public String createdAt;

    @Override
    public Object toJson() {
        return Json.map("id", id, "name", name, "type", type, "email", email, "phone", phone, "city", city, "createdAt", createdAt);
    }

    public static Donor fromJson(Map<String, Object> m) {
        Donor d = new Donor();
        d.id = Json.str(m, "id");
        d.name = Json.str(m, "name");
        d.type = DonorType.valueOf(Json.str(m, "type", "INDIVIDUAL"));
        d.email = Json.str(m, "email", "");
        d.phone = Json.str(m, "phone", "");
        d.city = Json.str(m, "city", "");
        d.createdAt = Json.str(m, "createdAt");
        return d;
    }
}
