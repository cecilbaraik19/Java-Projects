package com.cecil.bijak.model;

import com.cecil.bijak.util.Json;

import java.util.Map;

/**
 * A customer (the buyer on the invoice).
 * A business customer has a GSTIN (B2B). A customer without a GSTIN is B2C; their state is typed by hand.
 */
public class Customer implements Json.Writable {
    public String id, name, gstin, state, contact, email, phone, billingAddress, shippingAddress, notes;
    /** Days to pay after the invoice date (Net 30 = 30). */
    public int termsDays = 30;
    /** Most the customer may owe at one time, in paise. 0 = no limit. */
    public long creditLimit;
    /** Income-tax TDS the customer takes off when paying, in basis points (200 = 2 %). Usually 0, 100 (194C) or 200/1000 (194J). */
    public int tdsBp;
    /** true = no automatic payment reminders (for example a government office that pays on its own cycle). */
    public boolean noReminders;
    public boolean active = true;
    public long createdAt;
    public String createdBy;

    public boolean b2b() { return gstin != null && !gstin.isBlank(); }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "name", name, "gstin", gstin, "state", state, "contact", contact, "email", email,
                "phone", phone, "billingAddress", billingAddress, "shippingAddress", shippingAddress, "notes", notes,
                "termsDays", termsDays, "creditLimit", creditLimit, "tdsBp", tdsBp, "noReminders", noReminders,
                "active", active, "createdAt", createdAt, "createdBy", createdBy);
    }

    public static Customer fromJson(Map<String, Object> m) {
        Customer c = new Customer();
        c.id = Json.str(m, "id");
        c.name = Json.str(m, "name");
        c.gstin = Json.str(m, "gstin");
        c.state = Json.str(m, "state");
        c.contact = Json.str(m, "contact");
        c.email = Json.str(m, "email");
        c.phone = Json.str(m, "phone");
        c.billingAddress = Json.str(m, "billingAddress");
        c.shippingAddress = Json.str(m, "shippingAddress");
        c.notes = Json.str(m, "notes");
        c.termsDays = (int) Json.num(m, "termsDays", 30);
        c.creditLimit = Json.num(m, "creditLimit", 0);
        c.tdsBp = (int) Json.num(m, "tdsBp", 0);
        c.noReminders = Json.bool(m, "noReminders", false);
        c.active = Json.bool(m, "active", true);
        c.createdAt = Json.num(m, "createdAt", 0);
        c.createdBy = Json.str(m, "createdBy");
        return c;
    }
}
