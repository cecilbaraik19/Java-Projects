package com.cecil.sanket.model;

import com.cecil.sanket.util.Json;

import java.util.Map;

/** A shop customer (some of them also have a login). */
public class Customer implements Json.Writable {
    public String id;
    public String name;
    public String city;
    public String pincode;
    public String phone;

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "name", name, "city", city, "pincode", pincode, "phone", phone);
    }

    public static Customer fromJson(Map<String, Object> m) {
        Customer c = new Customer();
        c.id = Json.str(m, "id");
        c.name = Json.str(m, "name");
        c.city = Json.str(m, "city");
        c.pincode = Json.str(m, "pincode");
        c.phone = Json.str(m, "phone");
        return c;
    }
}
