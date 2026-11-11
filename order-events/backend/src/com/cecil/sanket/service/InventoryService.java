package com.cecil.sanket.service;

import com.cecil.sanket.broker.ConsumerGroup;
import com.cecil.sanket.broker.Handler;
import com.cecil.sanket.broker.Schemas;
import com.cecil.sanket.model.Event;
import com.cecil.sanket.model.Order;
import com.cecil.sanket.model.Product;
import com.cecil.sanket.model.Reservation;
import com.cecil.sanket.model.Role;
import com.cecil.sanket.model.User;
import com.cecil.sanket.store.DataStore;
import com.cecil.sanket.util.Clock;
import com.cecil.sanket.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The inventory service owns the products and their stock (consumer group "inventory").
 *   OrderPlaced         -> hold the stock for ALL items, or for none (StockReserved / StockRejected)
 *   OrderCancelled      -> give held stock back (StockReleased)            = compensation
 *   ShipmentDispatched  -> the parcel left: take the held stock off the shelf (StockCommitted)
 * Each step first checks its own reservation record, so the same order is never reserved twice
 * (even if OrderPlaced is delivered twice, or the log is replayed).
 */
public class InventoryService {
    private final DataStore store;

    public InventoryService(DataStore store) { this.store = store; }

    public ConsumerGroup group() {
        return new ConsumerGroup("inventory", "inventory-service", "Holds, releases and takes stock",
                List.of("orders", "shipments"), Set.of("OrderPlaced", "OrderCancelled", "ShipmentDispatched"), this::onEvent);
    }

    @SuppressWarnings("unchecked")
    private void onEvent(Event e, Handler.Out out) {
        String id = e.str("orderId");
        Reservation r = store.reservations.get(id);
        switch (e.type) {
            case "OrderPlaced" -> {
                if (r != null) return;                          // already decided for this order
                Map<String, Object> p = Schemas.upcast(e);      // v1 -> v2 shape
                List<Order.Line> lines = new ArrayList<>();
                for (Object o : Json.list(p, "items")) lines.add(Order.Line.fromJson((Map<String, Object>) o));
                r = new Reservation();
                r.orderId = id;
                r.lines = lines;
                r.at = Clock.millis();
                for (Order.Line l : lines) {
                    Product pr = store.products.get(l.sku);
                    int free = pr == null ? 0 : pr.available();
                    if (free < l.qty) {
                        r.state = "REJECTED";
                        store.reservations.put(id, r);
                        store.markDirty("reservations");
                        out.emit("inventory", "StockRejected", Json.obj("orderId", id, "sku", l.sku, "wanted", l.qty, "available", free));
                        return;
                    }
                }
                for (Order.Line l : lines) store.products.get(l.sku).reserved += l.qty;
                r.state = "RESERVED";
                store.reservations.put(id, r);
                store.markDirty("reservations", "products");
                out.emit("inventory", "StockReserved", Json.obj("orderId", id, "items", p.get("items"), "total", Json.num(p, "total", 0),
                        "paymentMethod", Json.str(p, "paymentMethod"), "city", Json.str(p, "city", "")));
            }
            case "OrderCancelled" -> {
                if (r == null || !"RESERVED".equals(r.state)) return;
                int units = 0;
                for (Order.Line l : r.lines) {
                    Product pr = store.products.get(l.sku);
                    if (pr != null) pr.reserved = Math.max(0, pr.reserved - l.qty);
                    units += l.qty;
                }
                r.state = "RELEASED";
                store.markDirty("reservations", "products");
                out.emit("inventory", "StockReleased", Json.obj("orderId", id, "units", units));
            }
            case "ShipmentDispatched" -> {
                if (r == null || !"RESERVED".equals(r.state)) return;
                int units = 0;
                for (Order.Line l : r.lines) {
                    Product pr = store.products.get(l.sku);
                    if (pr != null) { pr.reserved = Math.max(0, pr.reserved - l.qty); pr.stock = Math.max(0, pr.stock - l.qty); }
                    units += l.qty;
                }
                r.state = "COMMITTED";
                store.markDirty("reservations", "products");
                out.emit("inventory", "StockCommitted", Json.obj("orderId", id, "units", units));
            }
            default -> { }
        }
    }

    /** New stock arrived (staff, or the simulator's supplier). Goes out as StockReplenished through the outbox. */
    public Product restock(User actor, String sku, int qty) {
        if (actor != null && !actor.role.atLeast(Role.STAFF)) throw Problem.forbidden("Only staff can restock.");
        if (qty < 1 || qty > 500) throw Problem.bad("Add 1 to 500 units.");
        synchronized (store) {
            Product p = store.products.get(sku);
            if (p == null) throw Problem.notFound("Product");
            p.stock += qty;
            store.outbox("inventory", "StockReplenished", 1, p.sku, "inventory-service", Json.obj("sku", p.sku, "qty", qty, "stock", p.stock));
            store.markDirty("products");
            if (actor != null) store.log(actor.id, "stock.add", p.sku, "+" + qty + " → " + p.stock + " on the shelf");
            return p;
        }
    }

    public List<Object> products() {
        synchronized (store) {
            List<Object> out = new ArrayList<>();
            for (Product p : store.products.values()) out.add(p.view());
            return out;
        }
    }
}
