package com.cecil.sld.service;

import com.cecil.sld.model.*;
import com.cecil.sld.store.DataStore;
import com.cecil.sld.util.Csv;
import com.cecil.sld.util.Text;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** The home pages (teacher and student), settings and CSV exports. */
public class DashboardService {
    private final DataStore db;
    private final DifficultyEngine engine;
    private final QuizService quiz;
    private final PlanService plans;

    public DashboardService(DataStore db, DifficultyEngine engine, QuizService quiz, PlanService plans) {
        this.db = db;
        this.engine = engine;
        this.quiz = quiz;
        this.plans = plans;
    }

    // ------------------------------------------------------------ teacher / admin

    public synchronized Map<String, Object> teacher(User viewer, String batch) {
        LocalDate today = db.today();
        List<User> studs = new ArrayList<>();
        for (User u : db.students()) if (u.active && (batch == null || batch.isBlank() || batch.equals(u.batch))) studs.add(u);
        Set<String> ids = new HashSet<>();
        for (User u : studs) ids.add(u.id);

        int tests30 = 0, right30 = 0, total30 = 0;
        for (Attempt a : db.attempts) {
            if (!ids.contains(a.studentId)) continue;
            if (ChronoUnit.DAYS.between(LocalDateTime.parse(a.finishedAt).toLocalDate(), today) <= 30) {
                tests30++;
                right30 += a.score;
                total30 += a.total;
            }
        }

        // every student x every topic
        int flagged = 0, critical = 0;
        List<Map<String, Object>> needHelp = new ArrayList<>();
        for (User st : studs) {
            List<DifficultyEngine.TopicResult> rs = engine.forStudent(st.id, null);
            int hard = 0;
            DifficultyEngine.TopicResult worst = null;
            for (DifficultyEngine.TopicResult r : rs) {
                if (r.band.ordinal() >= Band.DIFFICULT.ordinal()) { hard++; flagged++; }
                if (r.band == Band.CRITICAL) critical++;
                if (r.band != Band.NO_DATA && (worst == null || r.index > worst.index)) worst = r;
            }
            if (hard > 0) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("studentId", st.id);
                m.put("name", st.name);
                m.put("rollNo", st.rollNo);
                m.put("hard", hard);
                m.put("worst", worst == null ? "" : worst.topic);
                m.put("worstIndex", worst == null ? -1 : worst.index);
                needHelp.add(m);
            }
        }
        needHelp.sort((a, b) -> {
            int c = Integer.compare((int) b.get("hard"), (int) a.get("hard"));
            return c != 0 ? c : Double.compare(((Number) b.get("worstIndex")).doubleValue(), ((Number) a.get("worstIndex")).doubleValue());
        });

        List<Map<String, Object>> topics = engine.classTopics(null, batch);
        List<Map<String, Object>> hardest = new ArrayList<>(topics);
        hardest.removeIf(m -> ((Number) m.get("index")).doubleValue() < 0);
        hardest.sort((a, b) -> Double.compare(((Number) b.get("index")).doubleValue(), ((Number) a.get("index")).doubleValue()));

        List<Map<String, Object>> openPlans = plans.list(viewer, "OPEN");
        int overdue = 0;
        for (Map<String, Object> p : openPlans) if (Boolean.TRUE.equals(p.get("overdue"))) overdue++;

        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("students", studs.size());
        kpi.put("tests30", tests30);
        kpi.put("avgScore30", total30 == 0 ? -1 : Text.r2((double) right30 / total30));
        kpi.put("flagged", flagged);
        kpi.put("critical", critical);
        kpi.put("openPlans", openPlans.size());
        kpi.put("overduePlans", overdue);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kpi", kpi);
        out.put("hardest", hardest.subList(0, Math.min(6, hardest.size())));
        out.put("slipping", slipping(ids));
        out.put("needHelp", needHelp.subList(0, Math.min(8, needHelp.size())));
        out.put("weekly", weekly(ids, 10));
        out.put("plans", openPlans.subList(0, Math.min(6, openPlans.size())));
        List<Map<String, Object>> recent = new ArrayList<>();
        for (Map<String, Object> a : quiz.attempts(null, null, 0)) {
            if (!ids.contains((String) a.get("studentId"))) continue;
            recent.add(a);
            if (recent.size() >= 8) break;
        }
        out.put("recent", recent);
        out.put("subjects", topicsBySubject(topics));
        return out;
    }

    private List<Map<String, Object>> topicsBySubject(List<Map<String, Object>> topics) {
        Map<String, Map<String, Object>> bySub = new LinkedHashMap<>();
        for (Map<String, Object> t : topics) {
            Map<String, Object> s = bySub.computeIfAbsent((String) t.get("subjectId"), k -> {
                Subject sub = db.subject(k);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", sub.id);
                m.put("code", sub.code);
                m.put("name", sub.name);
                m.put("color", sub.color);
                m.put("topics", new ArrayList<Map<String, Object>>());
                return m;
            });
            @SuppressWarnings("unchecked") List<Map<String, Object>> l = (List<Map<String, Object>>) s.get("topics");
            l.add(t);
        }
        return new ArrayList<>(bySub.values());
    }

    /** Topics where the class got clearly worse: last 14 days vs the 15-60 days before. */
    private List<Map<String, Object>> slipping(Set<String> studentIds) {
        LocalDate today = db.today();
        Map<String, int[]> recent = new HashMap<>(), older = new HashMap<>();
        for (Attempt a : db.attempts) {
            if (!studentIds.contains(a.studentId)) continue;
            long age = ChronoUnit.DAYS.between(LocalDateTime.parse(a.finishedAt).toLocalDate(), today);
            Map<String, int[]> target = age <= 14 ? recent : age <= 60 ? older : null;
            if (target == null) continue;
            for (AttemptItem it : a.items) {
                int[] c = target.computeIfAbsent(it.topicId, k -> new int[2]);
                c[0]++;
                if (it.correct) c[1]++;
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, int[]> e : recent.entrySet()) {
            int[] r = e.getValue();
            int[] o = older.get(e.getKey());
            if (o == null || r[0] < 8 || o[0] < 8) continue;
            double before = (double) o[1] / o[0], now = (double) r[1] / r[0];
            if (before - now >= 0.10) {
                Topic t = db.topic(e.getKey());
                if (t == null || !t.active) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("topicId", t.id);
                m.put("topic", t.name);
                m.put("subject", db.subject(t.subjectId).code);
                m.put("before", Text.r2(before));
                m.put("now", Text.r2(now));
                out.add(m);
            }
        }
        out.sort(Comparator.comparingDouble(m -> ((Number) m.get("now")).doubleValue() - ((Number) m.get("before")).doubleValue()));
        return out.subList(0, Math.min(5, out.size()));
    }

    /** Share of right answers per week, oldest first (null for a week with no answers). */
    private List<Map<String, Object>> weekly(Set<String> studentIds, int weeks) {
        LocalDate today = db.today();
        int[][] c = new int[weeks][2];
        for (Attempt a : db.attempts) {
            if (!studentIds.contains(a.studentId)) continue;
            long age = ChronoUnit.DAYS.between(LocalDateTime.parse(a.finishedAt).toLocalDate(), today);
            int w = (int) (age / 7);
            if (w < 0 || w >= weeks) continue;
            for (AttemptItem it : a.items) {
                c[weeks - 1 - w][0]++;
                if (it.correct) c[weeks - 1 - w][1]++;
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < weeks; i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            LocalDate end = today.minusDays(7L * (weeks - 1 - i));
            m.put("weekEnding", end.toString());
            m.put("answers", c[i][0]);
            m.put("rightRate", c[i][0] == 0 ? null : Text.r2((double) c[i][1] / c[i][0]));
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------ student

    public synchronized Map<String, Object> student(User st) {
        List<DifficultyEngine.TopicResult> all = engine.forStudent(st.id, null);
        Map<String, Integer> bands = new LinkedHashMap<>();
        for (Band b : Band.values()) bands.put(b.name(), 0);
        for (DifficultyEngine.TopicResult r : all) bands.merge(r.band.name(), 1, Integer::sum);

        List<Map<String, Object>> subjects = new ArrayList<>();
        for (Subject s : db.subjects) {
            if (!s.active) continue;
            List<DifficultyEngine.TopicResult> rs = new ArrayList<>();
            for (DifficultyEngine.TopicResult r : all) if (r.subjectId.equals(s.id)) rs.add(r);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id);
            m.put("code", s.code);
            m.put("name", s.name);
            m.put("color", s.color);
            m.put("topics", rs);
            subjects.add(m);
        }
        int tests = 0, right = 0, total = 0;
        for (Attempt a : db.attempts) if (a.studentId.equals(st.id)) { tests++; right += a.score; total += a.total; }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tests", tests);
        out.put("avgScore", total == 0 ? -1 : Text.r2((double) right / total));
        out.put("bands", bands);
        out.put("subjects", subjects);
        List<Map<String, Object>> plan = engine.studyPlan(st.id, null);
        out.put("plan", plan.subList(0, Math.min(5, plan.size())));
        out.put("planTotal", plan.size());
        out.put("supportPlans", plans.list(st, "OPEN"));
        out.put("recent", quiz.attempts(st.id, null, 5));
        out.put("weekly", weekly(Set.of(st.id), 8));
        return out;
    }

    // ------------------------------------------------------------ settings

    public synchronized Settings saveSettings(User by, Map<String, Object> in) {
        Settings s = db.settings;
        Settings n = new Settings();
        n.institution = Text.cut(CatalogService.str(in.getOrDefault("institution", s.institution)), 100);
        n.department = Text.cut(CatalogService.str(in.getOrDefault("department", s.department)), 100);
        n.wWrong = weight(in, "wWrong", s.wWrong);
        n.wSlow = weight(in, "wSlow", s.wSlow);
        n.wHints = weight(in, "wHints", s.wHints);
        n.wSureWrong = weight(in, "wSureWrong", s.wSureWrong);
        n.wSelf = weight(in, "wSelf", s.wSelf);
        n.wDecline = weight(in, "wDecline", s.wDecline);
        if (n.wWrong + n.wSlow + n.wHints + n.wSureWrong + n.wSelf + n.wDecline <= 0) throw ApiError.bad("At least one weight must be above 0.");
        n.moderateFrom = CatalogService.num(in.get("moderateFrom"), s.moderateFrom);
        n.difficultFrom = CatalogService.num(in.get("difficultFrom"), s.difficultFrom);
        n.criticalFrom = CatalogService.num(in.get("criticalFrom"), s.criticalFrom);
        if (!(n.moderateFrom >= 1 && n.moderateFrom < n.difficultFrom && n.difficultFrom < n.criticalFrom && n.criticalFrom <= 99))
            throw ApiError.bad("Limits must go up: Moderate < Difficult < Very difficult, between 1 and 99.");
        n.minAnswers = CatalogService.num(in.get("minAnswers"), s.minAnswers);
        if (n.minAnswers < 1 || n.minAnswers > 20) throw ApiError.bad("Minimum answers must be from 1 to 20.");
        n.halfLifeDays = CatalogService.num(in.get("halfLifeDays"), s.halfLifeDays);
        if (n.halfLifeDays < 1 || n.halfLifeDays > 365) throw ApiError.bad("Half-life must be from 1 to 365 days.");
        if (n.institution.isBlank()) throw ApiError.bad("Please enter the institution name.");
        db.settings = n;
        db.log(by.name, "Changed settings", "");
        db.save("settings", "events");
        return n;
    }

    private static double weight(Map<String, Object> in, String key, double dflt) {
        Object o = in.get(key);
        double v;
        if (o instanceof Number n) v = n.doubleValue();
        else if (o == null || String.valueOf(o).isBlank()) v = dflt;
        else {
            try { v = Double.parseDouble(String.valueOf(o)); }
            catch (NumberFormatException e) { throw ApiError.bad("Weights must be numbers."); }
        }
        if (v < 0 || v > 100) throw ApiError.bad("Each weight must be from 0 to 100.");
        return v;
    }

    // ------------------------------------------------------------ exports

    /** Every student x topic of a subject, as CSV. */
    @SuppressWarnings("unchecked")
    public synchronized String heatmapCsv(String subjectId, String batch) {
        Subject s = db.subject(subjectId);
        if (s == null) throw ApiError.notFound("Subject");
        Map<String, Object> hm = engine.heatmap(subjectId, batch);
        List<Map<String, Object>> topics = (List<Map<String, Object>>) hm.get("topics");
        List<String> header = new ArrayList<>(List.of("Roll no", "Name", "Batch", "Difficult topics"));
        for (Map<String, Object> t : topics) header.add((String) t.get("name"));
        List<List<Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : (List<Map<String, Object>>) hm.get("rows")) {
            List<Object> row = new ArrayList<>(List.of(r.get("rollNo"), r.get("name"), r.get("batch"), r.get("hard")));
            for (Object c : (List<Object>) r.get("cells")) {
                double idx = ((Number) ((Map<String, Object>) c).get("index")).doubleValue();
                row.add(idx < 0 ? "" : idx);
            }
            rows.add(row);
        }
        return Csv.build(header, rows);
    }

    public synchronized String attemptsCsv() {
        List<List<Object>> rows = new ArrayList<>();
        for (Map<String, Object> a : quiz.attempts(null, null, 0)) {
            User st = db.user((String) a.get("studentId"));
            rows.add(List.of(a.get("id"), a.get("finishedAt"), st == null ? "" : st.rollNo, a.get("student"), a.get("subject"),
                    a.get("kind"), a.get("score"), a.get("total"), a.get("minutes"), a.get("hints"), a.get("sureWrong")));
        }
        return Csv.build(List.of("Attempt", "Finished", "Roll no", "Student", "Subject", "Kind", "Score", "Out of", "Minutes", "Hints used", "Wrong while sure"), rows);
    }
}
