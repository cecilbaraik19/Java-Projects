package com.cecil.qpg.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.cecil.qpg.util.Json.map;

/** All the fixed lists used by the app, each with a friendly label for the screen. */
public final class Enums {
    private Enums() {}

    /** Who the person is. Each role can do everything the role before it can. */
    public enum Role {
        FACULTY("Faculty"), HOD("Head of Department"), ADMIN("Admin");
        public final String label;
        Role(String l) { label = l; }
        public boolean atLeast(Role r) { return ordinal() >= r.ordinal(); }
    }

    /** Kind of question. "Objective" ones have one fixed correct answer. */
    public enum QType {
        MCQ("Multiple choice", true),
        TRUE_FALSE("True / False", true),
        FILL_BLANK("Fill in the blank", true),
        SHORT("Short answer", false),
        LONG("Long answer", false),
        NUMERICAL("Numerical / problem", false);
        public final String label;
        public final boolean objective;
        QType(String l, boolean o) { label = l; objective = o; }
    }

    public enum Difficulty {
        EASY("Easy"), MEDIUM("Medium"), HARD("Hard");
        public final String label;
        Difficulty(String l) { label = l; }
    }

    /** Bloom's taxonomy: the thinking level a question tests, from simple recall to creating something new. */
    public enum Bloom {
        REMEMBER("Remember", "L1"), UNDERSTAND("Understand", "L2"), APPLY("Apply", "L3"),
        ANALYZE("Analyze", "L4"), EVALUATE("Evaluate", "L5"), CREATE("Create", "L6");
        public final String label;
        public final String level;
        Bloom(String l, String lv) { label = l; level = lv; }
    }

    /** Life of a paper: made -> sent for review -> approved by HOD -> released (locked) for the exam. */
    public enum PaperStatus {
        DRAFT("Draft"), SUBMITTED("Waiting for review"), APPROVED("Approved"), RELEASED("Released");
        public final String label;
        PaperStatus(String l) { label = l; }
    }

    /** Reads an enum from text like "mcq", "Multiple choice" or "MCQ"; gives a clear error if it is wrong. */
    public static <E extends Enum<E>> E parse(Class<E> type, String v, String field) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException(field + " is required");
        String t = v.trim();
        for (E e : type.getEnumConstants()) {
            if (e.name().equalsIgnoreCase(t.replace(' ', '_').replace('/', '_').replace("__", "_"))) return e;
            if (label(e).equalsIgnoreCase(t)) return e;
        }
        if (type == QType.class) {
            String low = t.toLowerCase(Locale.ROOT);
            if (low.equals("tf") || low.startsWith("true")) return type.cast(QType.TRUE_FALSE);
            if (low.startsWith("fill")) return type.cast(QType.FILL_BLANK);
            if (low.startsWith("num") || low.startsWith("problem")) return type.cast(QType.NUMERICAL);
            if (low.startsWith("multiple") || low.equals("objective")) return type.cast(QType.MCQ);
        }
        if (type == Bloom.class) {
            for (Bloom b : Bloom.values()) if (b.level.equalsIgnoreCase(t)) return type.cast(b);
            if (t.equalsIgnoreCase("analyse")) return type.cast(Bloom.ANALYZE);
        }
        throw new IllegalArgumentException(field + " '" + t + "' is not valid");
    }

    public static String label(Enum<?> e) {
        if (e instanceof Role r) return r.label;
        if (e instanceof QType q) return q.label;
        if (e instanceof Difficulty d) return d.label;
        if (e instanceof Bloom b) return b.label;
        if (e instanceof PaperStatus p) return p.label;
        return e.name();
    }

    /** All lists for the frontend's drop-downs. */
    public static Map<String, Object> meta() {
        List<Object> types = new ArrayList<>();
        for (QType q : QType.values()) types.add(map("id", q.name(), "label", q.label, "objective", q.objective));
        List<Object> bl = new ArrayList<>();
        for (Bloom b : Bloom.values()) bl.add(map("id", b.name(), "label", b.label, "level", b.level));
        return map(
                "roles", list(Role.values()),
                "types", types,
                "difficulties", list(Difficulty.values()),
                "blooms", bl,
                "statuses", list(PaperStatus.values()));
    }

    private static List<Object> list(Enum<?>[] values) {
        List<Object> out = new ArrayList<>();
        for (Enum<?> e : values) out.add(map("id", e.name(), "label", label(e)));
        return out;
    }
}
