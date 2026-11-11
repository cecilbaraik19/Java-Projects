package com.cecil.sanket.model;

import com.cecil.sanket.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The order as the order service wrote it when the customer pressed "Place order" (the "command side").
 * Its live status is NOT kept here: the status comes from the events and is shown by the read model (OrderView).
 */
public class Order implements Json.Writable {
    public String id;
    public String customerId;
    public List<Line> lines = new ArrayList<>();
    public long total;
    public String city;
    public String paymentMethod;  // UPI, CARD, WALLET, COD
    public String channel;        // WEB, APP, APP_OLD (the old mobile app still sends version 1 events)
    public String giftNote;
    public long placedAt;
    public String placedBy;       // user id (null when the simulator placed it)

    public static final class Line implements Json.Writable {
        public String sku;
        public String name;
        public int qty;
        public long price;

        public Line(String sku, String name, int qty, long price) {
            this.sku = sku; this.name = name; this.qty = qty; this.price = price;
        }

        @Override
        public Map<String, Object> toJson() { return Json.obj("sku", sku, "name", name, "qty", qty, "price", price); }

        public static Line fromJson(Map<String, Object> m) {
            return new Line(Json.str(m, "sku"), Json.str(m, "name"), (int) Json.num(m, "qty", 1), Json.num(m, "price", 0));
        }
    }

    public int units() { return lines.stream().mapToInt(l -> l.qty).sum(); }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "customerId", customerId, "lines", lines, "total", total, "city", city,
                "paymentMethod", paymentMethod, "channel", channel, "giftNote", giftNote, "placedAt", placedAt,
                "placedBy", placedBy);
    }

    @SuppressWarnings("unchecked")
    public static Order fromJson(Map<String, Object> m) {
        Order o = new Order();
        o.id = Json.str(m, "id");
        o.customerId = Json.str(m, "customerId");
        for (Object x : Json.list(m, "lines")) o.lines.add(Line.fromJson((Map<String, Object>) x));
        o.total = Json.num(m, "total", 0);
        o.city = Json.str(m, "city");
        o.paymentMethod = Json.str(m, "paymentMethod");
        o.channel = Json.str(m, "channel", "WEB");
        o.giftNote = Json.str(m, "giftNote");
        o.placedAt = Json.num(m, "placedAt", 0);
        o.placedBy = Json.str(m, "placedBy");
        return o;
    }

    /** The lines in the shape used inside event payloads. */
    public List<Object> linesPayload() {
        List<Object> out = new ArrayList<>();
        for (Line l : lines) out.add(l.toJson());
        return out;
    }
}
