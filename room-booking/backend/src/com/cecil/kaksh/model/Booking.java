package com.cecil.kaksh.model;

import com.cecil.kaksh.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One meeting in one room. Times are milliseconds since 1970 (UTC); the screen shows them in India time.
 * A booking uses the room from start to end, and the room's buffer minutes after that.
 */
public class Booking implements Json.Writable {
    public String id;
    public String roomId;
    public String organiserId;
    public String title;
    public long start;
    public long end;
    /** Colleagues invited (user ids). The organiser is not in this list. */
    public List<String> people = new ArrayList<>();
    /** Visitors from outside (only a number). */
    public int guests;
    /** Equipment the organiser asked for (used to pick a new room if this one is closed for repairs). */
    public List<String> needs = new ArrayList<>();
    /** Private: others see "Private meeting" instead of the title. */
    public boolean privateTitle;
    public Status status = Status.CONFIRMED;
    /** Set when the booking is one of a repeating series. */
    public String seriesId;
    public long createdAt;
    public long checkedInAt;
    /** Who decided an approval, when and why (or why it was cancelled). */
    public String decidedBy;
    public String reason = "";
    /** Goes up by one on every change. An edit must send the version it saw, so two people cannot overwrite each other. */
    public int version = 1;
    /** Small history: [{at, by, text}] */
    public List<Map<String, Object>> history = new ArrayList<>();

    public int headcount() { return 1 + people.size() + guests; }

    public long minutes() { return (end - start) / 60_000; }

    public boolean involves(String userId) {
        return userId != null && (userId.equals(organiserId) || people.contains(userId));
    }

    public void note(long at, String by, String text) {
        history.add(Json.obj("at", at, "by", by, "text", text));
        if (history.size() > 30) history.remove(0);
    }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "roomId", roomId, "organiserId", organiserId, "title", title, "start", start, "end", end,
                "people", people, "guests", guests, "needs", needs, "privateTitle", privateTitle, "status", status, "seriesId", seriesId,
                "createdAt", createdAt, "checkedInAt", checkedInAt, "decidedBy", decidedBy, "reason", reason,
                "version", version, "history", history);
    }

    @SuppressWarnings("unchecked")
    public static Booking fromJson(Map<String, Object> m) {
        Booking b = new Booking();
        b.id = Json.str(m, "id");
        b.roomId = Json.str(m, "roomId");
        b.organiserId = Json.str(m, "organiserId");
        b.title = Json.str(m, "title", "");
        b.start = Json.num(m, "start", 0);
        b.end = Json.num(m, "end", 0);
        b.people = new ArrayList<>(Json.strings(m, "people"));
        b.guests = (int) Json.num(m, "guests", 0);
        b.needs = new ArrayList<>(Json.strings(m, "needs"));
        b.privateTitle = Json.bool(m, "privateTitle", false);
        b.status = Status.valueOf(Json.str(m, "status", "CONFIRMED"));
        b.seriesId = Json.str(m, "seriesId");
        b.createdAt = Json.num(m, "createdAt", 0);
        b.checkedInAt = Json.num(m, "checkedInAt", 0);
        b.decidedBy = Json.str(m, "decidedBy");
        b.reason = Json.str(m, "reason", "");
        b.version = (int) Json.num(m, "version", 1);
        for (Object o : Json.list(m, "history")) if (o instanceof Map<?, ?> h) b.history.add((Map<String, Object>) h);
        return b;
    }
}
