package com.cecil.sanket.model;

import com.cecil.sanket.util.Json;

import java.util.Map;

/** The payment service's record for one order. AUTHORIZED -> REFUNDED, or FAILED. */
public class Payment implements Json.Writable {
    public String id;
    public String orderId;
    public long amount;
    public String method;
    public String state;      // AUTHORIZED, FAILED, REFUNDED
    public String reference;  // the (made-up) gateway reference
    public String reason;
    public long at;

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "orderId", orderId, "amount", amount, "method", method, "state", state,
                "reference", reference, "reason", reason, "at", at);
    }

    public static Payment fromJson(Map<String, Object> m) {
        Payment p = new Payment();
        p.id = Json.str(m, "id");
        p.orderId = Json.str(m, "orderId");
        p.amount = Json.num(m, "amount", 0);
        p.method = Json.str(m, "method");
        p.state = Json.str(m, "state");
        p.reference = Json.str(m, "reference");
        p.reason = Json.str(m, "reason");
        p.at = Json.num(m, "at", 0);
        return p;
    }
}
