package com.cecil.sanket.sim;

import com.cecil.sanket.broker.ConsumerGroup;
import com.cecil.sanket.broker.Dispatcher;
import com.cecil.sanket.broker.EventBroker;
import com.cecil.sanket.model.Customer;
import com.cecil.sanket.model.OrderView;
import com.cecil.sanket.model.Product;
import com.cecil.sanket.model.Role;
import com.cecil.sanket.model.User;
import com.cecil.sanket.service.InventoryService;
import com.cecil.sanket.service.OrderService;
import com.cecil.sanket.service.PaymentService;
import com.cecil.sanket.service.Problem;
import com.cecil.sanket.service.ShippingService;
import com.cecil.sanket.store.DataStore;
import com.cecil.sanket.util.Clock;
import com.cecil.sanket.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Plays the outside world so the demo is alive without real shoppers:
 *   - shoppers place orders (about 6 a minute, more in a flash sale)
 *   - the warehouse dispatches confirmed orders and the courier delivers them
 *   - the supplier brings new stock when a product runs low
 * It also lets the admin cause FAULTS, to see how an event-driven system copes with them.
 * Runs every 2 seconds.
 */
public class Simulator {
    public static final String FLASH_SKU = "DK-HORSE";

    /** The faults the admin can cause. */
    public static final List<Map<String, Object>> FAULTS = List.of(
            Json.obj("kind", "PAYMENT_OUTAGE", "label", "Payment gateway down", "minutes", true,
                    "explain", "The payment service cannot reach the bank. Each StockReserved is retried after 1 s, 2 s and 4 s, then parked in the dead-letter list. Orders stay at 'stock held'."),
            Json.obj("kind", "BROKER_DOWN", "label", "Broker down", "minutes", true,
                    "explain", "Nothing can be written to or read from the log. Customers can still place orders: they wait in the outbox and are sent when the broker is back."),
            Json.obj("kind", "SLOW_CONSUMER", "label", "Slow consumer", "minutes", true, "group", true,
                    "explain", "The chosen group handles only one event every 3 seconds. Its lag grows (yellow signal) and catches up afterwards."),
            Json.obj("kind", "CONSUMER_CRASH", "label", "Consumer crash", "group", true,
                    "explain", "The chosen group stops (red signal). Events are not lost: they wait in the log. Press Restart and it continues from its committed offset."),
            Json.obj("kind", "DUPLICATES", "label", "Duplicate delivery", "group", true, "count", true,
                    "explain", "The next events are handled but their offset commit is 'lost', so the broker delivers them again. The idempotent consumer skips them - no double charge."),
            Json.obj("kind", "POISON", "label", "Poison message",
                    "explain", "Places an order whose gift note breaks the notifier's template code. Every try fails, so after 4 tries it goes to the dead-letter list and the notifier moves on."),
            Json.obj("kind", "FLASH_SALE", "label", "Flash sale", "minutes", true,
                    "explain", "6× more orders, most of them for the Dokra brass horse. When it runs out, inventory answers StockRejected and the saga cancels those orders."));

    public static final class Fault {
        public final String key, kind, group, label;
        public final long until;
        Fault(String key, String kind, String group, String label, long until) {
            this.key = key; this.kind = kind; this.group = group; this.label = label; this.until = until;
        }
    }

    private final DataStore store;
    private final EventBroker broker;
    private final Dispatcher dispatcher;
    private final OrderService orders;
    private final InventoryService inventory;
    private final PaymentService payment;
    private final ShippingService shipping;
    private final Random rnd;
    private final Map<String, Fault> faults = new LinkedHashMap<>();
    private volatile long lastTick;

    public Simulator(DataStore store, EventBroker broker, Dispatcher dispatcher, OrderService orders, InventoryService inventory,
                     PaymentService payment, ShippingService shipping, long seed) {
        this.store = store; this.broker = broker; this.dispatcher = dispatcher; this.orders = orders;
        this.inventory = inventory; this.payment = payment; this.shipping = shipping;
        this.rnd = new Random(seed);
    }

    // ------------------------------------------------------------------ settings

    public int ordersPerMinute() { synchronized (store) { return (int) Json.num(store.meta, "sim.ordersPerMin", 6); } }

    public boolean autoShip() { synchronized (store) { return Json.bool(store.meta, "sim.autoShip", true); } }

    public int oldAppPct() { synchronized (store) { return (int) Json.num(store.meta, "sim.oldAppPct", 10); } }

    public Map<String, Object> settings(User actor, Map<String, Object> body) {
        admin(actor);
        synchronized (store) {
            List<String> changes = new ArrayList<>();
            if (body.containsKey("ordersPerMin")) {
                long v = Math.max(0, Math.min(60, Json.num(body, "ordersPerMin", 6)));
                store.meta.put("sim.ordersPerMin", v);
                changes.add("orders/min " + v);
            }
            if (body.containsKey("declinePct")) {
                long v = Math.max(0, Math.min(50, Json.num(body, "declinePct", 5)));
                store.meta.put("sim.declinePct", v);
                changes.add("declines " + v + "%");
            }
            if (body.containsKey("oldAppPct")) {
                long v = Math.max(0, Math.min(100, Json.num(body, "oldAppPct", 10)));
                store.meta.put("sim.oldAppPct", v);
                changes.add("old app " + v + "%");
            }
            if (body.containsKey("autoShip")) {
                boolean v = Json.bool(body, "autoShip", true);
                store.meta.put("sim.autoShip", v);
                changes.add("auto ship " + (v ? "on" : "off"));
            }
            if (body.containsKey("safeTemplates")) {
                boolean v = Json.bool(body, "safeTemplates", false);
                store.setFlag("notifier.safeTemplates", v);
                changes.add(v ? "notifier template fix deployed" : "notifier template fix rolled back");
            }
            if (changes.isEmpty()) throw Problem.bad("Nothing to change.");
            store.log(actor.id, "sim.settings", "simulator", String.join(", ", changes));
            store.markDirty("meta", "audit");
        }
        return status();
    }

    // ------------------------------------------------------------------ faults

    public Map<String, Object> inject(User actor, String kind, String group, int minutes, int count) {
        admin(actor);
        long now = Clock.millis();
        minutes = Math.max(1, Math.min(30, minutes));
        long until = now + minutes * 60_000L;
        String label;
        String key = kind;
        switch (kind == null ? "" : kind) {
            case "PAYMENT_OUTAGE" -> { payment.gatewayDown = true; label = "Payment gateway down"; }
            case "BROKER_DOWN" -> { broker.setDown(true); label = "Broker down"; }
            case "SLOW_CONSUMER" -> {
                ConsumerGroup g = dispatcher.need(group);
                g.slowUntil = until;
                g.slowGapMs = 3000;
                key = kind + ":" + g.name;
                label = "Slow consumer: " + g.name;
            }
            case "CONSUMER_CRASH" -> {
                ConsumerGroup g = dispatcher.need(group);
                dispatcher.pause(g.name, true, "Crashed (simulated fault)");
                key = kind + ":" + g.name;
                label = "Crashed: " + g.name;
                until = 0;   // stays down until restarted
            }
            case "DUPLICATES" -> {
                ConsumerGroup g = dispatcher.need(group == null || group.isBlank() ? "payment" : group);
                g.loseCommits = Math.max(1, Math.min(20, count));
                label = g.loseCommits + " lost commits: " + g.name;
                key = kind + ":" + g.name;
                until = 0;
            }
            case "POISON" -> {
                Customer c = randomCustomer();
                var o = orders.placeFor(c.id, List.<String[]>of(new String[]{"SOH-MUG", "1"}), "UPI", "WEB",
                        "Happy {{name} birthday, Didi!", actor.id);
                store.log(actor.id, "sim.fault", "POISON", "order " + o.id + " with a broken gift note");
                store.markDirty("audit");
                return Json.obj("ok", true, "orderId", o.id, "status", status());
            }
            case "FLASH_SALE" -> label = "Flash sale on the Dokra brass horse";
            default -> throw Problem.bad("Unknown fault.");
        }
        synchronized (faults) { faults.put(key, new Fault(key, kind, group, label, until)); }
        synchronized (store) {
            store.log(actor.id, "sim.fault", kind, label + (until > 0 ? " for " + minutes + " min" : ""));
            store.markDirty("audit");
        }
        return status();
    }

    public Map<String, Object> clear(User actor, String key) {
        admin(actor);
        Fault f;
        synchronized (faults) { f = faults.get(key); }
        if (f == null) throw Problem.notFound("Fault");
        end(f);
        synchronized (store) {
            store.log(actor.id, "sim.clear", f.kind, f.label);
            store.markDirty("audit");
        }
        return status();
    }

    private void end(Fault f) {
        switch (f.kind) {
            case "PAYMENT_OUTAGE" -> payment.gatewayDown = false;
            case "BROKER_DOWN" -> broker.setDown(false);
            case "SLOW_CONSUMER" -> { ConsumerGroup g = dispatcher.group(f.group); if (g != null) g.slowUntil = 0; }
            case "CONSUMER_CRASH" -> dispatcher.pause(f.group, false, null);
            case "DUPLICATES" -> { ConsumerGroup g = dispatcher.group(f.group == null || f.group.isBlank() ? "payment" : f.group); if (g != null) g.loseCommits = 0; }
            default -> { }
        }
        synchronized (faults) { faults.remove(f.key); }
    }

    /** A consumer was restarted from the Consumers page: forget its crash fault. */
    public void forgetCrash(String group) {
        synchronized (faults) { faults.remove("CONSUMER_CRASH:" + group); }
    }

    /** Used by the demo-history builder to play a flash sale in the past. */
    public volatile boolean historyFlash;

    public boolean flashSale() {
        if (historyFlash) return true;
        synchronized (faults) { return faults.containsKey("FLASH_SALE"); }
    }

    public List<Object> activeFaults() {
        long now = Clock.millis();
        List<Object> out = new ArrayList<>();
        synchronized (faults) {
            for (Fault f : faults.values()) {
                String extra = null;
                if (f.kind.equals("DUPLICATES")) {
                    ConsumerGroup g = dispatcher.group(f.group == null || f.group.isBlank() ? "payment" : f.group);
                    extra = g == null ? null : g.loseCommits + " left";
                }
                out.add(Json.obj("key", f.key, "kind", f.kind, "label", f.label, "group", f.group,
                        "secondsLeft", f.until == 0 ? null : Math.max(0, (f.until - now) / 1000), "extra", extra));
            }
        }
        return out;
    }

    public Map<String, Object> status() {
        synchronized (store) {
            return Json.obj("faults", activeFaults(), "catalogue", FAULTS,
                    "settings", Json.obj("ordersPerMin", ordersPerMinute(), "declinePct", Json.num(store.meta, "sim.declinePct", 5),
                            "oldAppPct", oldAppPct(), "autoShip", autoShip(), "safeTemplates", store.flag("notifier.safeTemplates")),
                    "lastTick", lastTick);
        }
    }

    // ------------------------------------------------------------------ the 2-second tick

    public void tick(long now) {
        lastTick = now;
        List<Fault> expired = new ArrayList<>();
        synchronized (faults) {
            for (Fault f : faults.values()) {
                if (f.until > 0 && now >= f.until) expired.add(f);
                if (f.kind.equals("DUPLICATES")) {
                    ConsumerGroup g = dispatcher.group(f.group == null || f.group.isBlank() ? "payment" : f.group);
                    if (g == null || g.loseCommits == 0) expired.add(f);
                }
            }
        }
        for (Fault f : expired) end(f);

        // shoppers
        double perTick = ordersPerMinute() * 2 / 60.0 * (flashSale() ? 6 : 1);
        int n = (int) perTick + (rnd.nextDouble() < perTick - (int) perTick ? 1 : 0);
        for (int i = 0; i < n; i++) {
            try { randomOrder(); } catch (Problem ignored) { }
        }
        // warehouse and courier
        if (autoShip()) {
            List<String> toDispatch = new ArrayList<>(), toDeliver = new ArrayList<>();
            synchronized (store) {
                for (OrderView v : store.views.values()) {
                    // at most 2 parcels leave and 2 arrive per tick, like a real warehouse and courier
                    if (toDispatch.size() < 2 && "CONFIRMED".equals(v.status) && now - v.stage("confirmed") > 40_000 && rnd.nextDouble() < 0.3) toDispatch.add(v.orderId);
                    if (toDeliver.size() < 2 && "DISPATCHED".equals(v.status) && now - v.stage("dispatched") > 90_000 && rnd.nextDouble() < 0.05) toDeliver.add(v.orderId);
                }
            }
            for (String id : toDispatch) try { shipping.dispatch(null, id); } catch (Problem ignored) { }
            for (String id : toDeliver) try { shipping.deliver(null, id); } catch (Problem ignored) { }
        }
        // supplier
        List<String> low = new ArrayList<>();
        synchronized (store) {
            for (Product p : store.products.values()) {
                if (flashSale() && p.sku.equals(FLASH_SKU)) continue;
                if (p.available() <= p.reorderLevel && rnd.nextDouble() < 0.06) low.add(p.sku);
            }
        }
        for (String sku : low) inventory.restock(null, sku, 25 + rnd.nextInt(20));
    }

    // ------------------------------------------------------------------ made-up shoppers

    /** How popular each product is (higher = ordered more often). */
    public static final Map<String, Integer> POPULAR = new LinkedHashMap<>();
    static {
        POPULAR.put("EAR-BUDS", 9); POPULAR.put("PWR-BANK", 8); POPULAR.put("USB-C-CHG", 8); POPULAR.put("STEEL-BTL", 7);
        POPULAR.put("SOH-MUG", 6); POPULAR.put("TUSSAR-STOLE", 4); POPULAR.put(FLASH_SKU, 3); POPULAR.put("BAMBOO-LAMP", 4);
        POPULAR.put("COTTON-KURTA", 6); POPULAR.put("LAPTOP-SLV", 4); POPULAR.put("PHONE-CVR", 7); POPULAR.put("NOTE-SET", 6);
        POPULAR.put("LED-PACK", 5); POPULAR.put("BACKPACK", 4); POPULAR.put("SAL-PLATES", 3); POPULAR.put("MAHUA-HONEY", 4);
        POPULAR.put("DESK-FAN", 3); POPULAR.put("JUTE-BAG", 5);
    }

    private static final String[] GIFT_NOTES = {"Happy birthday Didi!", "For Papa, with love", "Congratulations on the new job!",
            "Happy Diwali from all of us", "Get well soon, Nani"};

    public Customer randomCustomer() {
        synchronized (store) {
            List<Customer> all = new ArrayList<>(store.customers.values());
            return all.get(rnd.nextInt(all.size()));
        }
    }

    public String randomSku() {
        int total = POPULAR.values().stream().mapToInt(Integer::intValue).sum();
        int r = rnd.nextInt(total);
        for (var e : POPULAR.entrySet()) { r -= e.getValue(); if (r < 0) return e.getKey(); }
        return "EAR-BUDS";
    }

    public com.cecil.sanket.model.Order randomOrder() {
        Customer c = randomCustomer();
        List<String[]> items = new ArrayList<>();
        java.util.Set<String> used = new java.util.HashSet<>();
        if (flashSale() && rnd.nextDouble() < 0.7) { items.add(new String[]{FLASH_SKU, String.valueOf(1 + rnd.nextInt(2))}); used.add(FLASH_SKU); }
        double r = rnd.nextDouble();
        int lines = items.isEmpty() ? (r < 0.6 ? 1 : r < 0.9 ? 2 : 3) : (r < 0.7 ? 0 : 1);
        for (int i = 0; i < lines; i++) {
            String sku = randomSku();
            if (!used.add(sku)) continue;
            items.add(new String[]{sku, rnd.nextDouble() < 0.8 ? "1" : "2"});
        }
        if (items.isEmpty()) items.add(new String[]{"EAR-BUDS", "1"});
        double m = rnd.nextDouble();
        String method = m < 0.45 ? "UPI" : m < 0.70 ? "CARD" : m < 0.80 ? "WALLET" : "COD";
        double ch = rnd.nextDouble() * 100;
        String channel = ch < oldAppPct() ? "APP_OLD" : ch < 55 ? "APP" : "WEB";
        String note = rnd.nextDouble() < 0.07 ? GIFT_NOTES[rnd.nextInt(GIFT_NOTES.length)] : null;
        return orders.placeFor(c.id, items, method, channel, note, null);
    }

    public Random random() { return rnd; }

    private static void admin(User u) {
        if (u == null || u.role != Role.ADMIN) throw Problem.forbidden("Only the platform admin can use the simulator.");
    }
}
