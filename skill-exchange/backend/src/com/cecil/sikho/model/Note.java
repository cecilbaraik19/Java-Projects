package com.cecil.sikho.model;

import com.cecil.sikho.util.Json;

import java.util.Map;

/** A notification for one member ("Ravi accepted your request"). link = the page to open, e.g. "exchange/X-1001". */
public class Note implements Json.Writable {
    public String id;
    public String userId;
    public String text;
    public String link;
    public long at;
    public boolean read;

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "userId", userId, "text", text, "link", link, "at", at, "read", read);
    }

    public static Note fromJson(Map<String, Object> m) {
        Note n = new Note();
        n.id = Json.str(m, "id");
        n.userId = Json.str(m, "userId");
        n.text = Json.str(m, "text");
        n.link = Json.str(m, "link");
        n.at = Json.num(m, "at", 0);
        n.read = Json.bool(m, "read", false);
        return n;
    }
}
