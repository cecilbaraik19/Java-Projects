package com.cecil.kaushal.model;

import com.cecil.kaushal.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A client or internal project with "slots" to staff: each slot needs some skills and a share of a person's week
 * (pct = 50 means half of their time). Kaushal suggests who should fill the slots (Hungarian algorithm).
 */
public class Project implements Json.Writable {
    public String id;
    public String name;
    public String client;
    public String ownerId;
    public String status = "PLANNED";   // PLANNED, ACTIVE, DONE
    public String start;
    public String end;
    public List<Slot> slots = new ArrayList<>();

    public static class Slot implements Json.Writable {
        public String id;
        public String title;
        public int pct = 100;
        public List<Req> reqs = new ArrayList<>();
        public String assigneeId;
        public boolean stretch;          // staffed although a must-have is missing (a growth chance)

        @Override public Map<String, Object> toJson() {
            return Json.obj("id", id, "title", title, "pct", pct, "reqs", reqs, "assigneeId", assigneeId, "stretch", stretch);
        }

        @SuppressWarnings("unchecked")
        static Slot fromJson(Map<String, Object> m) {
            Slot s = new Slot();
            s.id = Json.str(m, "id");
            s.title = Json.str(m, "title");
            s.pct = (int) Json.num(m, "pct", 100);
            s.reqs = Req.list(Json.list(m, "reqs"));
            s.assigneeId = Json.str(m, "assigneeId");
            s.stretch = Json.bool(m, "stretch", false);
            return s;
        }
    }

    /** Counts against people's time: planned and active projects. */
    public boolean busy() { return !"DONE".equals(status); }

    public Slot slot(String slotId) {
        for (Slot s : slots) if (s.id.equals(slotId)) return s;
        return null;
    }

    @Override public Map<String, Object> toJson() {
        return Json.obj("id", id, "name", name, "client", client, "ownerId", ownerId, "status", status, "start", start,
                "end", end, "slots", slots);
    }

    @SuppressWarnings("unchecked")
    public static Project fromJson(Map<String, Object> m) {
        Project p = new Project();
        p.id = Json.str(m, "id");
        p.name = Json.str(m, "name");
        p.client = Json.str(m, "client");
        p.ownerId = Json.str(m, "ownerId");
        p.status = Json.str(m, "status", "PLANNED");
        p.start = Json.str(m, "start");
        p.end = Json.str(m, "end");
        for (Object o : Json.list(m, "slots")) if (o instanceof Map) p.slots.add(Slot.fromJson((Map<String, Object>) o));
        return p;
    }
}
