package com.cecil.dainandini.model;

import com.cecil.dainandini.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Something in the calendar that takes time: a meeting, a blocked slot or leave. */
public class CalEvent implements Json.Writable {
    public enum Kind { MEETING, BLOCK, LEAVE }

    public String id;
    public String ownerId;
    public List<String> attendeeIds = new ArrayList<>();
    public String title;
    public Kind kind = Kind.MEETING;
    public long start;
    public long end;
    public long createdAt;

    public boolean involves(String userId) { return userId.equals(ownerId) || attendeeIds.contains(userId); }
    public int minutes() { return (int) ((end - start) / 60_000); }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "ownerId", ownerId, "attendeeIds", attendeeIds, "title", title, "kind", kind,
                "start", start, "end", end, "createdAt", createdAt);
    }

    public static CalEvent fromJson(Map<String, Object> m) {
        CalEvent e = new CalEvent();
        e.id = Json.str(m, "id");
        e.ownerId = Json.str(m, "ownerId");
        e.attendeeIds = new ArrayList<>(Json.strings(m, "attendeeIds"));
        e.title = Json.str(m, "title", "");
        e.kind = Kind.valueOf(Json.str(m, "kind", "MEETING"));
        e.start = Json.num(m, "start", 0);
        e.end = Json.num(m, "end", 0);
        e.createdAt = Json.num(m, "createdAt", 0);
        return e;
    }
}
