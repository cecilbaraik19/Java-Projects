package com.cecil.parisar.iot;

import com.cecil.parisar.model.Alert;
import com.cecil.parisar.model.Device;
import com.cecil.parisar.model.Point;
import com.cecil.parisar.model.Role;
import com.cecil.parisar.model.Room;
import com.cecil.parisar.model.Student;
import com.cecil.parisar.model.User;
import com.cecil.parisar.service.AcademicService;
import com.cecil.parisar.service.Problem;
import com.cecil.parisar.store.DataStore;
import com.cecil.parisar.util.Clock;
import com.cecil.parisar.util.Json;
import com.cecil.parisar.util.Passwords;
import com.cecil.parisar.util.Stats;
import com.cecil.parisar.util.Text;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The IoT hub: everything that comes from the campus devices goes through here.
 *
 *   1. ingest()   - a real device sends JSON to POST /iot/ingest with its id and secret key
 *   2. process()  - check the values, save them (latest value + history), run the alert rules
 *   3. rules      - fixed limits (heat, CO2, low water, energy used in an empty room) and the AI anomaly check
 *   4. offline    - a device that has not sent anything for 5 minutes gets an OFFLINE alert
 *   5. control    - switch the AC / lights of a room on or off from the website (the "actuator" side of IoT)
 */
public class IotService {
    public static final long OFFLINE_MS = 5 * 60_000L;
    public static final int MAX_PER_MINUTE = 120;          // per device, protects the server from a broken device

    // alert limits (simple, easy to explain in a viva)
    public static final double HEAT_WARN = 32, HEAT_CRIT = 36, HEAT_CLEAR = 30;
    public static final double CO2_WARN = 1200, CO2_CRIT = 2000, CO2_CLEAR = 1000;
    public static final double WATER_WARN = 20, WATER_CRIT = 10, WATER_CLEAR = 30;
    public static final double ANOMALY_Z = 4.0;

    private final DataStore store;
    private AcademicService academic;
    private final Map<String, Deque<Long>> rate = new ConcurrentHashMap<>();
    /** room id -> since when it has been empty with something switched on (for the energy-waste rule) */
    private final Map<String, Long> emptySince = new ConcurrentHashMap<>();
    private volatile boolean rules = true;
    private final Deque<Long> recentMessages = new ArrayDeque<>();

    public IotService(DataStore store) { this.store = store; }

    public void setAcademic(AcademicService academic) { this.academic = academic; }

    /** Seeding old history: no alerts while filling in the past. */
    public void setRules(boolean on) { this.rules = on; }

    // ------------------------------------------------------------------ 1. messages from real devices

    /** A message from a device over HTTP. Checks the key, the rate limit, then processes it. */
    public Map<String, Object> ingest(String deviceId, String key, Map<String, Object> body) {
        Device d;
        synchronized (store) { d = deviceId == null ? null : store.devices.get(deviceId); }
        // same answer for "no such device" and "wrong key", compared in constant time
        String expected = d == null ? Passwords.sha256("-") : d.keyHash;
        String given = Passwords.sha256(key == null ? "" : key);
        boolean ok = MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
        if (d == null || !ok) throw new Problem(401, "Unknown device or wrong device key.");
        if (!d.active) throw Problem.forbidden("This device is switched off on the server.");
        long now = Clock.millis();
        Deque<Long> q = rate.computeIfAbsent(d.id, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > 60_000) q.pollFirst();
            if (q.size() >= MAX_PER_MINUTE) throw new Problem(429, "Too many messages from this device (120 per minute max).");
            q.addLast(now);
        }
        return process(d, now, body);
    }

    // ------------------------------------------------------------------ 2. processing one message

    /**
     * Checks and saves one message. Used by real devices (after ingest) and by the simulator.
     * For ENV / ENERGY / WATER the body is {"temp": 24.1, ...}; for GATE it is {"cardId": "RF-..."}.
     */
    public Map<String, Object> process(Device d, long at, Map<String, Object> body) {
        synchronized (store) {
            countMessage(at);
            if ("GATE".equals(d.type)) return gateTap(d, at, body);
            Map<String, Double> values = new LinkedHashMap<>();
            List<String> rejected = new ArrayList<>();
            for (Device.Metric m : Device.metricsOf(d.type)) {
                Object raw = body.get(m.key());
                if (raw == null) continue;
                double v = raw instanceof Number n ? n.doubleValue() : Json.dbl(body, m.key(), Double.NaN);
                if (Double.isNaN(v) || Double.isInfinite(v) || v < m.min() || v > m.max()) { rejected.add(m.key()); continue; }
                values.put(m.key(), v);
            }
            if (body.get("battery") instanceof Number b) d.battery = (int) Stats.clamp(b.doubleValue(), 0, 100);
            if (values.isEmpty()) throw Problem.bad("No valid values in the message" + (rejected.isEmpty() ? "." : " (out of range: " + String.join(", ", rejected) + ")."));
            List<Point> before = rules ? store.fineOf(d.id) : List.of();
            d.latest.putAll(values);
            d.lastSeen = at;
            d.messages++;
            store.record(d.id, at, values);
            resolve(d.id, "OFFLINE", "device is sending again");
            List<String> raised = new ArrayList<>();
            if (rules) raised = runRules(d, at, values, before);
            return Json.obj("ok", true, "device", d.id, "accepted", values.keySet(), "rejected", rejected, "alerts", raised);
        }
    }

    private Map<String, Object> gateTap(Device d, long at, Map<String, Object> body) {
        String card = Text.clean(Json.str(body, "cardId"), 30);
        if (card == null && Json.bool(body, "heartbeat", false)) {        // "I am alive" every minute, no card tapped
            d.lastSeen = at;
            d.messages++;
            resolve(d.id, "OFFLINE", "device is sending again");
            return Json.obj("ok", true, "device", d.id, "result", "HEARTBEAT");
        }
        if (card == null) throw Problem.bad("A gate message needs a cardId (or \"heartbeat\": true).");
        d.lastSeen = at;
        d.messages++;
        d.latest.put("taps", d.latest.getOrDefault("taps", 0.0) + 1);
        store.record(d.id, at, Map.of("taps", 1.0));
        resolve(d.id, "OFFLINE", "device is sending again");
        Student s = store.studentByCard(card);
        if (s == null) return Json.obj("ok", true, "device", d.id, "result", "UNKNOWN_CARD", "message", "Card not registered.");
        String result = academic == null ? "NO_CLASS" : academic.tap(d, s, at);
        String msg = switch (result) {
            case "PRESENT" -> s.name + " marked present.";
            case "ALREADY" -> s.name + " was already marked present.";
            case "LIBRARY" -> s.name + " entered the library.";
            case "NOT_ENROLLED" -> s.name + " is not in the class running in this room.";
            default -> "No class is running in this room now.";
        };
        return Json.obj("ok", true, "device", d.id, "result", result, "student", s.id, "message", msg);
    }

    private void countMessage(long at) {
        recentMessages.addLast(at);
        while (!recentMessages.isEmpty() && at - recentMessages.peekFirst() > 5 * 60_000L) recentMessages.pollFirst();
    }

    /** Messages received in the last 5 minutes (for the cloud "IoT hub" status). */
    public int messagesLast5Min() {
        synchronized (store) {
            long now = Clock.millis();
            while (!recentMessages.isEmpty() && now - recentMessages.peekFirst() > 5 * 60_000L) recentMessages.pollFirst();
            return recentMessages.size();
        }
    }

    // ------------------------------------------------------------------ 3. alert rules

    private List<String> runRules(Device d, long at, Map<String, Double> v, List<Point> before) {
        List<String> raised = new ArrayList<>();
        Room room = d.roomId == null ? null : store.rooms.get(d.roomId);
        String where = room != null ? room.name : buildingName(d.buildingId);
        if (v.containsKey("temp")) {
            double t = v.get("temp");
            if (t >= HEAT_WARN) raise(raised, d, "HEAT", t >= HEAT_CRIT ? "CRITICAL" : "WARNING", t,
                    "Too hot in " + where + ": " + Text.one(t) + " °C" + (room != null && room.hasAC ? " - check the AC." : " - open windows / switch on fans."));
            else if (t <= HEAT_CLEAR) resolve(d.id, "HEAT", "temperature normal again");
        }
        if (v.containsKey("co2")) {
            double c = v.get("co2");
            if (c >= CO2_WARN) raise(raised, d, "CO2", c >= CO2_CRIT ? "CRITICAL" : "WARNING", c,
                    "Stale air in " + where + ": CO₂ " + Math.round(c) + " ppm - open windows or switch on ventilation.");
            else if (c <= CO2_CLEAR) resolve(d.id, "CO2", "air fresh again");
        }
        if (v.containsKey("level")) {
            double l = v.get("level");
            if (l <= WATER_WARN) raise(raised, d, "WATER_LOW", l <= WATER_CRIT ? "CRITICAL" : "WARNING", l,
                    "Water tank low at " + where + ": " + Math.round(l) + "% - check the pump or look for a leak.");
            else if (l >= WATER_CLEAR) resolve(d.id, "WATER_LOW", "tank filled again");
        }
        if (room != null && v.containsKey("occupancy")) energyWasteRule(raised, d, room, at, v.get("occupancy"));
        anomalyRule(raised, d, v, before);
        return raised;
    }

    /** Someone left the AC or lights on in an empty room for 30 minutes. With "auto energy saver" on, the system switches them off itself. */
    private void energyWasteRule(List<String> raised, Device d, Room room, long at, double people) {
        boolean somethingOn = room.lightsOn || (room.hasAC && room.acOn);
        if ("SERVER".equals(room.kind)) return;                         // a server room is meant to be cooled while empty
        if (people >= 1 || !somethingOn) {
            emptySince.remove(room.id);
            resolve(d.id, "ENERGY_WASTE", people >= 1 ? "room is in use again" : "switched off");
            return;
        }
        long since = emptySince.computeIfAbsent(room.id, k -> at);
        if (at - since < 30 * 60_000L) return;
        String what = (room.acOn && room.hasAC ? "AC" : "") + (room.acOn && room.hasAC && room.lightsOn ? " and " : "") + (room.lightsOn ? "lights" : "");
        if (Json.bool(store.meta, "autoSaver", false)) {
            room.acOn = false;
            room.lightsOn = false;
            store.log(null, "iot.autosave", room.id, "auto energy saver switched off " + what + " in empty " + room.name);
            store.save("rooms", "audit");
            resolve(d.id, "ENERGY_WASTE", "switched off by the auto energy saver");
            return;
        }
        raise(raised, d, "ENERGY_WASTE", "INFO", 0, what.substring(0, 1).toUpperCase() + what.substring(1)
                + " on in empty " + room.name + " for " + ((at - since) / 60_000) + " min - switch off to save energy.");
    }

    /**
     * AI anomaly check (z-score): compare the new value with the last 24 ten-minute averages of the same sensor.
     * If it is more than 4 standard deviations away (and the jump is big in real units), it is unusual.
     */
    private void anomalyRule(List<String> raised, Device d, Map<String, Double> v, List<Point> before) {
        String key = switch (d.type) { case "ENV" -> "temp"; case "ENERGY" -> "power"; default -> null; };
        double minJump = "power".equals(key) ? 25 : 6;
        if (key == null || !v.containsKey(key)) return;
        Anomaly a = anomaly(before, key, v.get(key), minJump);
        if (a == null) { resolve(d.id, "ANOMALY", "readings normal again"); return; }
        if (a.z() < ANOMALY_Z) { if (Math.abs(a.z()) < 2) resolve(d.id, "ANOMALY", "readings normal again"); return; }
        // a fixed rule already explains it (for example HEAT) - no need for a second alert
        for (Alert x : store.alerts.values()) if (x.isOpen() && d.id.equals(x.deviceId) && !"ANOMALY".equals(x.rule) && !"ENERGY_WASTE".equals(x.rule)) return;
        Room room = d.roomId == null ? null : store.rooms.get(d.roomId);
        String where = room != null ? room.name : buildingName(d.buildingId);
        String unit = "power".equals(key) ? " kW" : " °C";
        raise(raised, d, "ANOMALY", "WARNING", v.get(key), "Unusual " + ("power".equals(key) ? "power use" : "temperature") + " at " + where + ": "
                + Text.one(v.get(key)) + unit + " (normal about " + Text.one(a.mean()) + unit + ", " + Text.one(a.z()) + " std. deviations away).");
    }

    /** The anomaly maths on its own (public for tests): null when there is too little history. */
    public static Anomaly anomaly(List<Point> history, String key, double value, double minJump) {
        List<Double> xs = new ArrayList<>();
        for (int i = Math.max(0, history.size() - 25); i < history.size() - 1; i++) {   // skip the bucket still filling
            double x = history.get(i).get(key);
            if (!Double.isNaN(x)) xs.add(x);
        }
        if (xs.size() < 12) return null;
        double mean = Stats.mean(xs), std = Math.max(Stats.std(xs), minJump / 6);
        double z = Math.abs(Stats.z(value, mean, std));
        if (Math.abs(value - mean) < minJump) z = Math.min(z, 1.9);
        return new Anomaly(mean, std, z);
    }

    public record Anomaly(double mean, double std, double z) { }

    private void raise(List<String> raised, Device d, String rule, String severity, double value, String message) {
        long now = Clock.millis();
        for (Alert a : store.alerts.values()) {
            if (a.isOpen() && d.id.equals(a.deviceId) && rule.equals(a.rule)) {
                a.value = value;
                a.message = message;
                if (rank(severity) > rank(a.severity)) { a.severity = severity; a.updatedAt = now; store.save("alerts"); }
                return;
            }
        }
        if (!"ANOMALY".equals(rule) && !"ENERGY_WASTE".equals(rule)) resolve(d.id, "ANOMALY", "explained by the " + rule + " rule");
        Alert a = new Alert();
        a.id = store.nextId("AL", 1001);
        a.rule = rule;
        a.severity = severity;
        a.deviceId = d.id;
        a.roomId = d.roomId;
        a.buildingId = d.buildingId;
        a.value = value;
        a.message = message;
        a.createdAt = now;
        a.updatedAt = now;
        store.alerts.put(a.id, a);
        store.save("alerts");
        raised.add(a.id);
    }

    private static int rank(String s) { return switch (s) { case "CRITICAL" -> 3; case "WARNING" -> 2; default -> 1; }; }

    private void resolve(String deviceId, String rule, String why) {
        for (Alert a : store.alerts.values()) {
            if (a.isOpen() && deviceId.equals(a.deviceId) && rule.equals(a.rule)) {
                a.status = "RESOLVED";
                a.resolvedBy = "auto";
                a.note = why;
                a.updatedAt = Clock.millis();
                store.save("alerts");
            }
        }
    }

    // ------------------------------------------------------------------ 4. offline check

    /** Run every minute: devices that went silent get an alert. */
    public int checkOffline() {
        synchronized (store) {
            long now = Clock.millis();
            int n = 0;
            for (Device d : store.devices.values()) {
                if (!d.active || d.lastSeen == 0 || now - d.lastSeen <= OFFLINE_MS) continue;
                List<String> raised = new ArrayList<>();
                long mins = (now - d.lastSeen) / 60_000;
                raise(raised, d, "OFFLINE", mins >= 30 ? "CRITICAL" : "WARNING", mins,
                        d.name + " has sent nothing for " + mins + " minutes - check power / Wi-Fi, or send a reboot.");
                n += raised.size();
            }
            return n;
        }
    }

    // ------------------------------------------------------------------ alerts for the website

    public List<Map<String, Object>> alerts(User user, String status) {
        synchronized (store) {
            List<Alert> list = new ArrayList<>(store.alerts.values());
            list.removeIf(a -> status != null && !status.isBlank() && !"ALL".equals(status)
                    && !(("OPEN".equals(status) && a.isOpen()) || status.equals(a.status)));
            list.sort(Comparator.comparing((Alert a) -> a.isOpen() ? 0 : 1)
                    .thenComparing(a -> -rank(a.severity)).thenComparing(a -> -a.updatedAt));
            List<Map<String, Object>> out = new ArrayList<>();
            for (Alert a : list) {
                Map<String, Object> m = a.toJson();
                m.put("roomName", a.roomId == null ? null : store.rooms.get(a.roomId) == null ? null : store.rooms.get(a.roomId).name);
                m.put("buildingName", buildingName(a.buildingId));
                m.put("ackByName", a.ackBy == null ? null : store.userName(a.ackBy));
                m.put("resolvedByName", a.resolvedBy == null ? null : "auto".equals(a.resolvedBy) ? "automatic" : store.userName(a.resolvedBy));
                out.add(m);
                if (out.size() >= 300) break;
            }
            return out;
        }
    }

    public Alert ack(User user, String id) {
        requireStaff(user);
        synchronized (store) {
            Alert a = store.alerts.get(id);
            if (a == null) throw Problem.notFound("Alert");
            if (!"OPEN".equals(a.status)) throw Problem.bad("Only a new (open) alert can be acknowledged.");
            a.status = "ACK";
            a.ackBy = user.id;
            a.updatedAt = Clock.millis();
            store.log(user.id, "alert.ack", a.id, a.rule);
            store.save("alerts", "audit");
            return a;
        }
    }

    public Alert resolveByHand(User user, String id, String note) {
        requireStaff(user);
        synchronized (store) {
            Alert a = store.alerts.get(id);
            if (a == null) throw Problem.notFound("Alert");
            if (!a.isOpen()) throw Problem.bad("This alert is already resolved.");
            a.status = "RESOLVED";
            a.resolvedBy = user.id;
            a.note = Text.clean(note, 200);
            a.updatedAt = Clock.millis();
            store.log(user.id, "alert.resolve", a.id, a.rule + (a.note == null ? "" : ": " + a.note));
            store.save("alerts", "audit");
            return a;
        }
    }

    // ------------------------------------------------------------------ 5. remote control (actuators)

    public Room control(User user, String roomId, Map<String, Object> body) {
        requireStaff(user);
        synchronized (store) {
            Room r = store.rooms.get(roomId);
            if (r == null) throw Problem.notFound("Room");
            List<String> changes = new ArrayList<>();
            if (body.containsKey("ac")) {
                if (!r.hasAC) throw Problem.bad(r.name + " has no AC.");
                boolean on = Json.bool(body, "ac", false);
                if (on != r.acOn) { r.acOn = on; changes.add("AC " + (on ? "on" : "off")); }
            }
            if (body.containsKey("lights")) {
                boolean on = Json.bool(body, "lights", false);
                if (on != r.lightsOn) { r.lightsOn = on; changes.add("lights " + (on ? "on" : "off")); }
            }
            if (changes.isEmpty()) throw Problem.bad("Nothing to change.");
            Device env = store.deviceOf("ENV", r.id);
            if (env != null && !r.lightsOn && !r.acOn) { emptySince.remove(r.id); resolve(env.id, "ENERGY_WASTE", "switched off from the website"); }
            store.log(user.id, "iot.control", r.id, String.join(", ", changes) + " in " + r.name);
            store.save("rooms", "audit");
            return r;
        }
    }

    // ------------------------------------------------------------------ device admin

    public List<Map<String, Object>> devices() {
        synchronized (store) {
            long now = Clock.millis();
            List<Map<String, Object>> out = new ArrayList<>();
            for (Device d : store.devices.values()) {
                Map<String, Object> m = d.publicView(now, OFFLINE_MS);
                Room r = d.roomId == null ? null : store.rooms.get(d.roomId);
                m.put("roomName", r == null ? null : r.name);
                m.put("buildingName", buildingName(d.buildingId));
                m.put("openAlerts", store.alerts.values().stream().filter(a -> a.isOpen() && d.id.equals(a.deviceId)).count());
                out.add(m);
            }
            return out;
        }
    }

    public Map<String, Object> deviceDetail(String id) {
        synchronized (store) {
            Device d = store.devices.get(id);
            if (d == null) throw Problem.notFound("Device");
            Map<String, Object> m = d.publicView(Clock.millis(), OFFLINE_MS);
            List<Object> metrics = new ArrayList<>();
            for (Device.Metric x : Device.metricsOf(d.type)) metrics.add(x.toJson());
            m.put("metrics", metrics);
            m.put("fine", store.fineOf(d.id));
            m.put("hourly", store.hourlyOf(d.id));
            Room r = d.roomId == null ? null : store.rooms.get(d.roomId);
            m.put("roomName", r == null ? null : r.name);
            m.put("buildingName", buildingName(d.buildingId));
            return m;
        }
    }

    /** Admin registers a new device. The secret key is shown once - only its hash is saved. */
    public Map<String, Object> register(User user, Map<String, Object> body) {
        requireAdmin(user);
        String type = Text.clean(Json.str(body, "type"), 10);
        String name = Text.clean(Json.str(body, "name"), 60);
        String roomId = Text.clean(Json.str(body, "roomId"), 20);
        String buildingId = Text.clean(Json.str(body, "buildingId"), 20);
        if (type == null || !Device.TYPES.contains(type.toUpperCase())) throw Problem.bad("Choose a device type: ENV, ENERGY, WATER or GATE.");
        type = type.toUpperCase();
        if (name == null) throw Problem.bad("Give the device a name.");
        synchronized (store) {
            if (("ENV".equals(type) || "GATE".equals(type))) {
                Room r = roomId == null ? null : store.rooms.get(roomId);
                if (r == null) throw Problem.bad("A room sensor or card reader must be placed in a room.");
                buildingId = r.buildingId;
            } else {
                roomId = null;
                if (buildingId == null || !store.buildings.containsKey(buildingId)) throw Problem.bad("Choose the building for this device.");
            }
            Device d = new Device();
            d.id = store.nextId("D-" + type, 201);
            d.name = name;
            d.type = type;
            d.roomId = roomId;
            d.buildingId = buildingId;
            String key = "pk_" + Passwords.token();
            d.keyHash = Passwords.sha256(key);
            store.devices.put(d.id, d);
            store.log(user.id, "device.register", d.id, type + " " + name);
            store.save("devices", "audit");
            return Json.obj("device", d.publicView(Clock.millis(), OFFLINE_MS), "key", key,
                    "note", "Copy this key now - it is shown only once.");
        }
    }

    public Map<String, Object> rotateKey(User user, String id) {
        requireAdmin(user);
        synchronized (store) {
            Device d = store.devices.get(id);
            if (d == null) throw Problem.notFound("Device");
            String key = "pk_" + Passwords.token();
            d.keyHash = Passwords.sha256(key);
            store.log(user.id, "device.key", d.id, "new secret key");
            store.save("devices", "audit");
            return Json.obj("device", d.id, "key", key, "note", "The old key stops working now. Copy this key - it is shown only once.");
        }
    }

    public Device setActive(User user, String id, boolean active) {
        requireAdmin(user);
        synchronized (store) {
            Device d = store.devices.get(id);
            if (d == null) throw Problem.notFound("Device");
            d.active = active;
            if (!active) resolve(d.id, "OFFLINE", "device switched off on the server");
            store.log(user.id, "device." + (active ? "on" : "off"), d.id, d.name);
            store.save("devices", "audit");
            return d;
        }
    }

    private String buildingName(String id) {
        return id == null || store.buildings.get(id) == null ? null : store.buildings.get(id).name;
    }

    static void requireStaff(User u) {
        if (!u.role.atLeast(Role.FACULTY)) throw Problem.forbidden("Only staff can do this.");
    }

    static void requireAdmin(User u) {
        if (u.role != Role.ADMIN) throw Problem.forbidden("Only the campus admin can do this.");
    }
}
