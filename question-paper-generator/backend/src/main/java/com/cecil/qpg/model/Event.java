package com.cecil.qpg.model;

import com.cecil.qpg.util.Json;

import java.util.Map;

/** One line in the activity history ("Meera approved Mid-Term Set A"). */
public class Event implements Json.JsonAble {
    public String at;
    public String user;
    public String action;
    public String detail;
    /** What it is about, for example "paper:P-0003". */
    public String ref;

    public Event() {}

    public Event(String at, String user, String action, String detail, String ref) {
        this.at = at; this.user = user; this.action = action; this.detail = detail; this.ref = ref;
    }

    @Override
    public Object toJson() {
        return Json.map("at", at, "user", user, "action", action, "detail", detail, "ref", ref);
    }

    public static Event fromJson(Map<String, Object> m) {
        return new Event(Json.str(m, "at"), Json.str(m, "user"), Json.str(m, "action"), Json.str(m, "detail"), Json.str(m, "ref"));
    }
}
