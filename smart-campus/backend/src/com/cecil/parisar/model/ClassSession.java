package com.cecil.parisar.model;

import com.cecil.parisar.util.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One class that took place (or is taking place now). Students are marked present when their RFID card is
 * tapped at the room's gate reader while the class is open, or by the teacher by hand.
 */
public class ClassSession implements Json.Writable {
    public String id;
    public String courseId;
    public String roomId;
    public String date;          // "2026-11-10"
    public int hour;             // start hour
    public long startedAt;
    public long endedAt;         // 0 while the class is open
    public String source;        // TIMETABLE (opened automatically) or EXTRA (started by a teacher)
    /** studentId -> "RFID@<millis>" or "MANUAL@<millis>" */
    public Map<String, String> present = new LinkedHashMap<>();

    public boolean isOpen() { return endedAt == 0; }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "courseId", courseId, "roomId", roomId, "date", date, "hour", hour,
                "startedAt", startedAt, "endedAt", endedAt, "source", source, "present", present);
    }

    public static ClassSession fromJson(Map<String, Object> m) {
        ClassSession s = new ClassSession();
        s.id = Json.str(m, "id");
        s.courseId = Json.str(m, "courseId");
        s.roomId = Json.str(m, "roomId");
        s.date = Json.str(m, "date");
        s.hour = (int) Json.num(m, "hour", 9);
        s.startedAt = Json.num(m, "startedAt", 0);
        s.endedAt = Json.num(m, "endedAt", 0);
        s.source = Json.str(m, "source", "TIMETABLE");
        Object p = m.get("present");
        if (p instanceof Map<?, ?> pm) for (Map.Entry<?, ?> e : pm.entrySet()) s.present.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        return s;
    }
}
