package com.cecil.invoice.store;

import com.cecil.invoice.model.*;
import com.cecil.invoice.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Keeps all records in memory and saves them as JSON files in the data folder
 * (one file per kind of record). No database is needed.
 *
 * Files are written to a temp file first and then moved into place, so a crash in
 * the middle of a save never leaves a half-written file.
 */
public class DataStore {
    public final List<User> users = new ArrayList<>();
    public final List<Vendor> vendors = new ArrayList<>();
    public final List<Invoice> invoices = new ArrayList<>();
    public final List<Event> events = new ArrayList<>();
    public Settings settings = new Settings();
    public CategoryModel model = new CategoryModel();

    private final Path dir;          // null = keep in memory only (used by tests)
    private final Clock clock;
    private final Map<String, Integer> counters = new HashMap<>();
    private LocalDateTime fixedNow;  // used while the demo data is created

    public DataStore(Path dir, Clock clock) {
        this.dir = dir;
        this.clock = clock;
    }

    public Clock clock() { return clock; }
    public LocalDate today() { return LocalDate.now(clock); }
    public LocalDateTime now() { return fixedNow != null ? fixedNow : LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS); }
    public String nowText() { return now().toString(); }

    /** Pretend it is another time (demo data). null = back to the real clock. */
    public void setNow(LocalDateTime t) { fixedNow = t == null ? null : t.truncatedTo(ChronoUnit.SECONDS); }

    public boolean hasFiles() { return dir != null && Files.exists(dir.resolve("users.json")); }

    // ------------------------------------------------------------ load / save

    /** Read the data folder. If it is empty, fill it with demo data. */
    public synchronized void load() {
        if (hasFiles()) {
            readList("users", User.class, users);
            readList("vendors", Vendor.class, vendors);
            readList("invoices", Invoice.class, invoices);
            readList("events", Event.class, events);
            Path s = dir.resolve("settings.json");
            if (Files.exists(s)) settings = Json.bind(Json.parse(readFile(s)), Settings.class);
            Path m = dir.resolve("model.json");
            if (Files.exists(m)) model = Json.bind(Json.parse(readFile(m)), CategoryModel.class);
            rebuildCounters();
        } else {
            SeedData.fill(this);
            rebuildCounters();
            saveAll();
        }
    }

    public synchronized void saveAll() {
        save("users", "vendors", "invoices", "events", "settings", "model");
    }

    /** Save the named files, e.g. save("invoices", "events"). */
    public synchronized void save(String... names) {
        if (dir == null) return;
        try {
            Files.createDirectories(dir);
            for (String n : names) {
                Object data = switch (n) {
                    case "users" -> users;
                    case "vendors" -> vendors;
                    case "invoices" -> invoices;
                    case "events" -> events;
                    case "settings" -> settings;
                    case "model" -> model;
                    default -> throw new IllegalArgumentException("Unknown file " + n);
                };
                Path target = dir.resolve(n + ".json");
                Path tmp = dir.resolve(n + ".json.tmp");
                Files.writeString(tmp, Json.write(data), StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not save data: " + e.getMessage(), e);
        }
    }

    private <T> void readList(String name, Class<T> type, List<T> into) {
        Path p = dir.resolve(name + ".json");
        if (!Files.exists(p)) return;
        Object parsed = Json.parse(readFile(p));
        if (parsed instanceof List<?> list) for (Object o : list) into.add(Json.bind(o, type));
    }

    private static String readFile(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + p + ": " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------ ids

    /** Next id with a prefix: "IX" -> IX0001, IX0002 ... (never reused). */
    public synchronized String nextId(String prefix) {
        int n = counters.getOrDefault(prefix, 0) + 1;
        counters.put(prefix, n);
        return prefix + String.format("%04d", n);
    }

    private void rebuildCounters() {
        counters.clear();
        List<String> ids = new ArrayList<>();
        users.forEach(x -> ids.add(x.id));
        vendors.forEach(x -> ids.add(x.id));
        invoices.forEach(x -> ids.add(x.id));
        events.forEach(x -> ids.add(x.id));
        for (String id : ids) {
            if (id == null) continue;
            int i = 0;
            while (i < id.length() && !Character.isDigit(id.charAt(i))) i++;
            if (i == 0 || i == id.length()) continue;
            try {
                counters.merge(id.substring(0, i), Integer.parseInt(id.substring(i)), Math::max);
            } catch (NumberFormatException ignored) { }
        }
    }

    // ------------------------------------------------------------ lookups

    public User user(String id) { return find(users, u -> u.id.equals(id)); }
    public User userByName(String username) { return find(users, u -> u.username.equalsIgnoreCase(username)); }
    public Vendor vendor(String id) { return id == null || id.isEmpty() ? null : find(vendors, v -> v.id.equals(id)); }
    public Invoice invoice(String id) { return find(invoices, i -> i.id.equals(id)); }

    public String userName(String id) {
        if (id == null || id.isEmpty()) return "";
        User u = user(id);
        return u == null ? id : u.name;
    }

    private static <T> T find(List<T> list, java.util.function.Predicate<T> p) {
        for (T t : list) if (p.test(t)) return t;
        return null;
    }

    /** Add a history line (kept to the newest 3000). Caller saves "events". */
    public synchronized void log(String user, String action, String detail) {
        Event e = new Event();
        e.id = nextId("E");
        e.at = nowText();
        e.user = user;
        e.action = action;
        e.detail = detail;
        events.add(e);
        while (events.size() > 3000) events.remove(0);
    }
}
