package com.cecil.sanket.model;

import com.cecil.sanket.util.Json;

import java.util.Map;

/** The shipping service's record for one order. CREATED -> DISPATCHED -> DELIVERED, or CREATED -> CANCELLED. */
public class Shipment implements Json.Writable {
    public String id;
    public String orderId;
    public String courier;
    public String awb;         // air way bill = tracking number
    public String city;
    public String state;
    public long createdAt, dispatchedAt, deliveredAt;

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "orderId", orderId, "courier", courier, "awb", awb, "city", city, "state", state,
                "createdAt", createdAt, "dispatchedAt", dispatchedAt, "deliveredAt", deliveredAt);
    }

    public static Shipment fromJson(Map<String, Object> m) {
        Shipment s = new Shipment();
        s.id = Json.str(m, "id");
        s.orderId = Json.str(m, "orderId");
        s.courier = Json.str(m, "courier");
        s.awb = Json.str(m, "awb");
        s.city = Json.str(m, "city");
        s.state = Json.str(m, "state");
        s.createdAt = Json.num(m, "createdAt", 0);
        s.dispatchedAt = Json.num(m, "dispatchedAt", 0);
        s.deliveredAt = Json.num(m, "deliveredAt", 0);
        return s;
    }
}
