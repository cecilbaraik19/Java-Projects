package com.cecil.bijak.store;

import com.cecil.bijak.model.AuditEntry;
import com.cecil.bijak.model.BankLine;
import com.cecil.bijak.model.Customer;
import com.cecil.bijak.model.Invoice;
import com.cecil.bijak.model.Item;
import com.cecil.bijak.model.Mail;
import com.cecil.bijak.model.Payment;
import com.cecil.bijak.model.Recurring;
import com.cecil.bijak.model.Settings;
import com.cecil.bijak.model.User;
import com.cecil.bijak.util.Clock;
import com.cecil.bijak.util.Json;

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
 * Keeps all records in memory and saves each collection as a JSON file in the data folder.
 *
 * Saving writes a temporary file first and then renames it, so a crash in the middle of a save never leaves a
 * half-written file. Every change is made while holding the lock on this object (synchronized): ONE change at a time,
 * so two people issuing invoices in the same second can never get the same invoice number.
 */
public class DataStore {
    public static final int MAX_AUDIT = 6000;
    public static final int MAX_MAILS = 3000;

    private final Path dir;   // null = memory only (used by the tests)

    public final Map<String, User> users = new LinkedHashMap<>();
    public final Map<String, Customer> customers = new LinkedHashMap<>();
    public final Map<String, Item> items = new LinkedHashMap<>();
    public final Map<String, Invoice> invoices = new LinkedHashMap<>();
    public final Map<String, Payment> payments = new LinkedHashMap<>();
    public final Map<String, Recurring> recurring = new LinkedHashMap<>();
    public final Map<String, BankLine> bank = new LinkedHashMap<>();
    public final Map<String, Mail> mails = new LinkedHashMap<>();
    public final List<AuditEntry> audit = new ArrayList<>();
    public final Map<String, Object> meta = new LinkedHashMap<>();
    public Settings settings = new Settings();

    /** True while the demo data is being created: nothing is written until saveAll() at the end (much faster). */
    public volatile boolean batch;

    public DataStore(Path dir) { this.dir = dir; }

    public Path dir() { return dir; }

    /** Returns true when there was nothing saved yet (first start). */
    public synchronized boolean load() throws IOException {
        if (dir == null) return true;
        Files.createDirectories(dir);
        if (!Files.exists(dir.resolve("users.json"))) return true;
        readInto("users.json", users, User::fromJson, u -> u.id);
        readInto("customers.json", customers, Customer::fromJson, c -> c.id);
        readInto("items.json", items, Item::fromJson, i -> i.id);
        readInto("invoices.json", invoices, Invoice::fromJson, i -> i.id);
        readInto("payments.json", payments, Payment::fromJson, p -> p.id);
        readInto("recurring.json", recurring, Recurring::fromJson, r -> r.id);
        readInto("bank.json", bank, BankLine::fromJson, b -> b.id);
        readInto("outbox.json", mails, Mail::fromJson, m -> m.id);
        for (Object o : readList("audit.json")) audit.add(AuditEntry.fromJson(asMap(o)));
        Path m = dir.resolve("meta.json");
        if (Files.exists(m)) meta.putAll(Json.parseObject(Files.readString(m, StandardCharsets.UTF_8)));
        Path s = dir.resolve("settings.json");
        if (Files.exists(s)) settings = Settings.fromJson(Json.parseObject(Files.readString(s, StandardCharsets.UTF_8)));
        return users.isEmpty();
    }

    // ------------------------------------------------------------------ look-ups

    public synchronized User userByName(String username) {
        if (username == null) return null;
        String k = username.trim().toLowerCase();
        for (User u : users.values()) if (u.username.equals(k)) return u;
        return null;
    }

    public synchronized List<Invoice> invoicesOf(String customerId) {
        List<Invoice> out = new ArrayList<>();
        for (Invoice i : invoices.values()) if (i.customerId.equals(customerId)) out.add(i);
        return out;
    }

    public synchronized List<Payment> paymentsOf(String customerId) {
        List<Payment> out = new ArrayList<>();
        for (Payment p : payments.values()) if (p.customerId.equals(customerId)) out.add(p);
        return out;
    }

    /** What the customer owes right now (open invoices), in paise. */
    public synchronized long outstanding(String customerId) {
        long s = 0;
        for (Invoice i : invoices.values()) if (i.customerId.equals(customerId) && !i.creditNote() && i.status.open()) s += i.balance();
        return s;
    }

    /** Money of the customer not yet given to any invoice: unapplied payments and unused credit notes. */
    public synchronized long advance(String customerId) {
        long s = 0;
        for (Payment p : payments.values()) if (p.customerId.equals(customerId)) s += p.unapplied();
        for (Invoice i : invoices.values()) if (i.customerId.equals(customerId) && i.creditNote() && i.status == com.cecil.bijak.model.Status.ISSUED) s += i.balance();
        return s;
    }

    // ------------------------------------------------------------------ ids, numbers, log

    /** Next id for a kind of record, e.g. nextId("C", 101) gives "C-101", then "C-102" ... */
    public synchronized String nextId(String prefix, long start) {
        long n = Json.num(meta, "next." + prefix, start);
        meta.put("next." + prefix, n + 1);
        return prefix + "-" + n;
    }

    /** Next running number of a series in a financial year, e.g. counter "KVP/26-27". Starts at 1. */
    public synchronized long nextNumber(String counter) {
        long n = Json.num(meta, "no." + counter, 1);
        meta.put("no." + counter, n + 1);
        return n;
    }

    /** The number the next one will get (without taking it). */
    public synchronized long peekNumber(String counter) { return Json.num(meta, "no." + counter, 1); }

    public synchronized void log(String userId, String action, String target, String detail) {
        audit.add(new AuditEntry(Clock.millis(), userId, action, target, detail));
        while (audit.size() > MAX_AUDIT) audit.remove(0);
    }

    public synchronized Mail mail(Mail m) {
        m.id = nextId("M", 1);
        m.at = Clock.millis();
        mails.put(m.id, m);
        if (mails.size() > MAX_MAILS) mails.remove(mails.keySet().iterator().next());
        return m;
    }

    // ------------------------------------------------------------------ saving

    public static final String[] ALL = { "users", "customers", "items", "invoices", "payments", "recurring", "bank", "mails", "audit", "settings" };

    public synchronized void saveAll() {
        batch = false;
        save(ALL);
    }

    /** Saves just the named collections (faster than saving everything after each small change). */
    public synchronized void save(String... names) {
        for (String n : names) {
            switch (n) {
                case "users" -> write("users.json", users.values(), true);
                case "customers" -> write("customers.json", customers.values(), true);
                case "items" -> write("items.json", items.values(), true);
                case "invoices" -> write("invoices.json", invoices.values(), true);
                case "payments" -> write("payments.json", payments.values(), true);
                case "recurring" -> write("recurring.json", recurring.values(), true);
                case "bank" -> write("bank.json", bank.values(), true);
                case "mails" -> write("outbox.json", mails.values(), false);
                case "audit" -> write("audit.json", audit, false);
                case "settings" -> write("settings.json", settings, true);
                case "meta" -> { }   // meta.json is written with every save anyway
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
        if (!(o instanceof Map)) throw new IllegalArgumentException("Expected a JSON object");
        return (Map<String, Object>) o;
    }
}
