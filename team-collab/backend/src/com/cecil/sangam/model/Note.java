package com.cecil.sangam.model;

import com.cecil.sangam.util.Json;

import java.util.Map;

/**
 * A notification. It is not shown before "deliverAt": notifications for somebody who is asleep or in focus time
 * wait until their working day starts ("quiet hours"), unless they are marked urgent.
 */
public class Note implements Json.Writable {
    public String id;
    public String userId;
    public String text;
    /** Where the website should go when it is clicked, e.g. "#/task/T-1004". */
    public String link;
    public long at;
    public long deliverAt;
    public boolean read;
    public boolean urgent;

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "userId", userId, "text", text, "link", link, "at", at, "deliverAt", deliverAt,
                "read", read, "urgent", urgent);
    }

    public static Note fromJson(Map<String, Object> m) {
        Note n = new Note();
        n.id = Json.str(m, "id");
        n.userId = Json.str(m, "userId");
        n.text = Json.str(m, "text");
        n.link = Json.str(m, "link");
        n.at = Json.num(m, "at", 0);
        n.deliverAt = Json.num(m, "deliverAt", n.at);
        n.read = Json.bool(m, "read", false);
        n.urgent = Json.bool(m, "urgent", false);
        return n;
    }
}
