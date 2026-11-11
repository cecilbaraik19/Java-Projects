package com.cecil.sanket.model;

import com.cecil.sanket.util.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One event in the log - a fact that has already happened ("OrderPlaced", "PaymentAuthorized" ...).
 * Events are never changed or deleted. The envelope (everything except the payload) is the same for every type.
 *
 *   id             unique id of this event (consumers use it to skip duplicates)
 *   topic          which log it is in (orders, inventory, payments, shipments, dead-letter)
 *   partition      which part of the topic (chosen from the key, so one order always uses the same partition)
 *   offset         its position in that partition: 0, 1, 2 ... (consumers remember the next offset to read)
 *   type, version  what kind of fact it is, and which version of its schema
 *   key            the partition key (the order id)
 *   at             when it was written (epoch millis)
 *   correlationId  the order this event belongs to - all events of one order share it (used for tracing)
 *   causationId    the id of the event that caused this one (null for the first event)
 *   source         which service wrote it
 */
public final class Event implements Json.Writable {
    public final String id;
    public final String topic;
    public final int partition;
    public final long offset;
    public final String type;
    public final int version;
    public final String key;
    public final long at;
    public final String correlationId;
    public final String causationId;
    public final String source;
    public final Map<String, Object> payload;

    public Event(String id, String topic, int partition, long offset, String type, int version, String key, long at,
                 String correlationId, String causationId, String source, Map<String, Object> payload) {
        this.id = id; this.topic = topic; this.partition = partition; this.offset = offset; this.type = type;
        this.version = version; this.key = key; this.at = at; this.correlationId = correlationId;
        this.causationId = causationId; this.source = source;
        this.payload = payload == null ? new LinkedHashMap<>() : payload;
    }

    public String str(String field) { return Json.str(payload, field); }

    public long num(String field) { return Json.num(payload, field, 0); }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "topic", topic, "partition", partition, "offset", offset, "type", type,
                "version", version, "key", key, "at", at, "correlationId", correlationId,
                "causationId", causationId, "source", source, "payload", payload);
    }

    @SuppressWarnings("unchecked")
    public static Event fromJson(Map<String, Object> m) {
        Object p = m.get("payload");
        return new Event(Json.str(m, "id"), Json.str(m, "topic"), (int) Json.num(m, "partition", 0),
                Json.num(m, "offset", 0), Json.str(m, "type"), (int) Json.num(m, "version", 1), Json.str(m, "key"),
                Json.num(m, "at", 0), Json.str(m, "correlationId"), Json.str(m, "causationId"), Json.str(m, "source"),
                p instanceof Map ? (Map<String, Object>) p : new LinkedHashMap<>());
    }
}
