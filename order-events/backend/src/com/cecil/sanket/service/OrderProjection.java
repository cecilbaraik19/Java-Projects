package com.cecil.sanket.service;

import com.cecil.sanket.broker.ConsumerGroup;
import com.cecil.sanket.broker.Dispatcher;
import com.cecil.sanket.broker.Handler;
import com.cecil.sanket.broker.Schemas;
import com.cecil.sanket.model.Event;
import com.cecil.sanket.model.OrderView;
import com.cecil.sanket.model.User;
import com.cecil.sanket.store.DataStore;
import com.cecil.sanket.util.Json;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The read model (consumer group "order-view"). This is the "Q" in CQRS: the screens read orders from here,
 * not from the services. It is built ONLY from events, so it can be thrown away and rebuilt from offset 0.
 *
 * Events of one order come from four topics, and ordering is only guaranteed inside one partition. So the
 * projection does not trust the arrival order: it records WHEN each stage happened, and the status is worked out
 * from the set of stages (cancelled > delivered > dispatched > cancel requested > confirmed > paid > reserved > placed).
 */
public class OrderProjection {
    private final DataStore store;
    private Dispatcher dispatcher;

    public static final Set<String> TYPES = Set.of("OrderPlaced", "StockReserved", "StockRejected", "PaymentAuthorized",
            "PaymentFailed", "PaymentRefunded", "ShipmentCreated", "OrderConfirmed", "OrderCancelRequested", "OrderCancelled",
            "OrderCancelDenied", "ShipmentDispatched", "ShipmentDelivered", "ShipmentCancelled", "StockReleased", "StockCommitted");

    public OrderProjection(DataStore store) { this.store = store; }

    public void setDispatcher(Dispatcher d) { this.dispatcher = d; }

    public ConsumerGroup group() {
        return new ConsumerGroup("order-view", "order-view", "Builds the order screens (read model)",
                List.of("orders", "inventory", "payments", "shipments"), TYPES, this::onEvent);
    }

    private void onEvent(Event e, Handler.Out out) {
        String id = e.str("orderId");
        OrderView v = store.views.get(id);
        if (v == null) {
            v = new OrderView();
            v.orderId = id;
            v.status = "PLACED";
            store.views.put(id, v);
        }
        Map<String, Object> p = Schemas.upcast(e);
        switch (e.type) {
            case "OrderPlaced" -> {
                v.customerId = Json.str(p, "customerId");
                v.city = Json.str(p, "city");
                v.total = Json.num(p, "total", 0);
                v.lines = Json.list(p, "items");
                v.units = 0;
                for (Object o : v.lines) if (o instanceof Map<?, ?> m && m.get("qty") instanceof Number n) v.units += n.intValue();
                v.paymentMethod = Json.str(p, "paymentMethod");
                v.placedAt = e.at;
                stage(v, "placed", e.at);
            }
            case "StockReserved" -> stage(v, "reserved", e.at);
            case "StockRejected", "PaymentFailed" -> { }
            case "PaymentAuthorized" -> stage(v, "paid", e.at);
            case "ShipmentCreated" -> { v.courier = e.str("courier"); v.awb = e.str("awb"); }
            case "OrderConfirmed" -> stage(v, "confirmed", e.at);
            case "OrderCancelRequested" -> stage(v, "cancelRequested", e.at);
            case "OrderCancelDenied" -> stage(v, "cancelDenied", e.at);
            case "OrderCancelled" -> { stage(v, "cancelled", e.at); v.reason = e.str("reason"); }
            case "PaymentRefunded" -> stage(v, "refunded", e.at);
            case "ShipmentDispatched" -> stage(v, "dispatched", e.at);
            case "ShipmentDelivered" -> stage(v, "delivered", e.at);
            default -> { }
        }
        v.events++;
        v.lastEvent = e.type;
        v.updatedAt = Math.max(v.updatedAt, e.at);
        v.status = status(v);
        store.markDirty("views");
    }

    private static void stage(OrderView v, String name, long at) { v.stages.putIfAbsent(name, at); }

    /** The status from the stages reached - the same answer whatever order the events arrived in. */
    public static String status(OrderView v) {
        if (v.stage("cancelled") > 0) return "CANCELLED";
        if (v.stage("delivered") > 0) return "DELIVERED";
        if (v.stage("dispatched") > 0) return "DISPATCHED";
        if (v.stage("cancelRequested") > 0 && v.stage("cancelDenied") == 0) return "CANCEL_REQUESTED";
        if (v.stage("confirmed") > 0) return "CONFIRMED";
        if (v.stage("paid") > 0) return "PAID";
        if (v.stage("reserved") > 0) return "RESERVED";
        return "PLACED";
    }

    /** Throws the read model away and replays the whole log into it. */
    public int rebuild(User actor) {
        synchronized (store) {
            int before = store.views.size();
            store.views.clear();
            dispatcher.reset("order-view", "earliest");
            store.log(actor == null ? null : actor.id, "view.rebuild", "order-view", "dropped " + before + " orders, replaying from offset 0");
            store.markDirty("views", "audit");
            return before;
        }
    }
}
