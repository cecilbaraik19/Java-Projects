package com.cecil.sanket.broker;

import com.cecil.sanket.model.Event;
import com.cecil.sanket.service.Problem;
import com.cecil.sanket.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The schema registry: the list of every event type, which topic it belongs to, which fields it must have,
 * and which service is allowed to write it. The broker checks every event against this list before it accepts it,
 * so a producer with a bug cannot put a broken event into the log.
 *
 * Extra fields are allowed (so a newer producer can add a field without breaking older consumers).
 * OrderPlaced has two versions: version 1 is still sent by the old mobile app and has no paymentMethod.
 * Consumers call upcast(), which turns a version 1 payload into the version 2 shape ("upcasting").
 */
public final class Schemas {
    private Schemas() { }

    public record Field(String name, String kind, String meaning) { }

    public record Schema(String type, int version, String topic, String producer, String meaning, List<Field> fields) {
        public Map<String, Object> toJson() {
            List<Object> f = new ArrayList<>();
            for (Field x : fields) f.add(Json.obj("name", x.name(), "kind", x.kind(), "meaning", x.meaning()));
            return Json.obj("type", type, "version", version, "topic", topic, "producer", producer, "meaning", meaning, "fields", f);
        }
    }

    private static Field f(String name, String kind, String meaning) { return new Field(name, kind, meaning); }

    private static final Field ORDER = f("orderId", "text", "the order");

    public static final List<Schema> ALL = List.of(
            new Schema("OrderPlaced", 1, "orders", "order-service", "A customer placed an order (old mobile app: always cash on delivery).",
                    List.of(ORDER, f("customerId", "text", "who ordered"), f("items", "list", "sku, name, qty, price"),
                            f("total", "number", "rupees"), f("city", "text", "delivery city"))),
            new Schema("OrderPlaced", 2, "orders", "order-service", "A customer placed an order.",
                    List.of(ORDER, f("customerId", "text", "who ordered"), f("items", "list", "sku, name, qty, price"),
                            f("total", "number", "rupees"), f("city", "text", "delivery city"),
                            f("paymentMethod", "text", "UPI, CARD, WALLET or COD"))),
            new Schema("OrderCancelRequested", 1, "orders", "order-service", "The customer (or staff) asked to cancel.",
                    List.of(ORDER, f("by", "text", "who asked"))),
            new Schema("OrderConfirmed", 1, "orders", "order-saga", "Stock, payment and shipment are all in place.",
                    List.of(ORDER, f("courier", "text", "courier company"), f("awb", "text", "tracking number"))),
            new Schema("OrderCancelled", 1, "orders", "order-saga", "The order is cancelled; other services undo their part.",
                    List.of(ORDER, f("reason", "text", "OUT_OF_STOCK, PAYMENT_DECLINED or CUSTOMER"), f("detail", "text", "explanation"))),
            new Schema("OrderCancelDenied", 1, "orders", "order-saga", "A cancel request came too late (parcel already left).",
                    List.of(ORDER, f("detail", "text", "explanation"))),
            new Schema("StockReserved", 1, "inventory", "inventory-service", "All items are held for this order.",
                    List.of(ORDER, f("items", "list", "what was held"), f("total", "number", "rupees to charge"),
                            f("paymentMethod", "text", "how the customer pays"))),
            new Schema("StockRejected", 1, "inventory", "inventory-service", "At least one item is not in stock; nothing was held.",
                    List.of(ORDER, f("sku", "text", "the missing item"), f("wanted", "number", "units asked"), f("available", "number", "units free"))),
            new Schema("StockReleased", 1, "inventory", "inventory-service", "Held stock was given back (order cancelled).",
                    List.of(ORDER, f("units", "number", "units given back"))),
            new Schema("StockCommitted", 1, "inventory", "inventory-service", "The parcel left: held stock is now taken off the shelf.",
                    List.of(ORDER, f("units", "number", "units taken"))),
            new Schema("StockReplenished", 1, "inventory", "inventory-service", "New stock arrived for a product.",
                    List.of(f("sku", "text", "product"), f("qty", "number", "units added"), f("stock", "number", "new shelf count"))),
            new Schema("PaymentAuthorized", 1, "payments", "payment-service", "The money is authorised (or COD is accepted).",
                    List.of(ORDER, f("paymentId", "text", "payment record"), f("amount", "number", "rupees"),
                            f("method", "text", "UPI, CARD, WALLET or COD"), f("reference", "text", "gateway reference"))),
            new Schema("PaymentFailed", 1, "payments", "payment-service", "The bank or wallet declined the payment.",
                    List.of(ORDER, f("reason", "text", "why"))),
            new Schema("PaymentRefunded", 1, "payments", "payment-service", "Money given back after a cancel.",
                    List.of(ORDER, f("amount", "number", "rupees"), f("reference", "text", "refund reference"))),
            new Schema("ShipmentCreated", 1, "shipments", "shipping-service", "A courier and tracking number are booked.",
                    List.of(ORDER, f("shipmentId", "text", "shipment record"), f("courier", "text", "courier company"), f("awb", "text", "tracking number"))),
            new Schema("ShipmentDispatched", 1, "shipments", "shipping-service", "The parcel left the warehouse.",
                    List.of(ORDER, f("awb", "text", "tracking number"))),
            new Schema("ShipmentDelivered", 1, "shipments", "shipping-service", "The customer received the parcel.",
                    List.of(ORDER, f("awb", "text", "tracking number"))),
            new Schema("ShipmentCancelled", 1, "shipments", "shipping-service", "The courier booking was cancelled.",
                    List.of(ORDER, f("awb", "text", "tracking number"))),
            new Schema("DeadLettered", 1, "dead-letter", "broker", "A consumer gave up on an event after all retries.",
                    List.of(f("group", "text", "consumer group"), f("eventId", "text", "the failed event"),
                            f("eventType", "text", "its type"), f("error", "text", "last error"), f("attempts", "number", "tries")))
    );

    public static Schema find(String type, int version) {
        for (Schema s : ALL) if (s.type().equals(type) && s.version() == version) return s;
        return null;
    }

    public static int latest(String type) {
        int v = 0;
        for (Schema s : ALL) if (s.type().equals(type)) v = Math.max(v, s.version());
        return v;
    }

    /** Throws Problem(400) when the event does not match its schema. */
    public static void validate(String topic, String type, int version, Map<String, Object> payload) {
        Schema s = find(type, version);
        if (s == null) throw Problem.bad("Unknown event type " + type + " v" + version + ".");
        if (!s.topic().equals(topic)) throw Problem.bad(type + " belongs on topic '" + s.topic() + "', not '" + topic + "'.");
        if (payload == null) throw Problem.bad(type + " has no payload.");
        for (Field f : s.fields()) {
            Object v = payload.get(f.name());
            boolean ok = switch (f.kind()) {
                case "text" -> v instanceof String str && !str.isBlank();
                case "number" -> v instanceof Number;
                case "list" -> v instanceof List<?> l && !l.isEmpty();
                case "bool" -> v instanceof Boolean;
                default -> v != null;
            };
            if (!ok) throw Problem.bad(type + " v" + version + ": field '" + f.name() + "' must be " + f.kind() + ".");
        }
    }

    /** Gives every consumer the newest shape of a payload. OrderPlaced v1 (old app) had no paymentMethod: it was always COD. */
    public static Map<String, Object> upcast(Event e) {
        if (e.type.equals("OrderPlaced") && e.version == 1) {
            Map<String, Object> p = new LinkedHashMap<>(e.payload);
            p.putIfAbsent("paymentMethod", "COD");
            return p;
        }
        return e.payload;
    }
}
