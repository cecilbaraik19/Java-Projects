package com.cecil.sambandh.model;

import com.cecil.sambandh.util.Json;

import java.util.Map;

/** Something that happened with a customer: a call, an e-mail, a meeting, a WhatsApp chat or a note. */
public class Interaction implements Json.Writable {
    public static final java.util.List<String> TYPES = java.util.List.of("CALL", "EMAIL", "MEETING", "WHATSAPP", "NOTE");
    public static final java.util.List<String> OUTCOMES = java.util.List.of("POSITIVE", "NEUTRAL", "NEGATIVE");

    public String id;
    public String customerId;
    public String type;        // CALL, EMAIL, MEETING, WHATSAPP, NOTE
    public String direction;   // IN (customer contacted us) or OUT (we contacted them); NOTE has none
    public String summary;
    public String outcome;     // POSITIVE, NEUTRAL, NEGATIVE - how did it go?
    public int minutes;
    public long at;
    public String byUserId;

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "customerId", customerId, "type", type, "direction", direction, "summary", summary,
                "outcome", outcome, "minutes", minutes, "at", at, "byUserId", byUserId);
    }

    public static Interaction fromJson(Map<String, Object> m) {
        Interaction i = new Interaction();
        i.id = Json.str(m, "id");
        i.customerId = Json.str(m, "customerId");
        i.type = Json.str(m, "type", "NOTE");
        i.direction = Json.str(m, "direction");
        i.summary = Json.str(m, "summary");
        i.outcome = Json.str(m, "outcome", "NEUTRAL");
        i.minutes = (int) Json.num(m, "minutes", 0);
        i.at = Json.num(m, "at", 0);
        i.byUserId = Json.str(m, "byUserId");
        return i;
    }
}
