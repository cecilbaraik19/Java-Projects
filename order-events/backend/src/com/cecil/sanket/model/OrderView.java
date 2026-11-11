package com.cecil.sanket.model;

import com.cecil.sanket.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The read model ("projection") of one order: built ONLY from events by the order-view consumer.
 * It can be thrown away and built again at any time by replaying the log from offset 0.
 */
public class OrderView implements Json.Writable {
    public String orderId;
    public String customerId;
    public String city;
    public long total;
    public int units;
    public List<Object> lines = new ArrayList<>();
    public String paymentMethod;
    public String status;                 // see STATUSES
    public String reason;                 // why it was cancelled
    public String courier, awb;
    public long placedAt, updatedAt;
    /** When each stage was reached: placed, reserved, paid, confirmed, dispatched, delivered, cancelled. */
    public Map<String, Object> stages = new LinkedHashMap<>();
    public int events;                    // how many events of this order the projection has seen
    public String lastEvent;

    public static final List<String> STATUSES = List.of("PLACED", "RESERVED", "PAID", "CONFIRMED", "DISPATCHED",
            "DELIVERED", "CANCEL_REQUESTED", "CANCELLED");

    public boolean open() { return !"DELIVERED".equals(status) && !"CANCELLED".equals(status); }

    public long stage(String name) { return Json.num(stages, name, 0); }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("orderId", orderId, "customerId", customerId, "city", city, "total", total, "units", units,
                "lines", lines, "paymentMethod", paymentMethod, "status", status, "reason", reason, "courier", courier,
                "awb", awb, "placedAt", placedAt, "updatedAt", updatedAt, "stages", stages, "events", events,
                "lastEvent", lastEvent);
    }

    @SuppressWarnings("unchecked")
    public static OrderView fromJson(Map<String, Object> m) {
        OrderView v = new OrderView();
        v.orderId = Json.str(m, "orderId");
        v.customerId = Json.str(m, "customerId");
        v.city = Json.str(m, "city");
        v.total = Json.num(m, "total", 0);
        v.units = (int) Json.num(m, "units", 0);
        v.lines = Json.list(m, "lines");
        v.paymentMethod = Json.str(m, "paymentMethod");
        v.status = Json.str(m, "status");
        v.reason = Json.str(m, "reason");
        v.courier = Json.str(m, "courier");
        v.awb = Json.str(m, "awb");
        v.placedAt = Json.num(m, "placedAt", 0);
        v.updatedAt = Json.num(m, "updatedAt", 0);
        if (m.get("stages") instanceof Map<?, ?> s) v.stages = new LinkedHashMap<>((Map<String, Object>) s);
        v.events = (int) Json.num(m, "events", 0);
        v.lastEvent = Json.str(m, "lastEvent");
        return v;
    }
}
