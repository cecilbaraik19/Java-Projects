package com.cecil.parisar.store;

import com.cecil.parisar.model.Alert;
import com.cecil.parisar.model.AuditEntry;
import com.cecil.parisar.model.Building;
import com.cecil.parisar.model.ClassSession;
import com.cecil.parisar.model.Course;
import com.cecil.parisar.model.Device;
import com.cecil.parisar.model.Notice;
import com.cecil.parisar.model.Point;
import com.cecil.parisar.model.Room;
import com.cecil.parisar.model.Student;
import com.cecil.parisar.model.User;
import com.cecil.parisar.util.Clock;
import com.cecil.parisar.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Keeps all records in memory and saves each collection as a JSON file in the data folder.
 *
 * Sensor history ("telemetry") is kept in two sizes:
 *   fine   - one point per 10 minutes, for the last 24 hours (the live charts)
 *   hourly - one point per hour, for the last 14 days (energy forecast, analytics)
 * Older points are dropped here; the cloud sync keeps the full history in the cloud folder.
 *
 * Saving writes a temporary file first and then renames it, so a crash in the middle of a save never leaves
 * a half-written file. Everything is synchronized on this object: one change at a time.
 */
public class DataStore {
    public static final int MAX_AUDIT = 5000;
    public static final long FINE_MS = 10 * 60_000L;
    public static final long HOUR_MS = 3_600_000L;
    public static final int FINE_KEEP = 24 * 6;      // 24 hours of 10-minute points
    public static final int HOURLY_KEEP = 14 * 24;   // 14 days of hourly points

    private final Path dir;   // null = memory only (used by tests)

    public final Map<String, User> users = new LinkedHashMap<>();
    public final Map<String, Building> buildings = new LinkedHashMap<>();
    public final Map<String, Room> rooms = new LinkedHashMap<>();
    public final Map<String, Device> devices = new LinkedHashMap<>();
    public final Map<String, Student> students = new LinkedHashMap<>();
    public final Map<String, Course> courses = new LinkedHashMap<>();
    public final Map<String, ClassSession> sessions = new LinkedHashMap<>();
    public final Map<String, Notice> notices = new LinkedHashMap<>();
    public final Map<String, Alert> alerts = new LinkedHashMap<>();
    public final List<AuditEntry> audit = new ArrayList<>();
    public final Map<String, Deque<Point>> fine = new LinkedHashMap<>();
    public final Map<String, Deque<Point>> hourly = new LinkedHashMap<>();
    /** Id counters and small settings (for example when the cloud sync last ran). */
    public final Map<String, Object> meta = new LinkedHashMap<>();

    /** True while the demo data is being created: nothing is written until saveAll() at the end (much faster). */
    public volatile boolean batch;

    public DataStore(Path dir) { this.dir = dir; }

    public Path dir() { return dir; }

    /** True when there was nothing saved yet (first start). */
    public synchronized boolean load() throws IOException {
        if (dir == null) return true;
        Files.createDirectories(dir);
        if (!Files.exists(dir.resolve("users.json"))) return true;
        readInto("users.json", users, User::fromJson, u -> u.id);
        readInto("buildings.json", buildings, Building::fromJson, b -> b.id);
        readInto("rooms.json", rooms, Room::fromJson, r -> r.id);
        readInto("devices.json", devices, Device::fromJson, d -> d.id);
        readInto("students.json", students, Student::fromJson, s -> s.id);
        readInto("courses.json", courses, Course::fromJson, c -> c.id);
        readInto("sessions.json", sessions, ClassSession::fromJson, s -> s.id);
        readInto("notices.json", notices, Notice::fromJson, n -> n.id);
        readInto("alerts.json", alerts, Alert::fromJson, a -> a.id);
        for (Object o : readList("audit.json")) audit.add(AuditEntry.fromJson(asMap(o)));
        Path m = dir.resolve("meta.json");
        if (Files.exists(m)) meta.putAll(Json.parseObject(Files.readString(m, StandardCharsets.UTF_8)));
        Path t = dir.resolve("telemetry.json");
        if (Files.exists(t)) {
            Map<String, Object> all = Json.parseObject(Files.readString(t, StandardCharsets.UTF_8));
            readSeries(all.get("fine"), fine);
            readSeries(all.get("hourly"), hourly);
        }
        return users.isEmpty();
    }

    // ------------------------------------------------------------------ ids, log, telemetry

    /** Next id for a kind of record, e.g. nextId("A", 1001) -> "A-1001", then "A-1002" ... */
    public synchronized String nextId(String prefix, long start) {
        long n = Json.num(meta, "next." + prefix, start);
        meta.put("next." + prefix, n + 1);
        return prefix + "-" + n;
    }

    public synchronized void log(String userId, String action, String target, String detail) {
        audit.add(new AuditEntry(Clock.millis(), userId, action, target, detail));
        while (audit.size() > MAX_AUDIT) audit.remove(0);
    }

    /** Adds one reading to the device's fine and hourly history. */
    public synchronized void record(String deviceId, long at, Map<String, Double> values) {
        add(fine.computeIfAbsent(deviceId, k -> new ArrayDeque<>()), at - Math.floorMod(at, FINE_MS), values, FINE_KEEP);
        add(hourly.computeIfAbsent(deviceId, k -> new ArrayDeque<>()), at - Math.floorMod(at, HOUR_MS), values, HOURLY_KEEP);
    }

    private static void add(Deque<Point> series, long bucket, Map<String, Double> values, int keep) {
        Point last = series.peekLast();
        if (last != null && last.t == bucket) { last.add(values); return; }
        if (last != null && bucket < last.t) {           // a late reading: find its bucket (rare)
            for (Point p : series) if (p.t == bucket) { p.add(values); return; }
            return;                                       // too old - ignore
        }
        Point p = new Point(bucket);
        p.add(values);
        series.addLast(p);
        long window = (long) keep * (keep == FINE_KEEP ? FINE_MS : HOUR_MS);
        while (series.size() > keep || (!series.isEmpty() && series.peekFirst().t <= bucket - window)) series.pollFirst();
    }

    public synchronized List<Point> fineOf(String deviceId) {
        Deque<Point> d = fine.get(deviceId);
        return d == null ? new ArrayList<>() : new ArrayList<>(d);
    }

    public synchronized List<Point> hourlyOf(String deviceId) {
        Deque<Point> d = hourly.get(deviceId);
        return d == null ? new ArrayList<>() : new ArrayList<>(d);
    }

    // ------------------------------------------------------------------ saving

    public synchronized void saveAll() {
        batch = false;
        save("users", "buildings", "rooms", "devices", "students", "courses", "sessions", "notices", "alerts", "audit", "telemetry");
    }

    /** Saves just the named collections (faster than saving everything after each small change). */
    public synchronized void save(String... names) {
        for (String n : names) {
            switch (n) {
                case "users" -> write("users.json", users.values(), true);
                case "buildings" -> write("buildings.json", buildings.values(), true);
                case "rooms" -> write("rooms.json", rooms.values(), true);
                case "devices" -> write("devices.json", devices.values(), true);
                case "students" -> write("students.json", students.values(), true);
                case "courses" -> write("courses.json", courses.values(), true);
                case "sessions" -> write("sessions.json", sessions.values(), false);
                case "notices" -> write("notices.json", notices.values(), true);
                case "alerts" -> write("alerts.json", alerts.values(), true);
                case "audit" -> write("audit.json", audit, true);
                case "telemetry" -> write("telemetry.json", Json.obj("fine", fine, "hourly", hourly), false);
                default -> throw new IllegalArgumentException("Unknown collection " + n);
            }
        }
        write("meta.json", meta, true);
    }

    /** All collections as one JSON object (used by the cloud backup). */
    public synchronized Map<String, Object> snapshot() {
        return Json.obj("users", new ArrayList<>(users.values()), "buildings", new ArrayList<>(buildings.values()),
                "rooms", new ArrayList<>(rooms.values()), "devices", new ArrayList<>(devices.values()),
                "students", new ArrayList<>(students.values()), "courses", new ArrayList<>(courses.values()),
                "sessions", new ArrayList<>(sessions.values()), "notices", new ArrayList<>(notices.values()),
                "alerts", new ArrayList<>(alerts.values()), "meta", new LinkedHashMap<>(meta));
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

    private static void readSeries(Object raw, Map<String, Deque<Point>> into) {
        if (!(raw instanceof Map<?, ?> m)) return;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            Deque<Point> d = new ArrayDeque<>();
            if (e.getValue() instanceof List<?> l) for (Object o : l) d.add(Point.fromJson(asMap(o)));
            into.put(String.valueOf(e.getKey()), d);
        }
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

    public synchronized Student studentByCard(String cardId) {
        if (cardId == null) return null;
        for (Student s : students.values()) if (cardId.equalsIgnoreCase(s.cardId)) return s;
        return null;
    }

    public synchronized Device deviceOf(String type, String roomOrBuildingId) {
        for (Device d : devices.values())
            if (d.type.equals(type) && (roomOrBuildingId.equals(d.roomId) || (d.roomId == null && roomOrBuildingId.equals(d.buildingId)))) return d;
        return null;
    }

    public synchronized ClassSession openSessionIn(String roomId) {
        for (ClassSession s : sessions.values()) if (s.isOpen() && roomId.equals(s.roomId)) return s;
        return null;
    }
}
