package com.cecil.sanket;

import com.cecil.sanket.broker.BrokerDown;
import com.cecil.sanket.broker.ConsumerGroup;
import com.cecil.sanket.broker.Dispatcher;
import com.cecil.sanket.broker.EventBroker;
import com.cecil.sanket.broker.Schemas;
import com.cecil.sanket.model.DeadLetter;
import com.cecil.sanket.model.Event;
import com.cecil.sanket.model.Order;
import com.cecil.sanket.model.OrderView;
import com.cecil.sanket.model.Product;
import com.cecil.sanket.model.User;
import com.cecil.sanket.service.Problem;
import com.cecil.sanket.sim.Simulator;
import com.cecil.sanket.store.DataStore;
import com.cecil.sanket.store.SeedData;
import com.cecil.sanket.util.Clock;
import com.cecil.sanket.util.Json;
import com.cecil.sanket.util.Passwords;
import com.cecil.sanket.util.Stats;
import com.cecil.sanket.web.ApiServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * All tests for Sanket, without JUnit (so nothing has to be downloaded).
 * Run with run-tests.bat / run-tests.sh. The clock is frozen at Wednesday 11 Nov 2026, 10:30 IST, so results never change.
 */
public final class AllTests {
    private static int passed, failed;
    private static final List<String> failures = new ArrayList<>();

    interface Body { void run() throws Exception; }

    static void test(String name, Body b) {
        try {
            b.run();
            passed++;
            System.out.println("  ok    " + name);
        } catch (Throwable t) {
            failed++;
            failures.add(name + " -> " + t);
            System.out.println("  FAIL  " + name + "  (" + t + ")");
        }
    }

    static void check(boolean cond, String msg) { if (!cond) throw new AssertionError(msg); }
    static void eq(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
    }
    static int problem(Body b) {
        try { b.run(); } catch (Problem p) { return p.status; } catch (Exception e) { throw new AssertionError("wrong exception " + e); }
        throw new AssertionError("expected a Problem");
    }

    static final Instant START = ZonedDateTime.of(2026, 11, 11, 10, 30, 0, 0, Clock.ZONE).toInstant();

    // ------------------------------------------------------------------ a fresh world
    static Platform p;
    static DataStore store;
    static EventBroker broker;

    static void world(boolean history) {
        Clock.fix(START);
        store = new DataStore(null);
        broker = new EventBroker(null);
        p = new Platform(store, broker, 42);
        SeedData.load(p, history);
        Clock.fix(START);
    }

    static User u(String username) { return store.userByName(username); }

    static Map<String, Object> m(Object... kv) { return Json.obj(kv); }

    static List<String[]> items(String... skuQty) {
        List<String[]> l = new ArrayList<>();
        for (int i = 0; i + 1 < skuQty.length; i += 2) l.add(new String[]{skuQty[i], skuQty[i + 1]});
        return l;
    }

    /** Places an order for customer C-101 (Neha) and lets the system process everything. */
    static Order order(String method, String... skuQty) {
        Order o = p.orders.placeFor("C-101", items(skuQty), method, "WEB", null, null);
        p.settle();
        return o;
    }

    static String status(String orderId) { OrderView v = store.views.get(orderId); return v == null ? null : v.status; }

    static List<String> types(String orderId) {
        List<String> t = new ArrayList<>();
        for (Event e : broker.trace(orderId)) t.add(e.type);
        return t;
    }

    /** An order id that the made-up bank accepts (or declines). */
    static String nextIdWhere(boolean declined) {
        long n = Json.num(store.meta, "next.ORD", 10001);
        for (long i = n; ; i++) {
            boolean d = Math.floorMod(("ORD-" + i + "#pay").hashCode(), 100) < 5;
            if (d == declined) { store.meta.put("next.ORD", i); return "ORD-" + i; }
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Sanket event-driven order processing - tests");

        System.out.println("\n[helpers]");
        test("JSON writes and reads back text with quotes, unicode and HTML characters", () -> {
            Map<String, Object> o = m("a", "say \"hi\" <b> & ₹", "n", 42L, "d", 2.5, "list", List.of(1L, true));
            Map<String, Object> back = Json.parseObject(Json.write(o));
            eq("say \"hi\" <b> & ₹", back.get("a"));
            eq(42L, back.get("n"));
            eq(2.5, back.get("d"));
            check(!Json.write(o).contains("<"), "< must be escaped");
        });
        test("passwords are hashed with a salt and checked in constant time", () -> {
            String h1 = Passwords.hash("secret123"), h2 = Passwords.hash("secret123");
            check(!h1.equals(h2), "salt must differ");
            check(Passwords.verify("secret123", h1), "right password");
            check(!Passwords.verify("secret124", h1), "wrong password");
            check(Passwords.problem("short1") != null && Passwords.problem("longenough1") == null, "rules");
        });
        test("percentile uses the nearest-rank method", () -> {
            List<Double> xs = new ArrayList<>();
            for (int i = 1; i <= 100; i++) xs.add((double) i);
            eq(50.0, Stats.percentile(xs, 50));
            eq(95.0, Stats.percentile(xs, 95));
            eq(100.0, Stats.percentile(xs, 100));
        });

        System.out.println("\n[broker]");
        test("the same key always goes to the same partition, and keys spread over all partitions", () -> {
            Set<Integer> used = new HashSet<>();
            for (int i = 10001; i < 10060; i++) {
                int a = EventBroker.partitionFor("ORD-" + i, 3);
                eq(a, EventBroker.partitionFor("ORD-" + i, 3));
                used.add(a);
            }
            eq(3, used.size());
        });
        test("offsets count from 0 in each partition and event ids are unique", () -> {
            Clock.fix(START);
            EventBroker b = new EventBroker(null);
            Event e1 = b.publish("orders", "OrderCancelRequested", 1, "ORD-1", "ORD-1", null, "t", m("orderId", "ORD-1", "by", "x"));
            Event e2 = b.publish("orders", "OrderCancelRequested", 1, "ORD-1", "ORD-1", null, "t", m("orderId", "ORD-1", "by", "x"));
            eq(0L, e1.offset);
            eq(1L, e2.offset);
            eq(e1.partition, e2.partition);
            check(!e1.id.equals(e2.id), "ids differ");
            eq(2L, b.end("orders", e1.partition));
            eq(e2.id, b.read("orders", e1.partition, 1).id);
            check(b.read("orders", e1.partition, 2) == null, "end of partition = null");
        });
        test("the schema registry refuses broken events", () -> {
            EventBroker b = new EventBroker(null);
            eq(400, problem(() -> b.publish("orders", "OrderConfirmed", 1, "k", "k", null, "t", m("orderId", "k"))));
            eq(400, problem(() -> b.publish("payments", "OrderCancelRequested", 1, "k", "k", null, "t", m("orderId", "k", "by", "x"))));
            eq(400, problem(() -> b.publish("orders", "OrderShipped", 1, "k", "k", null, "t", m("orderId", "k"))));
            eq(400, problem(() -> b.publish("orders", "OrderPlaced", 2, "k", "k", null, "t",
                    m("orderId", "k", "customerId", "c", "items", List.of(m("sku", "x")), "total", 5L, "city", "Ranchi"))));
            eq(0L, b.total());
        });
        test("version 1 events are upcast to the version 2 shape (old app = cash on delivery)", () -> {
            Event v1 = new Event("E-1", "orders", 0, 0, "OrderPlaced", 1, "k", 0, "k", null, "t", m("orderId", "k"));
            eq("COD", Schemas.upcast(v1).get("paymentMethod"));
            eq(null, v1.payload.get("paymentMethod"));
            eq(2, Schemas.latest("OrderPlaced"));
        });
        test("while the broker is down nothing can be written or read", () -> {
            EventBroker b = new EventBroker(null);
            b.setDown(true);
            try { b.publish("orders", "OrderCancelRequested", 1, "k", "k", null, "t", m("orderId", "k", "by", "x")); throw new AssertionError("no error"); }
            catch (BrokerDown expected) { }
            try { b.read("orders", 0, 0); throw new AssertionError("no error"); } catch (BrokerDown expected) { }
        });
        test("the log is saved as NDJSON files and read back; a half-written last line is skipped", () -> {
            Path dir = Files.createTempDirectory("sanket-log");
            EventBroker b = new EventBroker(dir);
            Event e = null;
            for (int i = 0; i < 5; i++) e = b.publish("orders", "OrderCancelRequested", 1, "ORD-7", "ORD-7", null, "t", m("orderId", "ORD-7", "by", "x" + i));
            Path f = dir.resolve("orders-" + e.partition + ".ndjson");
            Files.writeString(f, "{\"id\":\"E-000099\",\"offset\":9,\"to", java.nio.file.StandardOpenOption.APPEND);
            EventBroker again = new EventBroker(dir);
            again.load();
            eq(5L, again.end("orders", e.partition));
            eq("x4", again.read("orders", e.partition, 4).str("by"));
            eq(5, again.trace("ORD-7").size());
            Event next = again.publish("orders", "OrderCancelRequested", 1, "ORD-7", "ORD-7", null, "t", m("orderId", "ORD-7", "by", "y"));
            eq("E-000006", next.id);
        });

        System.out.println("\n[the order saga]");
        test("happy path: OrderPlaced -> StockReserved -> PaymentAuthorized -> ShipmentCreated -> OrderConfirmed", () -> {
            world(false);
            nextIdWhere(false);
            int before = store.products.get("EAR-BUDS").available();
            Order o = order("UPI", "EAR-BUDS", "2");
            eq("CONFIRMED", status(o.id));
            eq(List.of("OrderPlaced", "StockReserved", "PaymentAuthorized", "ShipmentCreated", "OrderConfirmed"), types(o.id));
            eq(before - 2, store.products.get("EAR-BUDS").available());
            eq("AUTHORIZED", store.payments.get(o.id).state);
            eq(2998L, store.payments.get(o.id).amount);
            eq("CREATED", store.shipments.get(o.id).state);
            eq("Swift Courier", store.shipments.get(o.id).courier);   // Neha lives in Ranchi
            check(store.notifications.values().stream().anyMatch(n -> n.orderId.equals(o.id) && n.kind.equals("OrderConfirmed")), "message sent");
            eq(0L, p.dispatcher.totalLag());
            for (Event e : broker.trace(o.id)) eq(o.id, e.correlationId);
            Event reserved = broker.trace(o.id).get(1);
            eq(broker.trace(o.id).get(0).id, reserved.causationId);
        });
        test("out of stock: StockRejected -> OrderCancelled (nothing held, nobody charged)", () -> {
            world(false);
            Product horse = store.products.get("DK-HORSE");
            horse.stock = 1;
            Order o = order("CARD", "DK-HORSE", "2", "SOH-MUG", "1");
            eq("CANCELLED", status(o.id));
            eq("OUT_OF_STOCK", store.views.get(o.id).reason);
            eq(0, horse.reserved);
            eq(0, store.products.get("SOH-MUG").reserved);
            check(!store.payments.containsKey(o.id) || !"AUTHORIZED".equals(store.payments.get(o.id).state), "no charge");
            check(types(o.id).contains("StockRejected"), "rejected event");
        });
        test("payment declined: PaymentFailed -> OrderCancelled -> StockReleased (compensation)", () -> {
            world(false);
            nextIdWhere(true);
            Order o = order("CARD", "PWR-BANK", "1");
            eq("CANCELLED", status(o.id));
            eq("PAYMENT_DECLINED", store.views.get(o.id).reason);
            eq("RELEASED", store.reservations.get(o.id).state);
            eq(0, store.products.get("PWR-BANK").reserved);
            check(types(o.id).containsAll(List.of("PaymentFailed", "OrderCancelled", "StockReleased")), "compensation events");
        });
        test("customer cancels a confirmed order: refund, stock back, courier booking cancelled", () -> {
            world(false);
            nextIdWhere(false);
            Order o = order("UPI", "STEEL-BTL", "1");
            eq("CONFIRMED", status(o.id));
            p.orders.cancel(u("neha"), o.id);
            p.settle();
            eq("CANCELLED", status(o.id));
            eq("CUSTOMER", store.views.get(o.id).reason);
            eq("REFUNDED", store.payments.get(o.id).state);
            eq("RELEASED", store.reservations.get(o.id).state);
            eq("CANCELLED", store.shipments.get(o.id).state);
            check(types(o.id).containsAll(List.of("OrderCancelRequested", "OrderCancelled", "PaymentRefunded", "StockReleased", "ShipmentCancelled")), "all undone");
            eq(409, problem(() -> p.orders.cancel(u("neha"), o.id)));
        });
        test("dispatch takes stock off the shelf; deliver completes; cancel is refused after dispatch", () -> {
            world(false);
            nextIdWhere(false);
            int shelf = store.products.get("JUTE-BAG").stock;
            Order o = order("COD", "JUTE-BAG", "3");
            p.shipping.dispatch(u("priya"), o.id);
            p.settle();
            eq("DISPATCHED", status(o.id));
            eq(shelf - 3, store.products.get("JUTE-BAG").stock);
            eq(0, store.products.get("JUTE-BAG").reserved);
            eq("COMMITTED", store.reservations.get(o.id).state);
            eq(409, problem(() -> p.orders.cancel(u("neha"), o.id)));
            eq(409, problem(() -> p.shipping.dispatch(u("priya"), o.id)));
            p.shipping.deliver(u("priya"), o.id);
            p.settle();
            eq("DELIVERED", status(o.id));
            eq(403, problem(() -> p.shipping.dispatch(u("neha"), o.id)));
        });
        test("a cancel request that arrives after the parcel left is denied by the saga", () -> {
            world(false);
            nextIdWhere(false);
            Order o = order("UPI", "NOTE-SET", "1");
            store.meta.put("cancelAsked." + o.id, false);
            store.outbox("shipments", "ShipmentDispatched", 1, o.id, "shipping-service", m("orderId", o.id, "awb", store.shipments.get(o.id).awb));
            store.shipments.get(o.id).state = "DISPATCHED";
            p.relay.tick();
            p.dispatcher.drain(50);
            store.outbox("orders", "OrderCancelRequested", 1, o.id, "order-service", m("orderId", o.id, "by", "customer"));
            p.settle();
            check(types(o.id).contains("OrderCancelDenied"), "denied");
            eq("DISPATCHED", status(o.id));
            eq("AUTHORIZED", store.payments.get(o.id).state);
        });
        test("old app (OrderPlaced v1) orders are upcast and paid cash on delivery", () -> {
            world(false);
            Order o = p.orders.placeFor("C-102", items("LED-PACK", "1"), "UPI", "APP_OLD", null, null);
            p.settle();
            eq(1, broker.trace(o.id).get(0).version);
            eq("COD", store.payments.get(o.id).method);
            eq("CONFIRMED", status(o.id));
            eq("COD", store.views.get(o.id).paymentMethod);
        });
        test("order validation: prices come from the catalogue, quantities and methods are checked", () -> {
            world(false);
            Order o = p.orders.place(u("neha"), m("items", List.of(m("sku", "SOH-MUG", "qty", 2), m("sku", "SOH-MUG", "qty", 1)), "paymentMethod", "upi", "price", 1));
            eq(1, o.lines.size());
            eq(3, o.lines.get(0).qty);
            eq(349L * 3, o.total);
            eq("C-101", o.customerId);
            eq(400, problem(() -> p.orders.place(u("neha"), m("items", List.of(), "paymentMethod", "UPI"))));
            eq(400, problem(() -> p.orders.place(u("neha"), m("items", List.of(m("sku", "NOPE", "qty", 1)), "paymentMethod", "UPI"))));
            eq(400, problem(() -> p.orders.place(u("neha"), m("items", List.of(m("sku", "SOH-MUG", "qty", 9)), "paymentMethod", "UPI"))));
            eq(400, problem(() -> p.orders.place(u("neha"), m("items", List.of(m("sku", "SOH-MUG", "qty", 1)), "paymentMethod", "GOLD"))));
        });

        System.out.println("\n[retries, dead letters, idempotency]");
        test("gateway down: retried after 1 s, 2 s, 4 s, then dead-lettered; the order waits at 'stock held'", () -> {
            world(false);
            nextIdWhere(false);
            p.payment.gatewayDown = true;
            Order o = p.orders.placeFor("C-101", items("BACKPACK", "1"), "CARD", "WEB", null, null);
            p.relay.tick();
            long t0 = Clock.millis();
            p.dispatcher.tick(Clock.millis(), 100);
            ConsumerGroup pay = p.dispatcher.group("payment");
            eq(1L, pay.retries);
            check(pay.retrying(), "waiting to retry");
            p.dispatcher.tick(Clock.millis(), 100);
            eq(1L, pay.retries);   // not yet: the 1 s back-off has not passed
            eq(1000L, Dispatcher.backoff(1));
            eq(4000L, Dispatcher.backoff(3));
            p.settle();
            eq(4L, pay.retries);
            eq(1L, pay.deadLettered);
            check(Clock.millis() - t0 >= 7000, "waited 1+2+4 s");
            eq("RESERVED", status(o.id));
            DeadLetter d = store.deadLetters.values().iterator().next();
            eq("payment", d.group);
            eq("StockReserved", d.type);
            eq("OPEN", d.state);
            check(d.error.contains("gateway"), "error kept");
            eq(1L, broker.total("dead-letter"));
            eq(0L, p.dispatcher.lag(pay));     // the group moved on
        });
        test("after the fix, a redrive delivers the dead letter again and the order continues", () -> {
            DeadLetter d = store.deadLetters.values().iterator().next();
            eq(400, problem(() -> p.dispatcher.redrive(u("admin"), d.id)));   // still down: fails, stays OPEN
            eq("OPEN", d.state);
            p.payment.gatewayDown = false;
            p.dispatcher.redrive(u("admin"), d.id);
            p.settle();
            eq("REDRIVEN", d.state);
            eq("CONFIRMED", status(d.correlationId));
            eq(409, problem(() -> p.dispatcher.redrive(u("admin"), d.id)));
        });
        test("a dead letter can be discarded instead", () -> {
            world(false);
            nextIdWhere(false);
            p.payment.gatewayDown = true;
            order("UPI", "BACKPACK", "1");
            DeadLetter d = store.deadLetters.values().iterator().next();
            p.dispatcher.discard(u("admin"), d.id, null);
            eq("DISCARDED", d.state);
            eq(409, problem(() -> p.dispatcher.discard(u("admin"), d.id, null)));
            p.payment.gatewayDown = false;
        });
        test("lost commits: the same events come again, the idempotent consumer skips them (no double charge)", () -> {
            world(false);
            ConsumerGroup pay = p.dispatcher.group("payment");
            pay.loseCommits = 2;
            nextIdWhere(false);
            Order a = order("UPI", "PHONE-CVR", "1");
            nextIdWhere(false);
            Order b = order("CARD", "PHONE-CVR", "1");
            eq(0, pay.loseCommits);
            eq(2L, pay.lostCommits);
            check(pay.duplicates >= 2, "duplicates skipped: " + pay.duplicates);
            long authorized = 0;
            for (Event e : broker.since(0)) if (e.type.equals("PaymentAuthorized")) authorized++;
            eq(2L, authorized);
            eq("CONFIRMED", status(a.id));
            eq("CONFIRMED", status(b.id));
        });
        test("replaying a service from offset 0 does nothing twice (it checks its own records)", () -> {
            long events = broker.total();
            long payments = store.payments.size();
            int reserved = store.products.get("PHONE-CVR").reserved;
            p.dispatcher.reset("payment", "earliest");
            p.dispatcher.reset("inventory", "earliest");
            check(p.dispatcher.lag(p.dispatcher.group("payment")) > 0, "lag after reset");
            p.settle();
            eq(events, broker.total());
            eq(payments, (long) store.payments.size());
            eq(reserved, store.products.get("PHONE-CVR").reserved);
            eq(0L, p.dispatcher.totalLag());
        });
        test("the read model can be thrown away and rebuilt from the log with the same result", () -> {
            world(true);
            Map<String, String> before = new java.util.HashMap<>();
            for (OrderView v : store.views.values()) before.put(v.orderId, v.status);
            p.projection.rebuild(u("admin"));
            eq(0, store.views.size());
            p.settle();
            eq(before.size(), store.views.size());
            for (var e : before.entrySet()) eq(e.getValue(), status(e.getKey()));
        });
        test("the read model gives the same status whatever order the events arrive in", () -> {
            OrderView v = new OrderView();
            v.stages.put("paid", 3L);
            v.stages.put("confirmed", 5L);
            eq("CONFIRMED", com.cecil.sanket.service.OrderProjection.status(v));
            v.stages.put("reserved", 2L);
            v.stages.put("placed", 1L);
            eq("CONFIRMED", com.cecil.sanket.service.OrderProjection.status(v));
            v.stages.put("cancelRequested", 6L);
            eq("CANCEL_REQUESTED", com.cecil.sanket.service.OrderProjection.status(v));
            v.stages.put("cancelled", 7L);
            eq("CANCELLED", com.cecil.sanket.service.OrderProjection.status(v));
        });
        test("cancel seen before StockReserved: payment leaves a 'voided' marker and never charges", () -> {
            world(false);
            nextIdWhere(false);
            ConsumerGroup pay = p.dispatcher.group("payment");
            Order o = p.orders.placeFor("C-101", items("MAHUA-HONEY", "1"), "UPI", "WEB", null, null);
            p.dispatcher.pause("payment", true, "test");
            p.relay.tick();
            p.dispatcher.drain(50);                 // inventory reserves, payment is stopped
            p.orders.cancel(null, o.id);
            p.relay.tick();
            p.dispatcher.drain(50);                 // saga cancels, inventory releases
            // payment reads the orders topic (OrderCancelled) before the inventory topic (StockReserved)
            p.dispatcher.pause("payment", false, null);
            p.settle();
            eq("VOIDED", store.payments.get(o.id).state);
            check(!types(o.id).contains("PaymentAuthorized"), "never charged");
            eq("CANCELLED", status(o.id));
            eq(0L, p.dispatcher.lag(pay));
        });

        System.out.println("\n[outbox, stopped and slow consumers, poison]");
        test("broker down: orders are still accepted, they wait in the outbox and go out when it is back", () -> {
            world(false);
            broker.setDown(true);
            Order o = p.orders.place(u("neha"), m("items", List.of(m("sku", "SOH-MUG", "qty", 1)), "paymentMethod", "COD"));
            eq(0, p.relay.tick());
            eq(1L, p.relay.pending());
            eq(0, p.dispatcher.tick(Clock.millis(), 10));
            OutboxEntryCheck.lastError(store);
            broker.setDown(false);
            p.settle();
            eq(0L, p.relay.pending());
            eq("CONFIRMED", status(o.id));
        });
        test("a stopped consumer builds up lag and catches up from its committed offset when restarted", () -> {
            world(false);
            p.dispatcher.pause("shipping", true, "crash");
            for (int i = 0; i < 3; i++) { nextIdWhere(false); order("UPI", "LED-PACK", "1"); }
            ConsumerGroup ship = p.dispatcher.group("shipping");
            check(p.dispatcher.lag(ship) >= 3, "lag " + p.dispatcher.lag(ship));
            eq("DANGER", p.dispatcher.aspect(ship, Clock.millis()));
            check(store.views.values().stream().allMatch(v -> v.status.equals("PAID")), "waiting at paid");
            p.dispatcher.pause("shipping", false, null);
            p.settle();
            eq(0L, p.dispatcher.lag(ship));
            check(store.views.values().stream().allMatch(v -> v.status.equals("CONFIRMED")), "all confirmed");
            eq("CLEAR", p.dispatcher.aspect(ship, Clock.millis()));
        });
        test("a slow consumer handles one event per gap and shows a caution signal", () -> {
            world(false);
            ConsumerGroup inv = p.dispatcher.group("inventory");
            inv.slowUntil = Clock.millis() + 60_000;
            inv.slowGapMs = 3000;
            for (int i = 0; i < 3; i++) p.orders.placeFor("C-10" + (i + 3), items("NOTE-SET", "1"), "UPI", "WEB", null, null);
            p.relay.tick();
            p.dispatcher.tick(Clock.millis(), 100);
            eq(1L, inv.processed);
            p.dispatcher.tick(Clock.millis(), 100);
            eq(1L, inv.processed);
            eq("CAUTION", p.dispatcher.aspect(inv, Clock.millis()));
            Clock.advanceMillis(3001);
            p.dispatcher.tick(Clock.millis(), 100);
            eq(2L, inv.processed);
        });
        test("a poison message: the notifier dead-letters it, everyone else goes on; after the fix a redrive works", () -> {
            world(false);
            nextIdWhere(false);
            Order o = p.orders.placeFor("C-101", items("SOH-MUG", "1"), "UPI", "WEB", "Happy {{name} day", null);
            p.settle();
            eq("CONFIRMED", status(o.id));
            ConsumerGroup n = p.dispatcher.group("notifier");
            eq(1L, n.deadLettered);
            DeadLetter d = store.deadLetters.values().iterator().next();
            eq("OrderPlaced", d.type);
            check(d.error.contains("Template error"), d.error);
            check(store.notifications.values().stream().anyMatch(x -> x.orderId.equals(o.id) && x.kind.equals("OrderConfirmed")), "later messages still sent");
            eq(400, problem(() -> p.dispatcher.redrive(u("admin"), d.id)));
            store.setFlag("notifier.safeTemplates", true);
            p.dispatcher.redrive(u("admin"), d.id);
            eq("REDRIVEN", d.state);
            check(store.notifications.values().stream().anyMatch(x -> x.orderId.equals(o.id) && x.kind.equals("OrderPlaced") && x.text.contains("{ {")), "rendered safely");
        });
        test("the notifier retries when it has not seen the customer yet", () -> {
            world(false);
            Event e = broker.publish("orders", "OrderConfirmed", 1, "ORD-9", "ORD-9", null, "t", m("orderId", "ORD-9", "courier", "c", "awb", "a"));
            p.dispatcher.tick(Clock.millis(), 100);
            ConsumerGroup n = p.dispatcher.group("notifier");
            eq(1L, n.retries);
            check(n.lastError.contains("not known"), n.lastError);
            eq(e.offset, n.offset("orders", e.partition));
        });

        System.out.println("\n[demo history, analytics, simulator]");
        test("the demo history: 3.5 days played through the real system, with the planted problems", () -> {
            world(true);
            check(store.views.size() > 350, "orders " + store.views.size());
            check(broker.total() > 2500, "events " + broker.total());
            eq(0L, p.dispatcher.totalLag());
            eq(0L, p.relay.pending());
            long openPay = store.deadLetters.values().stream().filter(d -> d.group.equals("payment") && d.state.equals("OPEN")).count();
            long openNote = store.deadLetters.values().stream().filter(d -> d.group.equals("notifier") && d.state.equals("OPEN")).count();
            eq(3L, openPay);
            eq(1L, openNote);
            check(store.deadLetters.values().stream().anyMatch(d -> d.state.equals("REDRIVEN")), "some were redriven");
            check(store.views.values().stream().anyMatch(v -> "OUT_OF_STOCK".equals(v.reason)), "flash sale sold out");
            check(store.orders.values().stream().anyMatch(o -> "APP_OLD".equals(o.channel)), "old app orders");
            check(Clock.millis() == START.toEpochMilli(), "clock restored");
            for (Event e : broker.since(0)) check(e.at <= START.toEpochMilli(), "no events from the future");
        });
        test("overview and analytics: stuck orders, latency percentiles and outcomes", () -> {
            Map<String, Object> ov = p.analytics.overview();
            @SuppressWarnings("unchecked") Map<String, Object> k = (Map<String, Object>) ov.get("kpi");
            eq(3L, k.get("stuck"));
            eq(4L, k.get("openDeadLetters"));
            double p95 = ((Number) k.get("p95")).doubleValue();
            check(p95 > 100 && p95 < 2000, "p95 " + p95);
            Map<String, Object> a = p.analytics.analytics();
            @SuppressWarnings("unchecked") Map<String, Object> lat = (Map<String, Object>) a.get("latency");
            check(((Number) lat.get("p50")).doubleValue() <= ((Number) lat.get("p99")).doubleValue(), "p50 <= p99");
            eq(72, ((List<?>) a.get("hours")).size());
            String any = store.views.keySet().iterator().next();
            List<Object> trace = p.analytics.trace(any);
            check(!trace.isEmpty(), "trace");
        });
        test("the simulator places orders, and only the admin can cause faults", () -> {
            world(false);
            Simulator sim = p.sim;
            Order o = sim.randomOrder();
            check(store.orders.containsKey(o.id), "placed");
            eq(403, problem(() -> sim.inject(u("priya"), "BROKER_DOWN", null, 1, 0)));
            sim.inject(u("admin"), "BROKER_DOWN", null, 1, 0);
            check(broker.isDown(), "down");
            Clock.advanceMillis(61_000);
            sim.tick(Clock.millis());
            check(!broker.isDown(), "back after a minute");
            sim.inject(u("admin"), "CONSUMER_CRASH", "notifier", 1, 0);
            check(p.dispatcher.group("notifier").paused, "crashed");
            sim.clear(u("admin"), "CONSUMER_CRASH:notifier");
            check(!p.dispatcher.group("notifier").paused, "restarted");
            sim.inject(u("admin"), "FLASH_SALE", null, 2, 0);
            int flash = 0;
            for (int i = 0; i < 30; i++) for (Order.Line l : sim.randomOrder().lines) if (l.sku.equals(Simulator.FLASH_SKU)) flash++;
            check(flash > 12, "flash sku ordered " + flash);
        });

        System.out.println("\n[accounts and the web server]");
        test("5 wrong passwords lock the account; the message never says whether the user exists", () -> {
            world(false);
            for (int i = 0; i < 5; i++) eq(401, problem(() -> p.auth.login("neha", "nope")));
            eq(429, problem(() -> p.auth.login("neha", "customer123")));
            eq(401, problem(() -> p.auth.login("ghost", "x")));
            Clock.advanceMillis(5 * 60_000 + 1);
            check(p.auth.userFor(p.auth.login("neha", "customer123")) != null, "unlocked later");
        });
        test("customers see only their own orders", () -> {
            Order mine = p.orders.placeFor("C-101", items("SOH-MUG", "1"), "UPI", "WEB", null, null);
            Order other = p.orders.placeFor("C-102", items("SOH-MUG", "1"), "UPI", "WEB", null, null);
            p.settle();
            eq(404, problem(() -> p.analytics.orderDetail(u("neha"), other.id)));
            eq(404, problem(() -> p.orders.cancel(u("neha"), other.id)));
            check(p.analytics.orderDetail(u("neha"), mine.id).get("payment") == null, "service records hidden from customers");
            check(p.analytics.orderDetail(u("priya"), other.id).get("payment") != null, "staff see them");
            @SuppressWarnings("unchecked") List<Map<String, Object>> list = (List<Map<String, Object>>) p.analytics.orders(u("neha"), Map.of()).get("orders");
            check(list.stream().allMatch(x -> x.get("customer").equals("Neha Kumari")), "own only");
        });
        test("web server: sign-in, CSRF header, roles, static files and placing an order over HTTP", () -> {
            world(false);
            Clock.fix(null);
            Path front = Files.createTempDirectory("sanket-front");
            Files.writeString(front.resolve("index.html"), "<!doctype html><title>t</title>");
            ApiServer server = new ApiServer(front, p);
            server.start(0);
            try {
                String base = "http://127.0.0.1:" + server.port();
                HttpClient c = HttpClient.newHttpClient();
                eq(200, c.send(HttpRequest.newBuilder(URI.create(base + "/")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                eq(404, c.send(HttpRequest.newBuilder(URI.create(base + "/static/..%2Fsecret")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                eq(401, c.send(HttpRequest.newBuilder(URI.create(base + "/api/orders")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                String login = "{\"username\":\"neha\",\"password\":\"customer123\"}";
                eq(403, c.send(HttpRequest.newBuilder(URI.create(base + "/api/login")).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(login)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(base + "/api/login")).header("Content-Type", "application/json").header("X-Sanket", "1")
                        .POST(HttpRequest.BodyPublishers.ofString(login)).build(), HttpResponse.BodyHandlers.ofString());
                eq(200, r.statusCode());
                String cookie = r.headers().firstValue("Set-Cookie").orElse("").split(";")[0];
                check(r.headers().firstValue("Set-Cookie").orElse("").contains("HttpOnly"), "HttpOnly cookie");
                eq(403, c.send(HttpRequest.newBuilder(URI.create(base + "/api/overview")).header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                HttpResponse<String> placed = c.send(HttpRequest.newBuilder(URI.create(base + "/api/orders")).header("Content-Type", "application/json").header("X-Sanket", "1").header("Cookie", cookie)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"items\":[{\"sku\":\"SOH-MUG\",\"qty\":1}],\"paymentMethod\":\"COD\"}")).build(), HttpResponse.BodyHandlers.ofString());
                eq(200, placed.statusCode());
                check(placed.body().contains("ORD-"), placed.body());
                check(r.headers().firstValue("Content-Security-Policy").isPresent(), "CSP header");
                eq(1L, p.relay.pending());
            } finally {
                server.stop();
                Clock.fix(START);
            }
        });

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        for (String f : failures) System.out.println("  - " + f);
        Clock.fix(null);
        System.exit(failed == 0 ? 0 : 1);
    }

    /** Small helper used by the outbox test. */
    static final class OutboxEntryCheck {
        static void lastError(DataStore s) {
            var e = s.outbox.values().iterator().next();
            check(e.attempts >= 1 && e.lastError != null && e.lastError.contains("broker"), "outbox row remembers the failure: " + e.lastError);
        }
    }
}
