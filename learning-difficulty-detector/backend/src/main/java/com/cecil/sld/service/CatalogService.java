package com.cecil.sld.service;

import com.cecil.sld.model.*;
import com.cecil.sld.store.DataStore;
import com.cecil.sld.util.Text;

import java.util.*;

/**
 * Subjects, topics and the question bank (the "survey" of what can be learned).
 * Teachers and the admin can change them; students only read them.
 */
public class CatalogService {
    public static final Set<String> COLORS = Set.of("moss", "river", "clay", "heather", "ochre", "slate");

    private final DataStore db;

    public CatalogService(DataStore db) { this.db = db; }

    // ------------------------------------------------------------ subjects

    public synchronized List<Map<String, Object>> subjects(boolean includeInactive) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Subject s : db.subjects) {
            if (!s.active && !includeInactive) continue;
            int topics = 0, qs = 0;
            for (Topic t : db.topics) if (t.subjectId.equals(s.id) && t.active) topics++;
            for (Question q : db.questions) {
                Topic t = db.topic(q.topicId);
                if (q.active && t != null && t.subjectId.equals(s.id)) qs++;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id);
            m.put("code", s.code);
            m.put("name", s.name);
            m.put("description", s.description);
            m.put("color", s.color);
            m.put("active", s.active);
            m.put("topics", topics);
            m.put("questions", qs);
            out.add(m);
        }
        return out;
    }

    public synchronized Subject saveSubject(User by, String id, Map<String, Object> in) {
        String code = Text.cut(str(in.get("code")), 12).toUpperCase();
        String name = Text.cut(str(in.get("name")), 80);
        String color = str(in.get("color"));
        if (code.isEmpty() || !code.matches("[A-Z0-9-]{2,12}")) throw ApiError.bad("Code must be 2-12 letters or numbers, for example CSIT135.");
        if (name.length() < 3) throw ApiError.bad("Please enter the subject name.");
        if (!COLORS.contains(color)) color = "moss";
        for (Subject o : db.subjects)
            if (o.code.equalsIgnoreCase(code) && !o.id.equals(id)) throw ApiError.conflict("Another subject already uses the code " + code + ".");
        Subject s;
        if (id == null) {
            s = new Subject();
            s.id = db.nextId("S");
            db.subjects.add(s);
        } else {
            s = db.subject(id);
            if (s == null) throw ApiError.notFound("Subject");
        }
        s.code = code;
        s.name = name;
        s.color = color;
        s.description = Text.cut(str(in.get("description")), 300);
        if (in.containsKey("active")) s.active = Boolean.TRUE.equals(in.get("active"));
        db.log(by.name, id == null ? "Added subject" : "Edited subject", code + " " + name);
        db.save("subjects", "events");
        return s;
    }

    // ------------------------------------------------------------ topics

    public synchronized List<Map<String, Object>> topics(String subjectId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Topic t : db.topicsOf(subjectId)) {
            int qs = 0;
            for (Question q : db.questions) if (q.topicId.equals(t.id) && q.active) qs++;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("topic", t);
            m.put("questions", qs);
            List<String> names = new ArrayList<>();
            for (String p : t.prerequisiteIds) { Topic pt = db.topic(p); if (pt != null) names.add(pt.name); }
            m.put("prerequisites", names);
            out.add(m);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public synchronized Topic saveTopic(User by, String id, Map<String, Object> in) {
        String subjectId = str(in.get("subjectId"));
        Subject s = db.subject(subjectId);
        if (s == null) throw ApiError.bad("Choose a subject.");
        String name = Text.cut(str(in.get("name")), 80);
        if (name.length() < 2) throw ApiError.bad("Please enter the topic name.");
        int unit = num(in.get("unit"), 1);
        int order = num(in.get("order"), 1);
        int expected = num(in.get("expectedSeconds"), 60);
        if (unit < 1 || unit > 12) throw ApiError.bad("Unit must be from 1 to 12.");
        if (expected < 10 || expected > 600) throw ApiError.bad("Expected time must be from 10 to 600 seconds per question.");
        for (Topic o : db.topics)
            if (o.subjectId.equals(subjectId) && o.name.equalsIgnoreCase(name) && !o.id.equals(id))
                throw ApiError.conflict("This subject already has a topic called " + name + ".");

        List<String> pre = new ArrayList<>();
        Object rawPre = in.get("prerequisiteIds");
        if (rawPre instanceof List<?> l) for (Object o : l) {
            String pid = String.valueOf(o);
            Topic pt = db.topic(pid);
            if (pt == null || !pt.subjectId.equals(subjectId)) throw ApiError.bad("A prerequisite must be a topic of the same subject.");
            if (pid.equals(id)) throw ApiError.bad("A topic cannot be its own prerequisite.");
            if (!pre.contains(pid)) pre.add(pid);
        }
        if (id != null && makesLoop(id, pre))
            throw ApiError.bad("These prerequisites would make a loop (A needs B and B needs A). Remove one.");

        Topic t;
        if (id == null) {
            t = new Topic();
            t.id = db.nextId("T");
            db.topics.add(t);
        } else {
            t = db.topic(id);
            if (t == null) throw ApiError.notFound("Topic");
            if (!t.subjectId.equals(subjectId)) throw ApiError.bad("A topic cannot be moved to another subject.");
        }
        t.subjectId = subjectId;
        t.name = name;
        t.unit = unit;
        t.order = order;
        t.expectedSeconds = expected;
        t.prerequisiteIds = pre;
        t.tip = Text.cut(str(in.get("tip")), 300);
        if (in.containsKey("active")) t.active = Boolean.TRUE.equals(in.get("active"));
        db.log(by.name, id == null ? "Added topic" : "Edited topic", s.code + " - " + name);
        db.save("topics", "events");
        return t;
    }

    /** Would giving topic {@code id} these prerequisites create a cycle? */
    boolean makesLoop(String id, List<String> newPre) {
        Deque<String> stack = new ArrayDeque<>(newPre);
        Set<String> seen = new HashSet<>();
        while (!stack.isEmpty()) {
            String cur = stack.pop();
            if (cur.equals(id)) return true;
            if (!seen.add(cur)) continue;
            Topic t = db.topic(cur);
            if (t != null) stack.addAll(t.prerequisiteIds);
        }
        return false;
    }

    public synchronized String deleteTopic(User by, String id) {
        Topic t = db.topic(id);
        if (t == null) throw ApiError.notFound("Topic");
        boolean used = false;
        for (Attempt a : db.attempts) for (AttemptItem it : a.items) if (it.topicId.equals(id)) { used = true; break; }
        boolean hasQ = db.questions.stream().anyMatch(q -> q.topicId.equals(id));
        boolean isPre = db.topics.stream().anyMatch(o -> o.prerequisiteIds.contains(id));
        if (used || hasQ || isPre) {
            t.active = false;
            db.log(by.name, "Turned off topic", t.name);
            db.save("topics", "events");
            return "This topic has questions or answers, so it was turned off instead of deleted.";
        }
        db.topics.remove(t);
        db.log(by.name, "Deleted topic", t.name);
        db.save("topics", "events");
        return "Topic deleted.";
    }

    // ------------------------------------------------------------ questions

    public synchronized List<Question> questions(String subjectId, String topicId) {
        List<Question> out = new ArrayList<>();
        for (Question q : db.questions) {
            Topic t = db.topic(q.topicId);
            if (t == null) continue;
            if (topicId != null && !q.topicId.equals(topicId)) continue;
            if (subjectId != null && !t.subjectId.equals(subjectId)) continue;
            out.add(q);
        }
        return out;
    }

    public synchronized Question saveQuestion(User by, String id, Map<String, Object> in) {
        Topic t = db.topic(str(in.get("topicId")));
        if (t == null) throw ApiError.bad("Choose a topic.");
        String text = Text.cut(str(in.get("text")), 600);
        if (text.length() < 5) throw ApiError.bad("Please write the question.");
        List<String> opts = new ArrayList<>();
        if (in.get("options") instanceof List<?> l) for (Object o : l) {
            String s = Text.cut(String.valueOf(o == null ? "" : o), 200);
            if (!s.isEmpty()) opts.add(s);
        }
        if (opts.size() < 2 || opts.size() > 5) throw ApiError.bad("A question needs 2 to 5 options.");
        Set<String> seen = new HashSet<>();
        for (String o : opts) if (!seen.add(o.toLowerCase())) throw ApiError.bad("Two options are the same: " + o);
        int answer = num(in.get("answer"), -1);
        if (answer < 0 || answer >= opts.size()) throw ApiError.bad("Choose which option is correct.");
        int level = num(in.get("level"), 2);
        if (level < 1 || level > 3) throw ApiError.bad("Level must be 1 (easy), 2 (medium) or 3 (hard).");

        Question q;
        if (id == null) {
            q = new Question();
            q.id = db.nextId("Q");
            db.questions.add(q);
        } else {
            q = db.question(id);
            if (q == null) throw ApiError.notFound("Question");
            boolean answered = db.attempts.stream().anyMatch(a -> a.items.stream().anyMatch(it -> it.questionId.equals(q.id)));
            if (answered && (opts.size() != q.options.size() || answer != q.answer))
                throw ApiError.conflict("Students have already answered this question, so its options and right answer cannot change. Turn it off and add a new one instead.");
        }
        q.topicId = t.id;
        q.text = text;
        q.options = opts;
        q.answer = answer;
        q.level = level;
        q.hint = Text.cut(str(in.get("hint")), 300);
        q.explanation = Text.cut(str(in.get("explanation")), 600);
        if (in.containsKey("active")) q.active = Boolean.TRUE.equals(in.get("active"));
        db.log(by.name, id == null ? "Added question" : "Edited question", t.name + ": " + Text.cut(text, 60));
        db.save("questions", "events");
        return q;
    }

    public synchronized String deleteQuestion(User by, String id) {
        Question q = db.question(id);
        if (q == null) throw ApiError.notFound("Question");
        boolean answered = db.attempts.stream().anyMatch(a -> a.items.stream().anyMatch(it -> it.questionId.equals(id)));
        if (answered) {
            q.active = false;
            db.log(by.name, "Turned off question", Text.cut(q.text, 60));
            db.save("questions", "events");
            return "Students have answered this question, so it was turned off instead of deleted.";
        }
        db.questions.remove(q);
        db.log(by.name, "Deleted question", Text.cut(q.text, 60));
        db.save("questions", "events");
        return "Question deleted.";
    }

    // ------------------------------------------------------------ helpers

    static String str(Object o) { return o == null ? "" : Text.clean(String.valueOf(o)); }

    static int num(Object o, int dflt) {
        if (o instanceof Number n) return n.intValue();
        if (o == null || String.valueOf(o).isBlank()) return dflt;
        try { return (int) Double.parseDouble(String.valueOf(o)); } catch (NumberFormatException e) { throw ApiError.bad("'" + o + "' is not a number."); }
    }
}
