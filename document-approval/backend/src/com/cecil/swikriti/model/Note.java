package com.cecil.swikriti.model;

import com.cecil.swikriti.util.Json;

import java.util.Map;

/** A notification inside the app ("PR/26-27/0042 is waiting for you"). There is no e-mail. */
public class Note implements Json.Writable {
    public String id;
    public String userId;
    public String text;
    public String link;     // e.g. "#/doc/D-1042"
    public long at;
    public boolean read;

    @Override public Map<String, Object> toJson() {
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
