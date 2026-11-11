package com.cecil.sanket.model;

import com.cecil.sanket.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Stock held by the inventory service for one order. RESERVED -> COMMITTED (parcel left) or RELEASED (cancelled). */
public class Reservation implements Json.Writable {
    public String orderId;
    public List<Order.Line> lines = new ArrayList<>();
    public String state;   // RESERVED, COMMITTED, RELEASED
    public long at;

    @Override
    public Map<String, Object> toJson() { return Json.obj("orderId", orderId, "lines", lines, "state", state, "at", at); }

    @SuppressWarnings("unchecked")
    public static Reservation fromJson(Map<String, Object> m) {
        Reservation r = new Reservation();
        r.orderId = Json.str(m, "orderId");
        for (Object x : Json.list(m, "lines")) r.lines.add(Order.Line.fromJson((Map<String, Object>) x));
        r.state = Json.str(m, "state");
        r.at = Json.num(m, "at", 0);
        return r;
    }
}
