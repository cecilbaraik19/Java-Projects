package com.cecil.kaksh.model;

import com.cecil.kaksh.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A repeating meeting (for example "Team stand-up, every weekday at 9:30"). Each date is its own Booking. */
public class Series implements Json.Writable {
    public String id;
    public String organiserId;
    public String roomId;
    public String title;
    /** DAILY, WEEKDAYS (Mon-Fri), WEEKLY */
    public String repeat;
    public int count;
    public List<String> bookingIds = new ArrayList<>();
    /** Dates that were skipped because the room was taken (yyyy-mm-dd). */
    public List<String> skipped = new ArrayList<>();
    public long createdAt;

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "organiserId", organiserId, "roomId", roomId, "title", title, "repeat", repeat,
                "count", count, "bookingIds", bookingIds, "skipped", skipped, "createdAt", createdAt);
    }

    public static Series fromJson(Map<String, Object> m) {
        Series s = new Series();
        s.id = Json.str(m, "id");
        s.organiserId = Json.str(m, "organiserId");
        s.roomId = Json.str(m, "roomId");
        s.title = Json.str(m, "title", "");
        s.repeat = Json.str(m, "repeat", "WEEKLY");
        s.count = (int) Json.num(m, "count", 0);
        s.bookingIds = new ArrayList<>(Json.strings(m, "bookingIds"));
        s.skipped = new ArrayList<>(Json.strings(m, "skipped"));
        s.createdAt = Json.num(m, "createdAt", 0);
        return s;
    }
}
