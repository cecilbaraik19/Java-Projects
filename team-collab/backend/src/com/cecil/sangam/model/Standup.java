package com.cecil.sangam.model;

import com.cecil.sangam.util.Json;

import java.util.Map;

/**
 * An async stand-up: three short answers, written once per day in the person's OWN local date.
 * Nobody has to be awake at the same time for it.
 */
public class Standup implements Json.Writable {
    public String id;
    public String userId;
    /** The person's local date, yyyy-mm-dd. */
    public String date;
    public String yesterday = "";
    public String today = "";
    public String blockers = "";
    public long at;

    public boolean blocked() { return blockers != null && !blockers.isBlank() && !blockers.trim().equalsIgnoreCase("none"); }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "userId", userId, "date", date, "yesterday", yesterday, "today", today,
                "blockers", blockers, "at", at);
    }

    public static Standup fromJson(Map<String, Object> m) {
        Standup s = new Standup();
        s.id = Json.str(m, "id");
        s.userId = Json.str(m, "userId");
        s.date = Json.str(m, "date");
        s.yesterday = Json.str(m, "yesterday", "");
        s.today = Json.str(m, "today", "");
        s.blockers = Json.str(m, "blockers", "");
        s.at = Json.num(m, "at", 0);
        return s;
    }
}
