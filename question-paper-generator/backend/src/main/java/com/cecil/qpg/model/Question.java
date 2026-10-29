package com.cecil.qpg.model;

import com.cecil.qpg.model.Enums.Bloom;
import com.cecil.qpg.model.Enums.Difficulty;
import com.cecil.qpg.model.Enums.QType;
import com.cecil.qpg.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One question in the question bank. */
public class Question implements Json.JsonAble {
    public String id;
    public String subjectCode;
    public int unit;
    public QType type;
    public String text;
    /** Choices for multiple-choice questions (2 to 6). Empty for other kinds. */
    public List<String> options = new ArrayList<>();
    /** Correct answer (MCQ: the text of the right option) or model answer / key points. */
    public String answer;
    public int marks;
    public Difficulty difficulty;
    public Bloom bloom;
    public List<String> tags = new ArrayList<>();
    public String createdBy;
    public String createdAt;
    public String updatedAt;
    /** How many released papers used it, and when it was last used (to avoid repeating questions). */
    public int timesUsed;
    public String lastUsedOn;
    public boolean archived;

    @Override
    public Object toJson() {
        return Json.map("id", id, "subjectCode", subjectCode, "unit", unit, "type", type, "text", text,
                "options", options, "answer", answer, "marks", marks, "difficulty", difficulty, "bloom", bloom,
                "tags", tags, "createdBy", createdBy, "createdAt", createdAt, "updatedAt", updatedAt,
                "timesUsed", timesUsed, "lastUsedOn", lastUsedOn, "archived", archived);
    }

    public static Question fromJson(Map<String, Object> m) {
        Question q = new Question();
        q.id = Json.str(m, "id");
        q.subjectCode = Json.str(m, "subjectCode");
        q.unit = Json.integer(m, "unit", 1);
        q.type = QType.valueOf(Json.str(m, "type", "SHORT"));
        q.text = Json.str(m, "text", "");
        q.options = Json.strings(m, "options");
        q.answer = Json.str(m, "answer", "");
        q.marks = Json.integer(m, "marks", 1);
        q.difficulty = Difficulty.valueOf(Json.str(m, "difficulty", "MEDIUM"));
        q.bloom = Bloom.valueOf(Json.str(m, "bloom", "UNDERSTAND"));
        q.tags = Json.strings(m, "tags");
        q.createdBy = Json.str(m, "createdBy");
        q.createdAt = Json.str(m, "createdAt");
        q.updatedAt = Json.str(m, "updatedAt");
        q.timesUsed = Json.integer(m, "timesUsed", 0);
        q.lastUsedOn = Json.str(m, "lastUsedOn");
        q.archived = Json.bool(m, "archived", false);
        return q;
    }
}
