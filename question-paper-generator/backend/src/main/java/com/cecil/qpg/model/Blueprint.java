package com.cecil.qpg.model;

import com.cecil.qpg.model.Enums.Difficulty;
import com.cecil.qpg.model.Enums.QType;
import com.cecil.qpg.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A blueprint (paper pattern) says what the paper should look like:
 * its sections, how many questions of which kind and marks, the easy / medium / hard mix,
 * and which units it covers. The generator fills it with questions from the bank.
 */
public class Blueprint implements Json.JsonAble {
    public String id;
    public String name;
    public String subjectCode;
    public String examType;
    public int durationMin;
    public List<String> instructions = new ArrayList<>();
    public List<Section> sections = new ArrayList<>();
    /** Percent of questions that should be easy / medium / hard. Adds up to 100. */
    public Map<Difficulty, Integer> mix = new LinkedHashMap<>();
    /** Units to take questions from. Empty means all units. */
    public List<Integer> units = new ArrayList<>();
    /** Try not to use questions that appeared in a released paper in the last N days (0 = off). */
    public int avoidRecentDays;
    public String createdBy;
    public String createdAt;
    public String updatedAt;

    public static class Section {
        public String title;
        public String instructions;
        public QType type;
        /** Questions printed in this section. */
        public int count;
        /** Questions the student must answer (equal to count when there is no choice). */
        public int attempt;
        public int marksEach;

        public int marks() { return attempt * marksEach; }

        public Map<String, Object> toJson() {
            return Json.map("title", title, "instructions", instructions, "type", type, "count", count,
                    "attempt", attempt, "marksEach", marksEach, "marks", marks());
        }

        public static Section fromJson(Map<String, Object> m) {
            Section s = new Section();
            s.title = Json.str(m, "title", "");
            s.instructions = Json.str(m, "instructions", "");
            s.type = QType.valueOf(Json.str(m, "type", "SHORT"));
            s.count = Json.integer(m, "count", 1);
            s.attempt = Json.integer(m, "attempt", s.count);
            s.marksEach = Json.integer(m, "marksEach", 1);
            return s;
        }
    }

    /** Marks the student can score (only the questions they must attempt count). */
    public int totalMarks() {
        int t = 0;
        for (Section s : sections) t += s.marks();
        return t;
    }

    public int questionCount() {
        int t = 0;
        for (Section s : sections) t += s.count;
        return t;
    }

    @Override
    public Object toJson() {
        List<Object> secs = new ArrayList<>();
        for (Section s : sections) secs.add(s.toJson());
        Map<String, Object> mx = new LinkedHashMap<>();
        for (Difficulty d : Difficulty.values()) mx.put(d.name(), mix.getOrDefault(d, 0));
        return Json.map("id", id, "name", name, "subjectCode", subjectCode, "examType", examType,
                "durationMin", durationMin, "instructions", instructions, "sections", secs, "mix", mx,
                "units", units, "avoidRecentDays", avoidRecentDays, "totalMarks", totalMarks(),
                "questionCount", questionCount(), "createdBy", createdBy, "createdAt", createdAt, "updatedAt", updatedAt);
    }

    @SuppressWarnings("unchecked")
    public static Blueprint fromJson(Map<String, Object> m) {
        Blueprint b = new Blueprint();
        b.id = Json.str(m, "id");
        b.name = Json.str(m, "name", "");
        b.subjectCode = Json.str(m, "subjectCode");
        b.examType = Json.str(m, "examType", "");
        b.durationMin = Json.integer(m, "durationMin", 60);
        b.instructions = Json.strings(m, "instructions");
        List<Object> secs = Json.list(m, "sections");
        if (secs != null) for (Object o : secs) b.sections.add(Section.fromJson((Map<String, Object>) o));
        Map<String, Object> mx = Json.obj(m, "mix");
        for (Difficulty d : Difficulty.values()) b.mix.put(d, mx == null ? 0 : Json.integer(mx, d.name(), 0));
        List<Object> us = Json.list(m, "units");
        if (us != null) for (Object o : us) b.units.add(((Number) o).intValue());
        b.avoidRecentDays = Json.integer(m, "avoidRecentDays", 0);
        b.createdBy = Json.str(m, "createdBy");
        b.createdAt = Json.str(m, "createdAt");
        b.updatedAt = Json.str(m, "updatedAt");
        return b;
    }
}
