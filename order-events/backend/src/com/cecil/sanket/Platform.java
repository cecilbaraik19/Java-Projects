package com.cecil.sanket;

import com.cecil.sanket.broker.Dispatcher;
import com.cecil.sanket.broker.EventBroker;
import com.cecil.sanket.service.AnalyticsService;
import com.cecil.sanket.service.AuthService;
import com.cecil.sanket.service.InventoryService;
import com.cecil.sanket.service.NotificationService;
import com.cecil.sanket.service.OrderProjection;
import com.cecil.sanket.service.OrderService;
import com.cecil.sanket.service.OutboxRelay;
import com.cecil.sanket.service.PaymentService;
import com.cecil.sanket.service.ShippingService;
import com.cecil.sanket.sim.Simulator;
import com.cecil.sanket.store.DataStore;
import com.cecil.sanket.util.Clock;

/**
 * Wires all the parts together: the broker, the dispatcher, the outbox relay and the six consumer groups.
 * Used by App (the real program) and by the tests (with memory-only storage).
 */
public final class Platform {
    public final DataStore store;
    public final EventBroker broker;
    public final Dispatcher dispatcher;
    public final OutboxRelay relay;
    public final OrderService orders;
    public final InventoryService inventory;
    public final PaymentService payment;
    public final ShippingService shipping;
    public final NotificationService notifier;
    public final OrderProjection projection;
    public final AuthService auth;
    public final AnalyticsService analytics;
    public final Simulator sim;

    public Platform(DataStore store, EventBroker broker, long seed) {
        this.store = store;
        this.broker = broker;
        this.dispatcher = new Dispatcher(broker, store);
        this.relay = new OutboxRelay(store, broker);
        this.orders = new OrderService(store);
        this.inventory = new InventoryService(store);
        this.payment = new PaymentService(store);
        this.shipping = new ShippingService(store);
        this.notifier = new NotificationService(store);
        this.projection = new OrderProjection(store);
        projection.setDispatcher(dispatcher);
        // the six consumer groups (the order matters only for the order they are shown in)
        dispatcher.register(inventory.group());
        dispatcher.register(payment.group());
        dispatcher.register(shipping.group());
        dispatcher.register(orders.sagaGroup());
        dispatcher.register(notifier.group());
        dispatcher.register(projection.group());
        this.auth = new AuthService(store);
        this.analytics = new AnalyticsService(store, broker, dispatcher, relay);
        this.sim = new Simulator(store, broker, dispatcher, orders, inventory, payment, shipping, seed);
    }

    /** One step of the live system: send waiting outbox rows, then deliver events to the consumers. */
    public int pump() {
        int n = relay.tick();
        return n + dispatcher.tick(Clock.millis(), 200);
    }

    /** Runs until everything has been delivered (tests: the clock is frozen, retries jump the clock forward). */
    public void settle() {
        for (int i = 0; i < 100; i++) {
            int r = relay.tick();
            int n = dispatcher.drain(200);
            if (r == 0 && n == 0) break;
        }
    }
}
