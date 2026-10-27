package com.cecil.pipeline.model;

import com.cecil.pipeline.util.Json;

import java.util.Map;

/** A company we are selling to. */
public class Account {

    public String id;
    public String name;
    public String industry;
    public String website;
    public String phone;
    public String city;
    public String size;      // e.g. "1-10", "11-50", "51-200", "201-1000", "1000+"
    public String owner;     // username of the rep who looks after this account
    public String notes;
    public String createdAt;
    public String updatedAt;

    public Map<String, Object> toMap() {
        return Json.obj(
            "id", id,
            "name", name,
            "industry", industry,
            "website", website,
            "phone", phone,
            "city", city,
            "size", size,
            "owner", owner,
            "notes", notes,
            "createdAt", createdAt,
            "updatedAt", updatedAt);
    }

    public static Account fromMap(Map<String, Object> m) {
        Account a = new Account();
        a.id = Json.str(m, "id");
        a.name = Json.str(m, "name", "");
        a.industry = Json.str(m, "industry", "Other");
        a.website = Json.str(m, "website", "");
        a.phone = Json.str(m, "phone", "");
        a.city = Json.str(m, "city", "");
        a.size = Json.str(m, "size", "");
        a.owner = Json.str(m, "owner");
        a.notes = Json.str(m, "notes", "");
        a.createdAt = Json.str(m, "createdAt");
        a.updatedAt = Json.str(m, "updatedAt");
        return a;
    }
}
