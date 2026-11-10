package com.cecil.parisar.model;

import com.cecil.parisar.util.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An IoT device on the campus. Four kinds:
 *   ENV    - room sensor: temperature (°C), humidity (%), CO2 (ppm), occupancy (people)
 *   ENERGY - smart electricity meter for a building: power (kW)
 *   WATER  - water tank level sensor: level (%)
 *   GATE   - RFID card reader at a door: each card tap is sent to the server (attendance, library visits)
 * Each device has its own secret key. Only a SHA-256 hash of the key is saved.
 */
public class Device implements Json.Writable {
    public static final List<String> TYPES = List.of("ENV", "ENERGY", "WATER", "GATE");

    public String id;          // "D-ENV-101"
    public String name;
    public String type;
    public String roomId;      // ENV and GATE devices sit in a room
    public String buildingId;
    public String keyHash;
    public String firmware = "1.4.2";
    public boolean active = true;
    public long lastSeen;      // epoch millis of the last message
    public long messages;      // messages received in total
    public int battery = 100;  // %
    public Map<String, Double> latest = new LinkedHashMap<>();

    /** The metrics a device of this type sends, with unit and normal range (used for checks and the website). */
    public static List<Metric> metricsOf(String type) {
        return switch (type) {
            case "ENV" -> List.of(new Metric("temp", "Temperature", "°C", -10, 60),
                    new Metric("humidity", "Humidity", "%", 0, 100),
                    new Metric("co2", "CO₂", "ppm", 250, 5000),
                    new Metric("occupancy", "People", "", 0, 1000));
            case "ENERGY" -> List.of(new Metric("power", "Power", "kW", 0, 2000));
            case "WATER" -> List.of(new Metric("level", "Tank level", "%", 0, 100));
            case "GATE" -> List.of(new Metric("taps", "Card taps", "", 0, 100000));
            default -> List.of();
        };
    }

    /** One measured value: key used in JSON, label, unit, and the lowest/highest value a real sensor can send. */
    public record Metric(String key, String label, String unit, double min, double max) {
        public Map<String, Object> toJson() { return Json.obj("key", key, "label", label, "unit", unit); }
    }

    /** Device view for the website (never the key hash). */
    public Map<String, Object> publicView(long now, long offlineAfterMs) {
        return Json.obj("id", id, "name", name, "type", type, "roomId", roomId, "buildingId", buildingId,
                "firmware", firmware, "active", active, "lastSeen", lastSeen, "messages", messages, "battery", battery,
                "online", online(now, offlineAfterMs), "latest", latest);
    }

    public boolean online(long now, long offlineAfterMs) {
        return active && lastSeen > 0 && now - lastSeen <= offlineAfterMs;
    }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "name", name, "type", type, "roomId", roomId, "buildingId", buildingId,
                "keyHash", keyHash, "firmware", firmware, "active", active, "lastSeen", lastSeen, "messages", messages,
                "battery", battery, "latest", latest);
    }

    public static Device fromJson(Map<String, Object> m) {
        Device d = new Device();
        d.id = Json.str(m, "id");
        d.name = Json.str(m, "name");
        d.type = Json.str(m, "type", "ENV");
        d.roomId = Json.str(m, "roomId");
        d.buildingId = Json.str(m, "buildingId");
        d.keyHash = Json.str(m, "keyHash");
        d.firmware = Json.str(m, "firmware", "1.4.2");
        d.active = Json.bool(m, "active", true);
        d.lastSeen = Json.num(m, "lastSeen", 0);
        d.messages = Json.num(m, "messages", 0);
        d.battery = (int) Json.num(m, "battery", 100);
        Object l = m.get("latest");
        if (l instanceof Map<?, ?> lm) for (Map.Entry<?, ?> e : lm.entrySet())
            if (e.getValue() instanceof Number n) d.latest.put(String.valueOf(e.getKey()), n.doubleValue());
        return d;
    }
}
