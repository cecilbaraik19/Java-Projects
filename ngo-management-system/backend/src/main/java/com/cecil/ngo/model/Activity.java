package com.cecil.ngo.model;

import com.cecil.ngo.model.Enums.ActivityKind;
import com.cecil.ngo.model.Enums.ActivityStatus;
import com.cecil.ngo.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One event on the ground under a program, like "Health camp at Ormanjhi on 12 Nov". */
public class Activity implements Json.JsonAble {
    public String id;
    public String programId;
    public String title;
    public ActivityKind kind;
    public String date;
    public String time;
    public String place;
    public ActivityStatus status = ActivityStatus.PLANNED;
    /** How many volunteers are wanted. Sign-ups stop when this is full. */
    public int volunteersNeeded;
    /** Volunteer ids signed up for this activity. */
    public List<String> volunteerIds = new ArrayList<>();
    /** People reached, filled in when the activity is marked done. */
    public int reached;
    public String outcome;
    public String createdBy;
    public String createdAt;

    public int seatsLeft() { return Math.max(0, volunteersNeeded - volunteerIds.size()); }

    @Override
    public Object toJson() {
        return Json.map("id", id, "programId", programId, "title", title, "kind", kind, "date", date, "time", time,
                "place", place, "status", status, "volunteersNeeded", volunteersNeeded, "volunteerIds", volunteerIds,
                "seatsLeft", seatsLeft(), "reached", reached, "outcome", outcome, "createdBy", createdBy, "createdAt", createdAt);
    }

    public static Activity fromJson(Map<String, Object> m) {
        Activity a = new Activity();
        a.id = Json.str(m, "id");
        a.programId = Json.str(m, "programId");
        a.title = Json.str(m, "title");
        a.kind = ActivityKind.valueOf(Json.str(m, "kind", "CAMP"));
        a.date = Json.str(m, "date");
        a.time = Json.str(m, "time", "");
        a.place = Json.str(m, "place", "");
        a.status = ActivityStatus.valueOf(Json.str(m, "status", "PLANNED"));
        a.volunteersNeeded = Json.integer(m, "volunteersNeeded", 0);
        a.volunteerIds = new ArrayList<>(Json.strings(m, "volunteerIds"));
        a.reached = Json.integer(m, "reached", 0);
        a.outcome = Json.str(m, "outcome", "");
        a.createdBy = Json.str(m, "createdBy");
        a.createdAt = Json.str(m, "createdAt");
        return a;
    }
}
