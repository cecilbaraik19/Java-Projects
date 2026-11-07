package com.cecil.sambandh.model;

import com.cecil.sambandh.util.Json;

import java.time.LocalDate;
import java.util.Map;

/** Something the customer bought. Amounts are whole paise (100 paise = ₹1). */
public class Order implements Json.Writable {
    public String id;
    public String customerId;
    public String item;
    public long amountPaise;
    public LocalDate date;
    public String status = "PAID";   // PAID, PENDING, REFUNDED
    public String byUserId;

    /** Refunded orders do not count as money earned. */
    public boolean counts() { return !"REFUNDED".equals(status); }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "customerId", customerId, "item", item, "amountPaise", amountPaise,
                "date", date.toString(), "status", status, "byUserId", byUserId);
    }

    public static Order fromJson(Map<String, Object> m) {
        Order o = new Order();
        o.id = Json.str(m, "id");
        o.customerId = Json.str(m, "customerId");
        o.item = Json.str(m, "item");
        o.amountPaise = Json.num(m, "amountPaise", 0);
        o.date = LocalDate.parse(Json.str(m, "date"));
        o.status = Json.str(m, "status", "PAID");
        o.byUserId = Json.str(m, "byUserId");
        return o;
    }
}
