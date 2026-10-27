package com.cecil.pipeline.model;

import com.cecil.pipeline.util.Json;

import java.util.Map;

/** A person at an account (the buyer we talk to). */
public class Contact {

    public String id;
    public String accountId;
    public String name;
    public String title;      // job title
    public String email;
    public String phone;
    public boolean decisionMaker;
    public String createdAt;
    public String updatedAt;

    public Map<String, Object> toMap() {
        return Json.obj(
            "id", id,
            "accountId", accountId,
            "name", name,
            "title", title,
            "email", email,
            "phone", phone,
            "decisionMaker", decisionMaker,
            "createdAt", createdAt,
            "updatedAt", updatedAt);
    }

    public static Contact fromMap(Map<String, Object> m) {
        Contact c = new Contact();
        c.id = Json.str(m, "id");
        c.accountId = Json.str(m, "accountId");
        c.name = Json.str(m, "name", "");
        c.title = Json.str(m, "title", "");
        c.email = Json.str(m, "email", "");
        c.phone = Json.str(m, "phone", "");
        c.decisionMaker = Json.bool(m, "decisionMaker", false);
        c.createdAt = Json.str(m, "createdAt");
        c.updatedAt = Json.str(m, "updatedAt");
        return c;
    }
}
