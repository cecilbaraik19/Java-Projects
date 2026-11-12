package com.cecil.sikho.model;

import com.cecil.sikho.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A "skill circle": a small group session (workshop) in a public place. Each person who joins pays 1 credit
 * (held in escrow). When the host marks it done, the host gets all of them.
 */
public class Circle implements Json.Writable {
    public enum Status { OPEN, DONE, CANCELLED }

    public String id;
    public String hostId;
    public String skillId;
    public String title;
    public String details;
    public String place;
    public String localityId;
    public long at;
    public int hours = 2;
    public int seats = 8;
    public List<String> attendees = new ArrayList<>();
    public Status status = Status.OPEN;
    public long createdAt;
    public long closedAt;

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "hostId", hostId, "skillId", skillId, "title", title, "details", details, "place", place,
                "localityId", localityId, "at", at, "hours", hours, "seats", seats, "attendees", attendees,
                "status", status, "createdAt", createdAt, "closedAt", closedAt);
    }

    public static Circle fromJson(Map<String, Object> m) {
        Circle c = new Circle();
        c.id = Json.str(m, "id");
        c.hostId = Json.str(m, "hostId");
        c.skillId = Json.str(m, "skillId");
        c.title = Json.str(m, "title");
        c.details = Json.str(m, "details");
        c.place = Json.str(m, "place");
        c.localityId = Json.str(m, "localityId");
        c.at = Json.num(m, "at", 0);
        c.hours = (int) Json.num(m, "hours", 2);
        c.seats = (int) Json.num(m, "seats", 8);
        c.attendees = Json.strings(m, "attendees");
        c.status = Listing.parse(Status.class, Json.str(m, "status"), Status.OPEN);
        c.createdAt = Json.num(m, "createdAt", 0);
        c.closedAt = Json.num(m, "closedAt", 0);
        return c;
    }
}
