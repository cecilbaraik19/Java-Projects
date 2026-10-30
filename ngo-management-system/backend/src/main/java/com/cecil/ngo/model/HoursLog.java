package com.cecil.ngo.model;

import com.cecil.ngo.model.Enums.Review;
import com.cecil.ngo.util.Json;

import java.util.Map;

/** Hours a volunteer worked at one activity. A manager approves them before they count. */
public class HoursLog implements Json.JsonAble {
    public String id;
    public String volunteerId;
    public String activityId;
    public String date;
    public double hours;
    public String note;
    public Review status = Review.PENDING;
    public String loggedBy;
    public String reviewedBy;
    public String loggedAt;

    @Override
    public Object toJson() {
        return Json.map("id", id, "volunteerId", volunteerId, "activityId", activityId, "date", date, "hours", hours,
                "note", note, "status", status, "loggedBy", loggedBy, "reviewedBy", reviewedBy, "loggedAt", loggedAt);
    }

    public static HoursLog fromJson(Map<String, Object> m) {
        HoursLog h = new HoursLog();
        h.id = Json.str(m, "id");
        h.volunteerId = Json.str(m, "volunteerId");
        h.activityId = Json.str(m, "activityId");
        h.date = Json.str(m, "date");
        Object hv = m.get("hours");
        h.hours = hv instanceof Number n ? n.doubleValue() : 0;
        h.note = Json.str(m, "note", "");
        h.status = Review.valueOf(Json.str(m, "status", "PENDING"));
        h.loggedBy = Json.str(m, "loggedBy");
        h.reviewedBy = Json.str(m, "reviewedBy");
        h.loggedAt = Json.str(m, "loggedAt");
        return h;
    }
}
