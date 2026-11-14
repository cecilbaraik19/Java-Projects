package com.cecil.kaksh.model;

import com.cecil.kaksh.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A meeting room. */
public class Room implements Json.Writable {
    /** The equipment a room can have. */
    public static final List<String> EQUIPMENT = List.of("SCREEN", "PROJECTOR", "VIDEO_CALL", "WHITEBOARD", "SPEAKERPHONE", "WHEELCHAIR");

    public String id;
    /** Room number on the door, e.g. "2.04" = floor 2, room 4. */
    public String code;
    public String name;
    public int floor;
    public int capacity;
    public List<String> equipment = new ArrayList<>();
    /** Minutes kept free after every meeting (cleaning, setting up, people leaving). */
    public int bufferMin = 5;
    /** Big rooms (boardroom, training hall) must be approved by the facility manager. */
    public boolean needsApproval;
    public boolean active = true;
    public String note = "";

    public boolean has(String item) { return equipment.contains(item); }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "code", code, "name", name, "floor", floor, "capacity", capacity, "equipment", equipment,
                "bufferMin", bufferMin, "needsApproval", needsApproval, "active", active, "note", note);
    }

    public static Room fromJson(Map<String, Object> m) {
        Room r = new Room();
        r.id = Json.str(m, "id");
        r.code = Json.str(m, "code");
        r.name = Json.str(m, "name");
        r.floor = (int) Json.num(m, "floor", 1);
        r.capacity = (int) Json.num(m, "capacity", 4);
        r.equipment = new ArrayList<>(Json.strings(m, "equipment"));
        r.bufferMin = (int) Json.num(m, "bufferMin", 5);
        r.needsApproval = Json.bool(m, "needsApproval", false);
        r.active = Json.bool(m, "active", true);
        r.note = Json.str(m, "note", "");
        return r;
    }
}
