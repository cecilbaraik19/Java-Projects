package com.cecil.sangam.model;

import com.cecil.sangam.util.Json;

import java.util.Map;

/**
 * A "follow-the-sun" handoff: at the end of my day I pass the work (the baton) to the colleague whose day
 * is starting, so the project keeps moving while I sleep.
 */
public class Handoff implements Json.Writable {
    public String id;
    public String projectId;
    public String fromId;
    public String toId;
    public String text;
    public long at;
    /** When the receiver gets the notification (the start of their working day). */
    public long deliverAt;
    /** When the receiver pressed "Got it" (0 = not yet). */
    public long ackAt;

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "projectId", projectId, "fromId", fromId, "toId", toId, "text", text, "at", at,
                "deliverAt", deliverAt, "ackAt", ackAt);
    }

    public static Handoff fromJson(Map<String, Object> m) {
        Handoff h = new Handoff();
        h.id = Json.str(m, "id");
        h.projectId = Json.str(m, "projectId");
        h.fromId = Json.str(m, "fromId");
        h.toId = Json.str(m, "toId");
        h.text = Json.str(m, "text", "");
        h.at = Json.num(m, "at", 0);
        h.deliverAt = Json.num(m, "deliverAt", 0);
        h.ackAt = Json.num(m, "ackAt", 0);
        return h;
    }
}
