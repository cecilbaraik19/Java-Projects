package com.cecil.parisar.model;

import com.cecil.parisar.util.Json;

import java.util.Map;

/**
 * A problem found in the sensor data: too hot, too much CO2, low water, a device gone silent,
 * electricity used in an empty room, or a reading the AI says is unusual (anomaly).
 * Life of an alert:  OPEN -> ACK (someone is looking at it) -> RESOLVED (by a person, or automatically when the value is normal again).
 */
public class Alert implements Json.Writable {
    public String id;
    public String rule;        // HEAT, CO2, WATER_LOW, OFFLINE, ENERGY_WASTE, ANOMALY
    public String severity;    // INFO, WARNING, CRITICAL
    public String deviceId;
    public String roomId;
    public String buildingId;
    public String message;
    public double value;
    public String status = "OPEN";
    public long createdAt;
    public long updatedAt;
    public String ackBy;
    public String resolvedBy;  // a user id, or "auto"
    public String note;

    public boolean isOpen() { return !"RESOLVED".equals(status); }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "rule", rule, "severity", severity, "deviceId", deviceId, "roomId", roomId,
                "buildingId", buildingId, "message", message, "value", value, "status", status, "createdAt", createdAt,
                "updatedAt", updatedAt, "ackBy", ackBy, "resolvedBy", resolvedBy, "note", note);
    }

    public static Alert fromJson(Map<String, Object> m) {
        Alert a = new Alert();
        a.id = Json.str(m, "id");
        a.rule = Json.str(m, "rule");
        a.severity = Json.str(m, "severity", "WARNING");
        a.deviceId = Json.str(m, "deviceId");
        a.roomId = Json.str(m, "roomId");
        a.buildingId = Json.str(m, "buildingId");
        a.message = Json.str(m, "message");
        a.value = Json.dbl(m, "value", 0);
        a.status = Json.str(m, "status", "OPEN");
        a.createdAt = Json.num(m, "createdAt", 0);
        a.updatedAt = Json.num(m, "updatedAt", 0);
        a.ackBy = Json.str(m, "ackBy");
        a.resolvedBy = Json.str(m, "resolvedBy");
        a.note = Json.str(m, "note");
        return a;
    }
}
