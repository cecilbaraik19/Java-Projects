package com.cecil.sanket.model;

import com.cecil.sanket.util.Json;

import java.util.Map;

/**
 * An event that one consumer group could not handle, even after all its retries ("dead letter").
 * The group moved on (so one bad event cannot block the partition for ever) and parked the event here.
 * An admin can REDRIVE it (deliver it to that same group again, after the problem is fixed) or DISCARD it.
 */
public class DeadLetter implements Json.Writable {
    public String id;
    public String group;
    public String eventId;
    public String topic;
    public int partition;
    public long offset;
    public String type;
    public String correlationId;
    public String error;
    public int attempts;
    public long at;
    public String state;       // OPEN, REDRIVEN, DISCARDED
    public String note;        // what happened when it was redriven or discarded

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "group", group, "eventId", eventId, "topic", topic, "partition", partition,
                "offset", offset, "type", type, "correlationId", correlationId, "error", error, "attempts", attempts,
                "at", at, "state", state, "note", note);
    }

    public static DeadLetter fromJson(Map<String, Object> m) {
        DeadLetter d = new DeadLetter();
        d.id = Json.str(m, "id");
        d.group = Json.str(m, "group");
        d.eventId = Json.str(m, "eventId");
        d.topic = Json.str(m, "topic");
        d.partition = (int) Json.num(m, "partition", 0);
        d.offset = Json.num(m, "offset", 0);
        d.type = Json.str(m, "type");
        d.correlationId = Json.str(m, "correlationId");
        d.error = Json.str(m, "error");
        d.attempts = (int) Json.num(m, "attempts", 0);
        d.at = Json.num(m, "at", 0);
        d.state = Json.str(m, "state", "OPEN");
        d.note = Json.str(m, "note");
        return d;
    }
}
