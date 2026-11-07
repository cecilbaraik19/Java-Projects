package com.cecil.sambandh.store;

import com.cecil.sambandh.model.AuditEntry;
import com.cecil.sambandh.model.Customer;
import com.cecil.sambandh.model.FollowUp;
import com.cecil.sambandh.model.Interaction;
import com.cecil.sambandh.model.Order;
import com.cecil.sambandh.model.Ticket;
import com.cecil.sambandh.model.User;
import com.cecil.sambandh.util.Clock;
import com.cecil.sambandh.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Keeps all records in memory and saves each collection as a JSON file in the data folder:
 *   users.json, customers.json, interactions.json, followups.json, orders.json, tickets.json, audit.json, counters.json
 *
 * Saving writes a temporary file first and then renames it, so a crash in the middle of a save never leaves
 * a half-written file. Every public method is synchronized: one change at a time, so two people can never
 * overwrite each other's change.
 */
public class DataStore {
    public static final int MAX_AUDIT = 5000;

    private final Path dir;   // null = memory only (used by tests)

    public final Map<String, User> users = new LinkedHashMap<>();
    public final Map<String, Customer> customers = new LinkedHashMap<>();
    public final Map<String, Interaction> interactions = new LinkedHashMap<>();
    public final Map<String, FollowUp> followUps = new LinkedHashMap<>();
    public final Map<String, Order> orders = new LinkedHashMap<>();
    public final Map<String, Ticket> tickets = new LinkedHashMap<>();
    public final List<AuditEntry> audit = new ArrayList<>();
    private final Map<String, Object> counters = new LinkedHashMap<>();

    public DataStore(Path dir) {
        this.dir = dir;
    }

    /** True when there was nothing saved yet (first start). */
    public synchronized boolean load() throws IOException {
        if (dir == null) return true;
        Files.createDirectories(dir);
        if (!Files.exists(dir.resolve("users.json"))) return true;
        readInto("users.json", users, User::fromJson, u -> u.id);
        readInto("customers.json", customers, Customer::fromJson, c -> c.id);
        readInto("interactions.json", interactions, Interaction::fromJson, i -> i.id);
        readInto("followups.json", followUps, FollowUp::fromJson, f -> f.id);
        readInto("orders.json", orders, Order::fromJson, o -> o.id);
        readInto("tickets.json", tickets, Ticket::fromJson, t -> t.id);
        for (Object o : readList("audit.json")) audit.add(AuditEntry.fromJson(asMap(o)));
        Path c = dir.resolve("counters.json");
        if (Files.exists(c)) counters.putAll(Json.parseObject(Files.readString(c, StandardCharsets.UTF_8)));
        return users.isEmpty();
    }

    /** Next id for a kind of record, e.g. nextId("C", 1001) -> "C-1001", then "C-1002" ... */
    public synchronized String nextId(String prefix, long start) {
        long n = Json.num(counters, prefix, start);
        counters.put(prefix, n + 1);
        return prefix + "-" + n;
    }

    public synchronized void log(String userId, String action, String target, String detail) {
        audit.add(new AuditEntry(Clock.millis(), userId, action, target, detail));
        while (audit.size() > MAX_AUDIT) audit.remove(0);
    }

    // ------------------------------------------------------------------ saving

    public synchronized void saveAll() {
        save("users.json", users.values());
        save("customers.json", customers.values());
        save("interactions.json", interactions.values());
        save("followups.json", followUps.values());
        save("orders.json", orders.values());
        save("tickets.json", tickets.values());
        save("audit.json", audit);
        save("counters.json", counters);
    }

    /** Saves just the named collections (faster than saving everything after each small change). */
    public synchronized void save(String... names) {
        for (String n : names) {
            switch (n) {
                case "users" -> save("users.json", users.values());
                case "customers" -> save("customers.json", customers.values());
                case "interactions" -> save("interactions.json", interactions.values());
                case "followups" -> save("followups.json", followUps.values());
                case "orders" -> save("orders.json", orders.values());
                case "tickets" -> save("tickets.json", tickets.values());
                case "audit" -> save("audit.json", audit);
                default -> throw new IllegalArgumentException("Unknown collection " + n);
            }
        }
        save("counters.json", counters);
    }

    private void save(String file, Object data) {
        if (dir == null) return;
        try {
            Path tmp = dir.resolve(file + ".tmp");
            Files.writeString(tmp, Json.pretty(data), StandardCharsets.UTF_8);
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
}
