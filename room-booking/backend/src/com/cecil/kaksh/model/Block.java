package com.cecil.kaksh.model;

import com.cecil.kaksh.util.Json;

import java.util.Map;

/** The room is closed for a while (repairs, deep cleaning, an event). Nobody can book it then. */
public class Block implements Json.Writable {
    public String id;
    public String roomId;
    public long start;
    public long end;
    public String reason;
    public String byId;
    public long createdAt;
    public boolean active = true;

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "roomId", roomId, "start", start, "end", end, "reason", reason, "byId", byId,
                "createdAt", createdAt, "active", active);
    }

    public static Block fromJson(Map<String, Object> m) {
        Block b = new Block();
        b.id = Json.str(m, "id");
        b.roomId = Json.str(m, "roomId");
        b.start = Json.num(m, "start", 0);
        b.end = Json.num(m, "end", 0);
        b.reason = Json.str(m, "reason", "");
        b.byId = Json.str(m, "byId");
        b.createdAt = Json.num(m, "createdAt", 0);
        b.active = Json.bool(m, "active", true);
        return b;
    }
}
