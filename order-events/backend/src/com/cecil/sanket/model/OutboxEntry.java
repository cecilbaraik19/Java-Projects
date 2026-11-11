package com.cecil.sanket.model;

import com.cecil.sanket.util.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One row of the transactional outbox: an event that a command (place order, cancel, dispatch ...) wants to publish.
 * The row is saved together with the change itself, so the change and "the event must be sent" can never get
 * separated. The outbox relay then publishes the row to the broker and marks it sent. If the broker is down the row
 * simply waits - nothing is lost.
 */
public class OutboxEntry implements Json.Writable {
    public long seq;
    public String topic;
    public String type;
    public int version;
    public String key;
    public String correlationId;
    public String source;
    public Map<String, Object> payload = new LinkedHashMap<>();
    public long createdAt;
    public long sentAt;          // 0 = not sent yet
    public String eventId;       // the event id once it is published
    public int attempts;
    public String lastError;

    public boolean sent() { return sentAt > 0; }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("seq", seq, "topic", topic, "type", type, "version", version, "key", key,
                "correlationId", correlationId, "source", source, "payload", payload, "createdAt", createdAt,
                "sentAt", sentAt, "eventId", eventId, "attempts", attempts, "lastError", lastError);
    }

    @SuppressWarnings("unchecked")
    public static OutboxEntry fromJson(Map<String, Object> m) {
        OutboxEntry e = new OutboxEntry();
        e.seq = Json.num(m, "seq", 0);
        e.topic = Json.str(m, "topic");
        e.type = Json.str(m, "type");
        e.version = (int) Json.num(m, "version", 1);
        e.key = Json.str(m, "key");
        e.correlationId = Json.str(m, "correlationId");
        e.source = Json.str(m, "source");
        if (m.get("payload") instanceof Map<?, ?> p) e.payload = (Map<String, Object>) p;
        e.createdAt = Json.num(m, "createdAt", 0);
        e.sentAt = Json.num(m, "sentAt", 0);
        e.eventId = Json.str(m, "eventId");
        e.attempts = (int) Json.num(m, "attempts", 0);
        e.lastError = Json.str(m, "lastError");
        return e;
    }
}
