package com.cecil.sld.service;

import com.cecil.sld.model.*;
import com.cecil.sld.store.DataStore;
import com.cecil.sld.util.Text;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Support plans: what a teacher will do to help one student with one difficult topic.
 * The Difficulty Index is saved when the plan starts, so later we can see whether it helped.
 */
public class PlanService {
    public static final List<String> ACTIONS = List.of(
            "Extra class", "Worksheet", "Peer tutor", "One-to-one doubt session", "Revise prerequisite", "Video lesson");

    private final DataStore db;
    private final DifficultyEngine engine;

    public PlanService(DataStore db, DifficultyEngine engine) {
        this.db = db;
        this.engine = engine;
    }

    /** Plans with the index now, so the change can be shown. Students only get their own. */
    public synchronized List<Map<String, Object>> list(User viewer, String status) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SupportPlan p : db.plans) {
            if (viewer.role == Role.STUDENT && !p.studentId.equals(viewer.id)) continue;
            if (status != null && !status.isBlank() && !status.equals(p.status)) continue;
            out.add(view(p));
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> !"OPEN".equals(m.get("status")))
                .thenComparing(m -> String.valueOf(m.get("dueDate"))));
        return out;
    }

    private Map<String, Object> view(SupportPlan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        User st = db.user(p.studentId);
        Topic t = db.topic(p.topicId);
        Subject s = t == null ? null : db.subject(t.subjectId);
        m.put("id", p.id);
        m.put("studentId", p.studentId);
        m.put("student", st == null ? "?" : st.name);
        m.put("rollNo", st == null ? "" : st.rollNo);
        m.put("topicId", p.topicId);
        m.put("topic", t == null ? "?" : t.name);
        m.put("subject", s == null ? "" : s.code);
        m.put("color", s == null ? "moss" : s.color);
        m.put("action", p.action);
        m.put("note", p.note);
        m.put("dueDate", p.dueDate);
        m.put("status", p.status);
        m.put("overdue", "OPEN".equals(p.status) && p.dueDate.compareTo(db.today().toString()) < 0);
        m.put("createdBy", p.createdBy);
        m.put("createdAt", p.createdAt);
        m.put("doneAt", p.doneAt);
        m.put("indexAtStart", p.indexAtStart);
        double now = -1;
        if (st != null && t != null) {
            DifficultyEngine.TopicResult r = engine.forStudentTopic(st.id, t.id);
            if (r != null) now = r.index;
        }
        m.put("indexNow", now);
        return m;
    }

    public synchronized SupportPlan create(User by, Map<String, Object> in) {
        User st = db.user(CatalogService.str(in.get("studentId")));
        if (st == null || st.role != Role.STUDENT) throw ApiError.bad("Choose a student.");
        Topic t = db.topic(CatalogService.str(in.get("topicId")));
        if (t == null || !t.active) throw ApiError.bad("Choose a topic.");
        String action = CatalogService.str(in.get("action"));
        if (!ACTIONS.contains(action)) throw ApiError.bad("Choose what kind of help this is.");
        LocalDate due = date(in.get("dueDate"));
        if (due.isBefore(db.today())) throw ApiError.bad("The due date cannot be in the past.");
        if (due.isAfter(db.today().plusDays(120))) throw ApiError.bad("The due date must be within 4 months.");
        for (SupportPlan p : db.plans)
            if (p.studentId.equals(st.id) && p.topicId.equals(t.id) && "OPEN".equals(p.status))
                throw ApiError.conflict(st.name + " already has an open plan for " + t.name + ". Finish that one first.");
        DifficultyEngine.TopicResult r = engine.forStudentTopic(st.id, t.id);
        SupportPlan p = new SupportPlan();
        p.id = db.nextId("P");
        p.studentId = st.id;
        p.topicId = t.id;
        p.action = action;
        p.note = Text.cut(CatalogService.str(in.get("note")), 400);
        p.dueDate = due.toString();
        p.indexAtStart = r == null ? -1 : r.index;
        p.createdBy = by.name;
        p.createdAt = db.nowText();
        db.plans.add(p);
        db.log(by.name, "Made a support plan", st.name + " - " + t.name + " (" + action + ")");
        db.save("plans", "events");
        return p;
    }

    public synchronized SupportPlan setStatus(User by, String id, String status) {
        SupportPlan p = db.plan(id);
        if (p == null) throw ApiError.notFound("Plan");
        if (!"OPEN".equals(status) && !"DONE".equals(status)) throw ApiError.bad("Status must be OPEN or DONE.");
        if (status.equals(p.status)) return p;
        p.status = status;
        p.doneAt = status.equals("DONE") ? db.nowText() : null;
        Topic t = db.topic(p.topicId);
        db.log(by.name, status.equals("DONE") ? "Finished a support plan" : "Re-opened a support plan",
                db.user(p.studentId).name + " - " + (t == null ? "?" : t.name));
        db.save("plans", "events");
        return p;
    }

    public synchronized void delete(User by, String id) {
        SupportPlan p = db.plan(id);
        if (p == null) throw ApiError.notFound("Plan");
        if (by.role != Role.ADMIN && !by.name.equals(p.createdBy)) throw new ApiError(403, "Only the teacher who made this plan, or the admin, can delete it.");
        db.plans.remove(p);
        db.log(by.name, "Deleted a support plan", p.action);
        db.save("plans", "events");
    }

    static LocalDate date(Object o) {
        try {
            return LocalDate.parse(CatalogService.str(o));
        } catch (DateTimeParseException e) {
            throw ApiError.bad("Please enter a valid date.");
        }
    }
}
