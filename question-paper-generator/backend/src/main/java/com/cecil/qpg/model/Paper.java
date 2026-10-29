package com.cecil.qpg.model;

import com.cecil.qpg.model.Enums.Bloom;
import com.cecil.qpg.model.Enums.Difficulty;
import com.cecil.qpg.model.Enums.PaperStatus;
import com.cecil.qpg.model.Enums.QType;
import com.cecil.qpg.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A generated question paper.
 * Each question is copied into the paper ("snapshot"), so editing the bank later
 * never changes a paper that was already approved or given to students.
 */
public class Paper implements Json.JsonAble {
    public String id;
    public String title;
    public String subjectCode;
    public String blueprintId;
    public String examType;
    public String examDate;
    public int durationMin;
    public String institute;
    public List<String> instructions = new ArrayList<>();
    /** "A", "B", "C"... when several sets are made together; they share a groupId. */
    public String setLabel;
    public String groupId;
    public long seed;
    public List<PSection> sections = new ArrayList<>();
    public PaperStatus status = PaperStatus.DRAFT;
    public List<String> warnings = new ArrayList<>();
    /** Easy / medium / hard percent the blueprint asked for (to compare with what the paper got). */
    public Map<String, Object> targetMix;
    public String createdBy;
    public String createdAt;
    public String updatedAt;
    public String reviewedBy;
    public String reviewNote;
    public String approvedBy;
    public String approvedAt;
    public String releasedAt;

    public static class PSection {
        public String title;
        public String instructions;
        public QType type;
        public int attempt;
        public int marksEach;
        public List<Item> items = new ArrayList<>();

        public int marks() { return Math.min(attempt, items.size()) * marksEach; }

        public Map<String, Object> toJson() {
            List<Object> it = new ArrayList<>();
            for (Item i : items) it.add(i.toJson());
            return Json.map("title", title, "instructions", instructions, "type", type, "attempt", attempt,
                    "marksEach", marksEach, "marks", marks(), "items", it);
        }

        @SuppressWarnings("unchecked")
        public static PSection fromJson(Map<String, Object> m) {
            PSection s = new PSection();
            s.title = Json.str(m, "title", "");
            s.instructions = Json.str(m, "instructions", "");
            s.type = QType.valueOf(Json.str(m, "type", "SHORT"));
            s.attempt = Json.integer(m, "attempt", 1);
            s.marksEach = Json.integer(m, "marksEach", 1);
            List<Object> it = Json.list(m, "items");
            if (it != null) for (Object o : it) s.items.add(Item.fromJson((Map<String, Object>) o));
            return s;
        }
    }

    /** A copy of one bank question as it appears in this paper (options may be shuffled). */
    public static class Item {
        public String questionId;
        public String text;
        public List<String> options = new ArrayList<>();
        public String answer;
        public int marks;
        public int unit;
        public Difficulty difficulty;
        public Bloom bloom;
        public QType type;

        public static Item of(Question q, List<String> shuffledOptions) {
            Item i = new Item();
            i.questionId = q.id;
            i.text = q.text;
            i.options = new ArrayList<>(shuffledOptions == null ? q.options : shuffledOptions);
            i.answer = q.answer;
            i.marks = q.marks;
            i.unit = q.unit;
            i.difficulty = q.difficulty;
            i.bloom = q.bloom;
            i.type = q.type;
            return i;
        }

        /** For MCQ: the letter (a, b, c...) of the right option in this paper's order. */
        public String answerLetter() {
            for (int k = 0; k < options.size(); k++) if (options.get(k).equals(answer)) return String.valueOf((char) ('a' + k));
            return null;
        }

        public Map<String, Object> toJson() {
            return Json.map("questionId", questionId, "text", text, "options", options, "answer", answer,
                    "answerLetter", answerLetter(), "marks", marks, "unit", unit, "difficulty", difficulty,
                    "bloom", bloom, "type", type);
        }

        public static Item fromJson(Map<String, Object> m) {
            Item i = new Item();
            i.questionId = Json.str(m, "questionId");
            i.text = Json.str(m, "text", "");
            i.options = Json.strings(m, "options");
            i.answer = Json.str(m, "answer", "");
            i.marks = Json.integer(m, "marks", 1);
            i.unit = Json.integer(m, "unit", 1);
            i.difficulty = Difficulty.valueOf(Json.str(m, "difficulty", "MEDIUM"));
            i.bloom = Bloom.valueOf(Json.str(m, "bloom", "UNDERSTAND"));
            i.type = QType.valueOf(Json.str(m, "type", "SHORT"));
            return i;
        }
    }

    public int totalMarks() {
        int t = 0;
        for (PSection s : sections) t += s.marks();
        return t;
    }

    public int questionCount() {
        int t = 0;
        for (PSection s : sections) t += s.items.size();
        return t;
    }

    public List<String> questionIds() {
        List<String> ids = new ArrayList<>();
        for (PSection s : sections) for (Item i : s.items) ids.add(i.questionId);
        return ids;
    }

    public boolean editable() { return status == PaperStatus.DRAFT; }

    /** Short version for lists. */
    public Map<String, Object> summary() {
        return Json.map("id", id, "title", title, "subjectCode", subjectCode, "examType", examType,
                "examDate", examDate, "setLabel", setLabel, "groupId", groupId, "status", status,
                "statusLabel", status.label, "totalMarks", totalMarks(), "questionCount", questionCount(),
                "durationMin", durationMin, "warningCount", warnings.size(), "createdBy", createdBy,
                "createdAt", createdAt, "updatedAt", updatedAt, "approvedBy", approvedBy, "releasedAt", releasedAt);
    }

    @Override
    public Object toJson() {
        List<Object> secs = new ArrayList<>();
        for (PSection s : sections) secs.add(s.toJson());
        Map<String, Object> m = summary();
        m.put("blueprintId", blueprintId);
        m.put("institute", institute);
        m.put("instructions", instructions);
        m.put("seed", seed);
        m.put("sections", secs);
        m.put("warnings", warnings);
        m.put("targetMix", targetMix);
        m.put("reviewedBy", reviewedBy);
        m.put("reviewNote", reviewNote);
        m.put("approvedAt", approvedAt);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Paper fromJson(Map<String, Object> m) {
        Paper p = new Paper();
        p.id = Json.str(m, "id");
        p.title = Json.str(m, "title", "");
        p.subjectCode = Json.str(m, "subjectCode");
        p.blueprintId = Json.str(m, "blueprintId");
        p.examType = Json.str(m, "examType", "");
        p.examDate = Json.str(m, "examDate");
        p.durationMin = Json.integer(m, "durationMin", 60);
        p.institute = Json.str(m, "institute", "");
        p.instructions = Json.strings(m, "instructions");
        p.setLabel = Json.str(m, "setLabel", "A");
        p.groupId = Json.str(m, "groupId");
        Long seed = Json.longOrNull(m, "seed");
        p.seed = seed == null ? 0 : seed;
        List<Object> secs = Json.list(m, "sections");
        if (secs != null) for (Object o : secs) p.sections.add(PSection.fromJson((Map<String, Object>) o));
        p.status = PaperStatus.valueOf(Json.str(m, "status", "DRAFT"));
        p.warnings = Json.strings(m, "warnings");
        p.targetMix = Json.obj(m, "targetMix");
        p.createdBy = Json.str(m, "createdBy");
        p.createdAt = Json.str(m, "createdAt");
        p.updatedAt = Json.str(m, "updatedAt");
        p.reviewedBy = Json.str(m, "reviewedBy");
        p.reviewNote = Json.str(m, "reviewNote");
        p.approvedBy = Json.str(m, "approvedBy");
        p.approvedAt = Json.str(m, "approvedAt");
        p.releasedAt = Json.str(m, "releasedAt");
        return p;
    }
}
