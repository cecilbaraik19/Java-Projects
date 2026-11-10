package com.cecil.parisar.model;

import com.cecil.parisar.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A course with its weekly timetable. Each slot is one hour: day 1 = Monday ... 5 = Friday, hour 9 = 9:00-10:00. */
public class Course implements Json.Writable {
    public String id;            // the course code, e.g. "CSIT135"
    public String name;
    public String program;       // "B.Sc. IT", or "All" for a common course
    public String facultyId;
    public String roomId;
    public List<int[]> slots = new ArrayList<>();      // {day, hour}
    public List<String> studentIds = new ArrayList<>();

    public boolean hasSlot(int day, int hour) {
        for (int[] s : slots) if (s[0] == day && s[1] == hour) return true;
        return false;
    }

    public List<Object> slotsJson() {
        List<Object> out = new ArrayList<>();
        for (int[] s : slots) out.add(Json.obj("day", s[0], "hour", s[1]));
        return out;
    }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "name", name, "program", program, "facultyId", facultyId, "roomId", roomId,
                "slots", slotsJson(), "studentIds", studentIds);
    }

    @SuppressWarnings("unchecked")
    public static Course fromJson(Map<String, Object> m) {
        Course c = new Course();
        c.id = Json.str(m, "id");
        c.name = Json.str(m, "name");
        c.program = Json.str(m, "program");
        c.facultyId = Json.str(m, "facultyId");
        c.roomId = Json.str(m, "roomId");
        for (Object o : Json.list(m, "slots"))
            if (o instanceof Map<?, ?> s) c.slots.add(new int[]{(int) Json.num((Map<String, Object>) s, "day", 1),
                    (int) Json.num((Map<String, Object>) s, "hour", 9)});
        c.studentIds.addAll(Json.strings(m, "studentIds"));
        return c;
    }
}
