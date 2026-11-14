package com.cecil.kaksh.model;

import com.cecil.kaksh.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "Tell me (and book it for me) if this room becomes free at this time."
 * First come, first served: the oldest waiting entry gets the room first.
 */
public class WaitEntry implements Json.Writable {
    public String id;
    public String roomId;
    public String userId;
    public String title;
    public long start;
    public long end;
    public List<String> people = new ArrayList<>();
    public int guests;
    public long createdAt;
    /** WAITING, BOOKED, EXPIRED, CANCELLED */
    public String status = "WAITING";
    public String bookingId;

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "roomId", roomId, "userId", userId, "title", title, "start", start, "end", end,
                "people", people, "guests", guests, "createdAt", createdAt, "status", status, "bookingId", bookingId);
    }

    public static WaitEntry fromJson(Map<String, Object> m) {
        WaitEntry w = new WaitEntry();
        w.id = Json.str(m, "id");
        w.roomId = Json.str(m, "roomId");
        w.userId = Json.str(m, "userId");
        w.title = Json.str(m, "title", "");
        w.start = Json.num(m, "start", 0);
        w.end = Json.num(m, "end", 0);
        w.people = new ArrayList<>(Json.strings(m, "people"));
        w.guests = (int) Json.num(m, "guests", 0);
        w.createdAt = Json.num(m, "createdAt", 0);
        w.status = Json.str(m, "status", "WAITING");
        w.bookingId = Json.str(m, "bookingId");
        return w;
    }
}
