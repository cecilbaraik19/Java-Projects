package com.cecil.sanket.service;

import com.cecil.sanket.broker.ConsumerGroup;
import com.cecil.sanket.broker.EventBroker;
import com.cecil.sanket.broker.Handler;
import com.cecil.sanket.model.Customer;
import com.cecil.sanket.model.Event;
import com.cecil.sanket.model.Order;
import com.cecil.sanket.model.OrderView;
import com.cecil.sanket.model.Product;
import com.cecil.sanket.model.Role;
import com.cecil.sanket.model.User;
import com.cecil.sanket.store.DataStore;
import com.cecil.sanket.util.Clock;
import com.cecil.sanket.util.Json;
import com.cecil.sanket.util.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The order service.
 *
 * 1) COMMANDS (from the website or the simulator): place an order, ask to cancel it.
 *    It saves the order and an outbox row together. It does NOT check stock or take money: that is the job of
 *    other services, which react to the OrderPlaced event. So the customer gets an answer at once.
 *
 * 2) THE SAGA (consumer group "order-saga"): a saga is a business process made of several local steps in
 *    different services, without one big transaction. This one is "choreographed": every service reacts to events.
 *    The order-saga group only watches the outcome and decides the final answer:
 *        StockRejected or PaymentFailed            -> OrderCancelled  (others then undo their part = compensation)
 *        ShipmentCreated                           -> OrderConfirmed
 *        OrderCancelRequested (before dispatch)    -> OrderCancelled
 *        OrderCancelRequested (after dispatch)     -> OrderCancelDenied
 */
public class OrderService {
    public static final Set<String> METHODS = Set.of("UPI", "CARD", "WALLET", "COD");
    public static final Set<String> CHANNELS = Set.of("WEB", "APP", "APP_OLD");
    public static final int MAX_LINES = 8, MAX_QTY = 5;

    private final DataStore store;

    public OrderService(DataStore store) { this.store = store; }

    // ------------------------------------------------------------------ commands

    /** From the website. Customers order for themselves; staff can place a test order for any customer. */
    public Order place(User actor, Map<String, Object> body) {
        String customerId = actor.role == Role.CUSTOMER ? actor.customerId : Text.clean(Json.str(body, "customerId"), 20);
        if (customerId == null) throw Problem.bad("Choose the customer.");
        List<String[]> items = new ArrayList<>();
        for (Object o : Json.list(body, "items")) {
            if (!(o instanceof Map<?, ?> m)) throw Problem.bad("Each item needs a sku and a qty.");
            @SuppressWarnings("unchecked") Map<String, Object> it = (Map<String, Object>) m;
            items.add(new String[]{Json.str(it, "sku"), String.valueOf(Json.num(it, "qty", 0))});
        }
        String method = Text.clean(Json.str(body, "paymentMethod", ""), 10);
        String note = Text.clean(Json.str(body, "giftNote"), 120);
        Order o = placeFor(customerId, items, method == null ? null : method.toUpperCase(), "WEB", note, actor.id);
        synchronized (store) { store.log(actor.id, "order.place", o.id, o.units() + " item(s), ₹" + o.total); }
        return o;
    }

    /** items = [sku, qty] pairs. Prices always come from the catalogue, never from the browser. */
    public Order placeFor(String customerId, List<String[]> items, String method, String channel, String giftNote, String placedBy) {
        if (items.isEmpty()) throw Problem.bad("The cart is empty.");
        if (items.size() > MAX_LINES) throw Problem.bad("At most " + MAX_LINES + " different items per order.");
        if (!CHANNELS.contains(channel)) throw Problem.bad("Unknown channel.");
        if ("APP_OLD".equals(channel)) method = "COD";   // the old app only had cash on delivery
        if (method == null || !METHODS.contains(method)) throw Problem.bad("Choose how to pay: UPI, card, wallet or cash on delivery.");
        synchronized (store) {
            Customer c = store.customers.get(customerId);
            if (c == null) throw Problem.notFound("Customer");
            Map<String, Order.Line> lines = new LinkedHashMap<>();
            for (String[] it : items) {
                Product p = it[0] == null ? null : store.products.get(it[0]);
                if (p == null) throw Problem.bad("Unknown product " + Text.clean(it[0], 20) + ".");
                int qty;
                try { qty = Integer.parseInt(it[1]); } catch (NumberFormatException e) { qty = 0; }
                Order.Line l = lines.get(p.sku);
                int total = (l == null ? 0 : l.qty) + qty;
                if (qty < 1 || total > MAX_QTY) throw Problem.bad("Quantity for " + p.name + " must be 1 to " + MAX_QTY + ".");
                lines.put(p.sku, new Order.Line(p.sku, p.name, total, p.price));
            }
            Order o = new Order();
            o.id = store.nextId("ORD", 10001);
            o.customerId = c.id;
            o.lines.addAll(lines.values());
            o.total = o.lines.stream().mapToLong(l -> l.price * l.qty).sum();
            o.city = c.city;
            o.paymentMethod = method;
            o.channel = channel;
            o.giftNote = giftNote;
            o.placedAt = Clock.millis();
            o.placedBy = placedBy;
            store.orders.put(o.id, o);
            store.sagas.put(o.id, "PLACED");
            Map<String, Object> payload = Json.obj("orderId", o.id, "customerId", c.id, "items", o.linesPayload(),
                    "total", o.total, "city", o.city);
            int version = 1;
            if (!"APP_OLD".equals(channel)) { payload.put("paymentMethod", method); version = 2; }
            payload.put("channel", channel);
            if (giftNote != null) payload.put("giftNote", giftNote);
            store.outbox("orders", "OrderPlaced", version, o.id, "order-service", payload);
            store.markDirty("orders", "sagas");
            return o;
        }
    }

    /** Ask to cancel. The answer comes later as an event (OrderCancelled or OrderCancelDenied). */
    public void cancel(User actor, String orderId) {
        synchronized (store) {
            Order o = store.orders.get(orderId);
            if (o == null || (actor != null && actor.role == Role.CUSTOMER && !o.customerId.equals(actor.customerId)))
                throw Problem.notFound("Order");
            OrderView v = store.views.get(orderId);
            String status = v == null ? "PLACED" : v.status;
            if (Set.of("DISPATCHED", "DELIVERED").contains(status)) throw Problem.conflict("The parcel has already left the warehouse, so it cannot be cancelled now.");
            if ("CANCELLED".equals(status)) throw Problem.conflict("This order is already cancelled.");
            if ("CANCEL_REQUESTED".equals(status) || Json.bool(store.meta, "cancelAsked." + orderId, false))
                throw Problem.conflict("A cancel request is already on its way.");
            store.meta.put("cancelAsked." + orderId, true);
            String by = actor == null ? "customer" : actor.role == Role.CUSTOMER ? "customer" : "staff (" + actor.name + ")";
            store.outbox("orders", "OrderCancelRequested", 1, orderId, "order-service", Json.obj("orderId", orderId, "by", by));
            if (actor != null) store.log(actor.id, "order.cancel", orderId, "cancel requested by " + by);
        }
    }

    // ------------------------------------------------------------------ the saga

    public ConsumerGroup sagaGroup() {
        return new ConsumerGroup("order-saga", "order-saga", "Decides the final outcome of each order",
                List.of("orders", "inventory", "payments", "shipments"),
                Set.of("OrderPlaced", "StockRejected", "PaymentFailed", "ShipmentCreated", "OrderCancelRequested",
                        "ShipmentDispatched", "ShipmentDelivered"),
                this::onEvent);
    }

    private void onEvent(Event e, Handler.Out out) {
        String id = e.str("orderId");
        String stage = store.sagas.getOrDefault(id, "PLACED");
        boolean finished = stage.equals("CANCELLED") || stage.equals("DELIVERED");
        switch (e.type) {
            case "OrderPlaced" -> store.sagas.putIfAbsent(id, "PLACED");
            case "StockRejected" -> {
                if (finished) return;
                out.emit("orders", "OrderCancelled", Json.obj("orderId", id, "reason", "OUT_OF_STOCK",
                        "detail", "Not enough stock of " + e.str("sku") + " (wanted " + e.num("wanted") + ", free " + e.num("available") + ")."));
                stage = "CANCELLED";
            }
            case "PaymentFailed" -> {
                if (finished) return;
                out.emit("orders", "OrderCancelled", Json.obj("orderId", id, "reason", "PAYMENT_DECLINED", "detail", e.str("reason")));
                stage = "CANCELLED";
            }
            case "ShipmentCreated" -> {
                if (!stage.equals("PLACED")) return;
                out.emit("orders", "OrderConfirmed", Json.obj("orderId", id, "courier", e.str("courier"), "awb", e.str("awb")));
                stage = "CONFIRMED";
            }
            case "OrderCancelRequested" -> {
                if (stage.equals("CANCELLED")) return;
                if (stage.equals("DISPATCHED") || stage.equals("DELIVERED")) {
                    out.emit("orders", "OrderCancelDenied", Json.obj("orderId", id, "detail", "The parcel had already left the warehouse."));
                    return;
                }
                out.emit("orders", "OrderCancelled", Json.obj("orderId", id, "reason", "CUSTOMER", "detail", "Cancelled on request of the " + e.str("by") + "."));
                stage = "CANCELLED";
            }
            case "ShipmentDispatched" -> { if (!finished) stage = "DISPATCHED"; }
            case "ShipmentDelivered" -> { if (!stage.equals("CANCELLED")) stage = "DELIVERED"; }
            default -> { }
        }
        store.sagas.put(id, stage);
        store.markDirty("sagas");
    }
}
