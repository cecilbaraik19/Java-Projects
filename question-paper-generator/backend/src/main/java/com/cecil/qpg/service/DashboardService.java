package com.cecil.qpg.service;

import com.cecil.qpg.model.Enums.Difficulty;
import com.cecil.qpg.model.Enums.PaperStatus;
import com.cecil.qpg.model.Enums.Role;
import com.cecil.qpg.model.Event;
import com.cecil.qpg.model.Paper;
import com.cecil.qpg.model.Question;
import com.cecil.qpg.model.Settings;
import com.cecil.qpg.model.Subject;
import com.cecil.qpg.model.User;
import com.cecil.qpg.store.DataStore;
import com.cecil.qpg.util.Json;
import com.cecil.qpg.util.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Numbers for the home page, the activity history, and the paper-header settings. */
public class DashboardService {
    private final DataStore store;

    public DashboardService(DataStore store) { this.store = store; }

    public Map<String, Object> dashboard(User me) {
        synchronized (store) {
            int active = 0, archived = 0, neverUsed = 0, mine = 0;
            Map<String, Integer> byDiff = new LinkedHashMap<>();
            for (Difficulty d : Difficulty.values()) byDiff.put(d.name(), 0);
            for (Question q : store.questions) {
                if (q.archived) { archived++; continue; }
                active++;
                byDiff.merge(q.difficulty.name(), 1, Integer::sum);
                if (q.timesUsed == 0) neverUsed++;
                if (me.username.equals(q.createdBy)) mine++;
            }
            Map<String, Integer> byStatus = new LinkedHashMap<>();
            for (PaperStatus s : PaperStatus.values()) byStatus.put(s.name(), 0);
            List<Object> review = new ArrayList<>(), myDrafts = new ArrayList<>(), upcoming = new ArrayList<>();
            String today = store.today().toString();
            List<Paper> sorted = new ArrayList<>(store.papers);
            sorted.sort(Comparator.comparing((Paper p) -> p.examDate == null ? "9999" : p.examDate));
            for (Paper p : sorted) {
                byStatus.merge(p.status.name(), 1, Integer::sum);
                if (p.status == PaperStatus.SUBMITTED) review.add(p.summary());
                if (p.status == PaperStatus.DRAFT && (me.username.equals(p.createdBy) || me.role.atLeast(Role.HOD))) {
                    Map<String, Object> m = p.summary();
                    m.put("reviewNote", p.reviewNote);
                    myDrafts.add(m);
                }
                if (p.examDate != null && p.examDate.compareTo(today) >= 0 && p.status != PaperStatus.DRAFT) upcoming.add(p.summary());
            }
            List<Object> subjects = new ArrayList<>();
            for (Subject s : store.subjects) {
                int n = 0, hard = 0, papers = 0;
                for (Question q : store.questions) if (!q.archived && q.subjectCode.equals(s.code)) { n++; if (q.difficulty == Difficulty.HARD) hard++; }
                for (Paper p : store.papers) if (p.subjectCode.equals(s.code)) papers++;
                subjects.add(Json.map("code", s.code, "name", s.name, "questions", n, "hard", hard, "papers", papers, "units", s.units.size()));
            }
            return Json.map("questions", active, "archived", archived, "neverUsed", neverUsed, "mine", mine,
                    "byDifficulty", byDiff, "blueprints", store.blueprints.size(), "papers", store.papers.size(),
                    "byStatus", byStatus, "review", review, "drafts", myDrafts, "upcoming", upcoming,
                    "subjects", subjects, "recent", events(null, 12));
        }
    }

    public List<Object> events(String ref, int limit) {
        synchronized (store) {
            List<Object> out = new ArrayList<>();
            for (int i = store.events.size() - 1; i >= 0 && out.size() < limit; i--) {
                Event e = store.events.get(i);
                if (ref != null && !ref.equals(e.ref)) continue;
                out.add(e.toJson());
            }
            return out;
        }
    }

    public Object settings() {
        synchronized (store) { return store.settings.toJson(); }
    }

    public Object saveSettings(User me, Map<String, Object> b) {
        AuthService.need(me, Role.HOD, "change settings");
        synchronized (store) {
            Settings s = store.settings;
            try {
                if (b.containsKey("institute")) s.institute = Text.line(Json.str(b, "institute"), "Institute", 100, true);
                if (b.containsKey("department")) s.department = Text.line(Json.str(b, "department"), "Department", 100, false);
                if (b.containsKey("duplicateThreshold")) {
                    Object v = b.get("duplicateThreshold");
                    double d;
                    try { d = v instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(v)); }
                    catch (NumberFormatException e) { throw new IllegalArgumentException("Similarity limit must be a number"); }
                    if (d > 1) d = d / 100.0; // accept 60 as 60%
                    if (d < 0.4 || d > 0.95) throw new IllegalArgumentException("Similarity limit must be between 40% and 95%");
                    s.duplicateThreshold = Math.round(d * 100) / 100.0;
                }
            } catch (IllegalArgumentException e) {
                throw ApiError.bad(e.getMessage());
            }
            store.saveSettings();
            store.log(me.username, "settings", "Changed paper settings", null);
            return s.toJson();
        }
    }
}
