package com.cecil.sanket.service;

import com.cecil.sanket.broker.ConsumerGroup;
import com.cecil.sanket.broker.Handler;
import com.cecil.sanket.model.Event;
import com.cecil.sanket.model.OrderView;
import com.cecil.sanket.model.Role;
import com.cecil.sanket.model.Shipment;
import com.cecil.sanket.model.User;
import com.cecil.sanket.store.DataStore;
import com.cecil.sanket.util.Clock;
import com.cecil.sanket.util.Json;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The shipping service (consumer group "shipping").
 *   PaymentAuthorized -> book a courier and a tracking number (ShipmentCreated)
 *   OrderCancelled    -> cancel the booking if the parcel has not left (ShipmentCancelled)   = compensation
 * Commands from the warehouse (staff or the simulator): dispatch and deliver. They go out through the outbox.
 */
public class ShippingService {
    /** Made-up courier companies, by delivery city. */
    public static final Map<String, String> COURIER = Map.of(
            "Ranchi", "Swift Courier", "Jamshedpur", "Swift Courier", "Dhanbad", "Dak Express", "Bokaro", "Dak Express",
            "Hazaribagh", "Ganga Logistics", "Deoghar", "Ganga Logistics", "Patna", "Rail Parcel", "Kolkata", "Rail Parcel",
            "Bhubaneswar", "Rail Parcel", "Gaya", "Ganga Logistics");

    private final DataStore store;

    public ShippingService(DataStore store) { this.store = store; }

    public ConsumerGroup group() {
        return new ConsumerGroup("shipping", "shipping-service", "Books couriers and tracking numbers",
                List.of("payments", "orders"), Set.of("PaymentAuthorized", "OrderCancelled"), this::onEvent);
    }

    private void onEvent(Event e, Handler.Out out) {
        String id = e.str("orderId");
        Shipment s = store.shipments.get(id);
        if (e.type.equals("PaymentAuthorized")) {
            if (s != null) return;                     // already booked (or cancelled first)
            // the city travels inside the events (OrderPlaced -> StockReserved -> PaymentAuthorized), so shipping
            // never has to read the order service's tables ("event-carried state transfer")
            String city = e.str("city") == null || e.str("city").isBlank() ? "Ranchi" : e.str("city");
            s = new Shipment();
            s.id = store.nextId("SHP", 70001);
            s.orderId = id;
            s.city = city;
            s.courier = COURIER.getOrDefault(city, "Swift Courier");
            s.awb = s.courier.substring(0, 2).toUpperCase() + String.format("%09d", Math.floorMod((id + "#awb").hashCode(), 1_000_000_000));
            s.state = "CREATED";
            s.createdAt = Clock.millis();
            store.shipments.put(id, s);
            store.markDirty("shipments");
            out.emit("shipments", "ShipmentCreated", Json.obj("orderId", id, "shipmentId", s.id, "courier", s.courier, "awb", s.awb));
        } else if (e.type.equals("OrderCancelled")) {
            if (s == null) {                           // cancelled before payment: leave a marker so we never book later
                s = new Shipment();
                s.id = store.nextId("SHP", 70001);
                s.orderId = id;
                s.state = "CANCELLED";
                s.createdAt = Clock.millis();
                store.shipments.put(id, s);
                store.markDirty("shipments");
                return;
            }
            if (!"CREATED".equals(s.state)) return;
            s.state = "CANCELLED";
            store.markDirty("shipments");
            out.emit("shipments", "ShipmentCancelled", Json.obj("orderId", id, "awb", s.awb));
        }
    }

    public Shipment dispatch(User actor, String orderId) {
        if (actor != null && !actor.role.atLeast(Role.STAFF)) throw Problem.forbidden("Only warehouse staff can dispatch.");
        synchronized (store) {
            Shipment s = store.shipments.get(orderId);
            OrderView v = store.views.get(orderId);
            if (s == null || "CANCELLED".equals(s.state)) throw Problem.bad("This order has no courier booking yet.");
            if (!"CREATED".equals(s.state)) throw Problem.conflict("The parcel has already left.");
            if (v != null && ("CANCEL_REQUESTED".equals(v.status) || "CANCELLED".equals(v.status)))
                throw Problem.conflict("The customer asked to cancel this order. Do not dispatch it.");
            s.state = "DISPATCHED";
            s.dispatchedAt = Clock.millis();
            store.outbox("shipments", "ShipmentDispatched", 1, orderId, "shipping-service", Json.obj("orderId", orderId, "awb", s.awb));
            store.markDirty("shipments");
            if (actor != null) store.log(actor.id, "ship.dispatch", orderId, s.courier + " " + s.awb);
            return s;
        }
    }

    public Shipment deliver(User actor, String orderId) {
        if (actor != null && !actor.role.atLeast(Role.STAFF)) throw Problem.forbidden("Only staff can mark a delivery.");
        synchronized (store) {
            Shipment s = store.shipments.get(orderId);
            if (s == null || !"DISPATCHED".equals(s.state)) throw Problem.bad("Only a dispatched parcel can be delivered.");
            s.state = "DELIVERED";
            s.deliveredAt = Clock.millis();
            store.outbox("shipments", "ShipmentDelivered", 1, orderId, "shipping-service", Json.obj("orderId", orderId, "awb", s.awb));
            store.markDirty("shipments");
            if (actor != null) store.log(actor.id, "ship.deliver", orderId, s.awb);
            return s;
        }
    }
}
