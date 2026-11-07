package com.cecil.sambandh.model;

import com.cecil.sambandh.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One customer (a person, or a person at a business). */
public class Customer implements Json.Writable {
    public String id;            // C-1001
    public String name;          // the person we talk to
    public String company;       // optional
    public String email;         // stored in lower case
    public String phone;         // stored as +91XXXXXXXXXX
    public String city;
    public String type = "INDIVIDUAL";   // INDIVIDUAL or BUSINESS
    public String source;        // where they came from: Website, Referral, ...
    public Stage stage = Stage.LEAD;
    public String ownerId;       // the staff member responsible
    public List<String> tags = new ArrayList<>();
    public String notes;

    // consent (India's DPDP Act 2023 and TRAI's Do-Not-Disturb rules)
    public boolean consentEmail;   // agreed to receive e-mails from us
    public boolean doNotCall;      // asked us NOT to phone them

    public long createdAt;
    public long updatedAt;
    public boolean erased;         // personal data erased on request
    public String mergedInto;      // if this was a duplicate: the customer it was merged into

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "name", name, "company", company, "email", email, "phone", phone, "city", city,
                "type", type, "source", source, "stage", stage, "ownerId", ownerId, "tags", tags, "notes", notes,
                "consentEmail", consentEmail, "doNotCall", doNotCall, "createdAt", createdAt, "updatedAt", updatedAt,
                "erased", erased, "mergedInto", mergedInto);
    }

    public static Customer fromJson(Map<String, Object> m) {
        Customer c = new Customer();
        c.id = Json.str(m, "id");
        c.name = Json.str(m, "name");
        c.company = Json.str(m, "company");
        c.email = Json.str(m, "email");
        c.phone = Json.str(m, "phone");
        c.city = Json.str(m, "city");
        c.type = Json.str(m, "type", "INDIVIDUAL");
        c.source = Json.str(m, "source");
        Stage st = Stage.parse(Json.str(m, "stage"));
        c.stage = st == null ? Stage.LEAD : st;
        c.ownerId = Json.str(m, "ownerId");
        c.tags = new ArrayList<>(Json.strings(m, "tags"));
        c.notes = Json.str(m, "notes");
        c.consentEmail = Json.bool(m, "consentEmail", false);
        c.doNotCall = Json.bool(m, "doNotCall", false);
        c.createdAt = Json.num(m, "createdAt", 0);
        c.updatedAt = Json.num(m, "updatedAt", 0);
        c.erased = Json.bool(m, "erased", false);
        c.mergedInto = Json.str(m, "mergedInto");
        return c;
    }

    /** Merged customers are hidden everywhere (their records moved to the customer they were merged into). */
    public boolean visible() { return mergedInto == null; }
}
