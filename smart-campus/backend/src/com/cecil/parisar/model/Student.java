package com.cecil.parisar.model;

import com.cecil.parisar.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A student. Attendance is not stored here - it is worked out from the class sessions.
 * attendHabit and libraryHabit are only used by the IoT simulator (how often this made-up student comes to class
 * or to the library). They are never shown to the AI model, which has to learn from real behaviour.
 */
public class Student implements Json.Writable {
    public String id;            // "S-1001"
    public String roll;          // "2025-BSCIT/004"
    public String name;
    public String program;       // "B.Sc. IT"
    public int year;
    public String cardId;        // RFID card number, e.g. "RF-7A31C0"
    public String mentorId;      // faculty user id
    public double internalPct;   // internal exam marks, %
    public double assignmentPct; // assignments handed in, %
    public double lmsHours;      // hours per week on the online learning portal
    public List<Long> libraryVisits = new ArrayList<>();   // epoch millis of library gate taps (last 60 days)
    public double attendHabit;   // simulator only
    public double libraryHabit;  // simulator only

    public Map<String, Object> publicView() {
        return Json.obj("id", id, "roll", roll, "name", name, "program", program, "year", year, "cardId", cardId,
                "mentorId", mentorId, "internalPct", internalPct, "assignmentPct", assignmentPct, "lmsHours", lmsHours);
    }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "roll", roll, "name", name, "program", program, "year", year, "cardId", cardId,
                "mentorId", mentorId, "internalPct", internalPct, "assignmentPct", assignmentPct, "lmsHours", lmsHours,
                "libraryVisits", libraryVisits, "attendHabit", attendHabit, "libraryHabit", libraryHabit);
    }

    public static Student fromJson(Map<String, Object> m) {
        Student s = new Student();
        s.id = Json.str(m, "id");
        s.roll = Json.str(m, "roll");
        s.name = Json.str(m, "name");
        s.program = Json.str(m, "program");
        s.year = (int) Json.num(m, "year", 1);
        s.cardId = Json.str(m, "cardId");
        s.mentorId = Json.str(m, "mentorId");
        s.internalPct = Json.dbl(m, "internalPct", 0);
        s.assignmentPct = Json.dbl(m, "assignmentPct", 0);
        s.lmsHours = Json.dbl(m, "lmsHours", 0);
        for (Object o : Json.list(m, "libraryVisits")) if (o instanceof Number n) s.libraryVisits.add(n.longValue());
        s.attendHabit = Json.dbl(m, "attendHabit", 0.8);
        s.libraryHabit = Json.dbl(m, "libraryHabit", 0.3);
        return s;
    }
}
