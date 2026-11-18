package com.cecil.swikriti.model;

import com.cecil.swikriti.util.Json;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** "While I am on leave (from - to), Rahul may approve for me." Optional: only for some document types. */
public class Delegation implements Json.Writable {
    public String id;
    public String fromId;
    public String toId;
    public String start;           // yyyy-mm-dd
    public String end;             // yyyy-mm-dd (inclusive)
    public List<String> types = new java.util.ArrayList<>();   // empty = all types
    public String note;
    public boolean cancelled;
    public long createdAt;

    public boolean covers(LocalDate day, String type) {
        if (cancelled) return false;
        if (day.isBefore(LocalDate.parse(start)) || day.isAfter(LocalDate.parse(end))) return false;
        return types.isEmpty() || type == null || types.contains(type);
    }

    /** Do two date ranges share at least one day? */
    public boolean overlaps(String s2, String e2) {
        return !LocalDate.parse(end).isBefore(LocalDate.parse(s2)) && !LocalDate.parse(e2).isBefore(LocalDate.parse(start));
    }

    @Override public Map<String, Object> toJson() {
        return Json.obj("id", id, "fromId", fromId, "toId", toId, "start", start, "end", end, "types", types,
                "note", note, "cancelled", cancelled, "createdAt", createdAt);
    }

    public static Delegation fromJson(Map<String, Object> m) {
        Delegation d = new Delegation();
        d.id = Json.str(m, "id");
        d.fromId = Json.str(m, "fromId");
        d.toId = Json.str(m, "toId");
        d.start = Json.str(m, "start");
        d.end = Json.str(m, "end");
        d.types = Json.strings(m, "types");
        d.note = Json.str(m, "note");
        d.cancelled = Json.bool(m, "cancelled", false);
        d.createdAt = Json.num(m, "createdAt", 0);
        return d;
    }
}
