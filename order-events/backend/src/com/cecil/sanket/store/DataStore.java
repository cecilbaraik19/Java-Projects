package com.cecil.sanket.store;

import com.cecil.sanket.model.AuditEntry;
import com.cecil.sanket.model.Customer;
import com.cecil.sanket.model.DeadLetter;
import com.cecil.sanket.model.Notification;
import com.cecil.sanket.model.Order;
import com.cecil.sanket.model.OrderView;
import com.cecil.sanket.model.OutboxEntry;
import com.cecil.sanket.model.Payment;
import com.cecil.sanket.model.Product;
import com.cecil.sanket.model.Reservation;
import com.cecil.sanket.model.Shipment;
import com.cecil.sanket.model.User;
import com.cecil.sanket.util.Clock;
import com.cecil.sanket.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Keeps every service's own records in memory and saves each collection as a JSON file in the data folder.
 * (The event log itself is kept by the EventBroker in data/log.)
 *
 * In a real system each service would have its OWN database. Here they share this one object, but every service
 * only touches its own collections:
 *   order service      orders, outbox, sagas
 *   inventory service  products, reservations
 *   payment service    payments
 *   shipping service   shipments
 *   notifier           notifications, contacts
 *   order-view         views (the read model, can be rebuilt from the log)
 *   broker             groups (offsets), deadLetters
 *
 * Changes mark collections "dirty"; a background job writes the dirty ones every second (flush).
 * Saving writes a temporary file first and then renames it, so a crash never leaves a half-written file.
 */
public class DataStore {
    public static final int MAX_AUDIT = 5000;
    public static final int MAX_SENT_OUTBOX = 3000;   // sent outbox rows are kept for a while so you can see them

    private final Path dir;   // null = memory only (tests)

    public final Map<String, User> users = new LinkedHashMap<>();
    public final Map<String, Customer> customers = new LinkedHashMap<>();
    public final Map<String, Product> products = new LinkedHashMap<>();
    public final Map<String, Order> orders = new LinkedHashMap<>();
    public final Map<Long, OutboxEntry> outbox = new LinkedHashMap<>();
    public final Map<String, String> sagas = new LinkedHashMap<>();          // orderId -> saga stage
    public final Map<String, Reservation> reservations = new LinkedHashMap<>();
    public final Map<String, Payment> payments = new LinkedHashMap<>();       // by orderId
    public final Map<String, Shipment> shipments = new LinkedHashMap<>();     // by orderId
    public final Map<String, Notification> notifications = new LinkedHashMap<>();
    public final Map<String, String> contacts = new LinkedHashMap<>();       // notifier: orderId -> customerId
    public final Map<String, OrderView> views = new LinkedHashMap<>();
    public final Map<String, DeadLetter> deadLetters = new LinkedHashMap<>();
    public final Map<String, Object> groups = new LinkedHashMap<>();         // consumer group name -> saved state
    public final List<AuditEntry> audit = new ArrayList<>();
    /** Id counters and small settings. */
    public final Map<String, Object> meta = new LinkedHashMap<>();

    private final Set<String> dirty = new LinkedHashSet<>();
    /** True while the demo history is built: nothing is written until saveAll() at the end (much faster). */
    public volatile boolean batch;

    public static final List<String> ALL = List.of("users", "customers", "products", "orders", "outbox", "sagas",
            "reservations", "payments", "shipments", "notifications", "views", "deadLetters", "groups", "audit", "contacts");

    public DataStore(Path dir) { this.dir = dir; }

    public Path dir() { return dir; }

    /** True when there was nothing saved yet (first start). */
    @SuppressWarnings("unchecked")
    public synchronized boolean load() throws IOException {
        if (dir == null) return true;
        Files.createDirectories(dir);
        if (!Files.exists(dir.resolve("users.json"))) return true;
        readInto("users.json", users, User::fromJson, u -> u.id);
        readInto("customers.json", customers, Customer::fromJson, c -> c.id);
        readInto("products.json", products, Product::fromJson, p -> p.sku);
        readInto("orders.json", orders, Order::fromJson, o -> o.id);
        for (Object o : readList("outbox.json")) { OutboxEntry e = OutboxEntry.fromJson(asMap(o)); outbox.put(e.seq, e); }
        readInto("reservations.json", reservations, Reservation::fromJson, r -> r.orderId);
        readInto("payments.json", payments, Payment::fromJson, p -> p.orderId);
        readInto("shipments.json", shipments, Shipment::fromJson, s -> s.orderId);
        readInto("notifications.json", notifications, Notification::fromJson, n -> n.id);
        readInto("views.json", views, OrderView::fromJson, v -> v.orderId);
        readInto("deadLetters.json", deadLetters, DeadLetter::fromJson, d -> d.id);
        for (Object o : readList("audit.json")) audit.add(AuditEntry.fromJson(asMap(o)));
        meta.putAll(readMap("meta.json"));
        groups.putAll(readMap("groups.json"));
        for (Map.Entry<String, Object> e : readMap("sagas.json").entrySet()) sagas.put(e.getKey(), String.valueOf(e.getValue()));
        for (Map.Entry<String, Object> e : readMap("contacts.json").entrySet()) contacts.put(e.getKey(), String.valueOf(e.getValue()));
        return users.isEmpty();
    }

    // ------------------------------------------------------------------ ids, log, outbox

    /** Next id for a kind of record, e.g. nextId("ORD", 10001) -> "ORD-10001", then "ORD-10002" ... */
    public synchronized String nextId(String prefix, long start) {
        long n = Json.num(meta, "next." + prefix, start);
        meta.put("next." + prefix, n + 1);
        return prefix + "-" + n;
    }

    public synchronized void log(String userId, String action, String target, String detail) {
        audit.add(new AuditEntry(Clock.millis(), userId, action, target, detail));
        while (audit.size() > MAX_AUDIT) audit.remove(0);
        dirty.add("audit");
    }

    /**
     * Adds a row to the transactional outbox. Call this inside the same synchronized block as the change itself,
     * so "the change" and "the event about the change" are saved together (or not at all).
     */
    public synchronized OutboxEntry outbox(String topic, String type, int version, String key, String source,
                                           Map<String, Object> payload) {
        OutboxEntry e = new OutboxEntry();
        e.seq = Json.num(meta, "next.outbox", 1);
        meta.put("next.outbox", e.seq + 1);
        e.topic = topic;
        e.type = type;
        e.version = version;
        e.key = key;
        e.correlationId = key;
        e.source = source;
        e.payload = payload;
        e.createdAt = Clock.millis();
        outbox.put(e.seq, e);
        // keep only the newest sent rows
        long sent = outbox.values().stream().filter(OutboxEntry::sent).count();
        if (sent > MAX_SENT_OUTBOX) {
            var it = outbox.values().iterator();
            while (it.hasNext() && sent > MAX_SENT_OUTBOX) { if (it.next().sent()) { it.remove(); sent--; } }
        }
        dirty.add("outbox");
        return e;
    }

    // ------------------------------------------------------------------ saving

    public synchronized void markDirty(String... names) {
        for (String n : names) dirty.add(n);
    }

    /** Writes the collections that changed since the last flush. */
    public synchronized void flush() {
        if (batch || dirty.isEmpty()) return;
        List<String> names = new ArrayList<>(dirty);
        dirty.clear();
        save(names.toArray(new String[0]));
    }

    public synchronized void saveAll() {
        batch = false;
        dirty.clear();
        save(ALL.toArray(new String[0]));
    }

    /** Saves just the named collections. */
    public synchronized void save(String... names) {
        for (String n : names) {
            switch (n) {
                case "users" -> write("users.json", users.values(), true);
                case "customers" -> write("customers.json", customers.values(), true);
                case "products" -> write("products.json", products.values(), true);
                case "orders" -> write("orders.json", orders.values(), false);
                case "outbox" -> write("outbox.json", outbox.values(), false);
                case "sagas" -> write("sagas.json", sagas, false);
                case "reservations" -> write("reservations.json", reservations.values(), false);
                case "payments" -> write("payments.json", payments.values(), false);
                case "shipments" -> write("shipments.json", shipments.values(), false);
                case "notifications" -> write("notifications.json", notifications.values(), false);
                case "views" -> write("views.json", views.values(), false);
                case "deadLetters" -> write("deadLetters.json", deadLetters.values(), true);
                case "groups" -> write("groups.json", groups, false);
                case "audit" -> write("audit.json", audit, true);
                case "contacts" -> write("contacts.json", contacts, false);
                case "meta" -> { }   // meta.json is written after every save (below)
                default -> throw new IllegalArgumentException("Unknown collection " + n);
            }
        }
        write("meta.json", meta, true);
    }

    private void write(String file, Object data, boolean pretty) {
        if (dir == null || batch) return;
        try {
            Path tmp = dir.resolve(file + ".tmp");
            Files.writeString(tmp, pretty ? Json.pretty(data) : Json.write(data), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, dir.resolve(file), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicNotSupported) {
                Files.move(tmp, dir.resolve(file), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not save " + file + ": " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ reading helpers

    private <T> void readInto(String file, Map<String, T> into, Function<Map<String, Object>, T> maker,
                              Function<T, String> idOf) throws IOException {
        for (Object o : readList(file)) {
            T t = maker.apply(asMap(o));
            into.put(idOf.apply(t), t);
        }
    }

    private List<?> readList(String file) throws IOException {
        Path p = dir.resolve(file);
        if (!Files.exists(p)) return List.of();
        Object v = Json.parse(Files.readString(p, StandardCharsets.UTF_8));
        if (!(v instanceof List<?> l)) throw new IOException(file + " is not a JSON list");
        return l;
    }

    private Map<String, Object> readMap(String file) throws IOException {
        Path p = dir.resolve(file);
        if (!Files.exists(p)) return new LinkedHashMap<>();
        return Json.parseObject(Files.readString(p, StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (!(o instanceof Map)) throw new IllegalArgumentException("Expected a JSON object in the data file");
        return (Map<String, Object>) o;
    }

    // ------------------------------------------------------------------ small lookups

    public synchronized User userByName(String username) {
        if (username == null) return null;
        for (User u : users.values()) if (u.username.equalsIgnoreCase(username.trim())) return u;
        return null;
    }

    public synchronized String userName(String id) {
        User u = id == null ? null : users.get(id);
        return u == null ? "—" : u.name;
    }

    public synchronized String customerName(String id) {
        Customer c = id == null ? null : customers.get(id);
        return c == null ? "—" : c.name;
    }

    public synchronized boolean flag(String name) { return Json.bool(meta, name, false); }

    public synchronized void setFlag(String name, boolean on) {
        meta.put(name, on);
        dirty.add("meta");
    }
}
