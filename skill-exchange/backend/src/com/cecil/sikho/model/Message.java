package com.cecil.sikho.model;

import com.cecil.sikho.util.Json;

import java.util.Map;

/** One chat message inside an exchange. fromId null = a note written by the system. */
public class Message implements Json.Writable {
    public String fromId;
    public String text;
    public long at;
    /** Set when the safety check found something (money, a phone number before acceptance). */
    public String flag;

    public Message() { }

    public Message(String fromId, String text, long at, String flag) {
        this.fromId = fromId; this.text = text; this.at = at; this.flag = flag;
    }

    @Override
    public Map<String, Object> toJson() { return Json.obj("fromId", fromId, "text", text, "at", at, "flag", flag); }

    public static Message fromJson(Map<String, Object> m) {
        return new Message(Json.str(m, "fromId"), Json.str(m, "text"), Json.num(m, "at", 0), Json.str(m, "flag"));
    }
}
