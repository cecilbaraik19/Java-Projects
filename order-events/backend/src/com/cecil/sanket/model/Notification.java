package com.cecil.sanket.model;

import com.cecil.sanket.util.Json;

import java.util.Map;

/** A message the notification service sent to a customer (shown in their inbox, like an SMS or e-mail). */
public class Notification implements Json.Writable {
    public String id;
    public String customerId;
    public String orderId;
    public String kind;      // the event type that caused it
    public String text;
    public long at;
    public boolean read;

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "customerId", customerId, "orderId", orderId, "kind", kind, "text", text, "at", at, "read", read);
    }

    public static Notification fromJson(Map<String, Object> m) {
        Notification n = new Notification();
        n.id = Json.str(m, "id");
        n.customerId = Json.str(m, "customerId");
        n.orderId = Json.str(m, "orderId");
        n.kind = Json.str(m, "kind");
        n.text = Json.str(m, "text");
        n.at = Json.num(m, "at", 0);
        n.read = Json.bool(m, "read", false);
        return n;
    }
}
