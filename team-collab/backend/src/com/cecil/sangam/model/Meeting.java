package com.cecil.sangam.model;

import com.cecil.sangam.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A video call. "costs" says how bad the time was for each person (0 = in working hours ... 5 = middle of the night);
 * these points are added to the person's "pain" so the next meeting is kinder to them.
 */
public class Meeting implements Json.Writable {
    public String id;
    public String title;
    public String organizerId;
    public List<String> attendeeIds = new ArrayList<>();
    public long start, end;
    public String projectId;
    public String agenda = "";
    public boolean cancelled;
    public Map<String, Integer> costs = new LinkedHashMap<>();
    public long createdAt;

    public boolean involves(String userId) { return userId.equals(organizerId) || attendeeIds.contains(userId); }

    /** Everybody in the call, organiser first. */
    public List<String> everyone() {
        List<String> all = new ArrayList<>();
        all.add(organizerId);
        for (String a : attendeeIds) if (!all.contains(a)) all.add(a);
        return all;
    }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "title", title, "organizerId", organizerId, "attendeeIds", attendeeIds,
                "start", start, "end", end, "projectId", projectId, "agenda", agenda, "cancelled", cancelled,
                "costs", costs, "createdAt", createdAt);
    }

    public static Meeting fromJson(Map<String, Object> m) {
        Meeting x = new Meeting();
        x.id = Json.str(m, "id");
        x.title = Json.str(m, "title");
        x.organizerId = Json.str(m, "organizerId");
        x.attendeeIds = new ArrayList<>(Json.strings(m, "attendeeIds"));
        x.start = Json.num(m, "start", 0);
        x.end = Json.num(m, "end", 0);
        x.projectId = Json.str(m, "projectId");
        x.agenda = Json.str(m, "agenda", "");
        x.cancelled = Json.bool(m, "cancelled", false);
        if (m.get("costs") instanceof Map<?, ?> c)
            for (Map.Entry<?, ?> e : c.entrySet()) if (e.getValue() instanceof Number n) x.costs.put(String.valueOf(e.getKey()), n.intValue());
        x.createdAt = Json.num(m, "createdAt", 0);
        return x;
    }
}
