package com.cecil.sld.service;

import com.cecil.sld.model.*;
import com.cecil.sld.store.DataStore;
import com.cecil.sld.util.Text;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * The heart of the project: works out how difficult each topic is for a student.
 *
 * For every topic it looks at the student's answers and turns six signals into
 * one number, the Difficulty Index (0 = very easy, 100 = very hard):
 *
 *   1. Wrong answers      - how often the answer was wrong (a wrong answer on an
 *                           easy question counts more than one on a hard question)
 *   2. Slow answers       - time taken compared with the time expected for the topic
 *   3. Hints              - how often a hint was needed
 *   4. Wrong while sure   - wrong answers given with "I am sure": a sign of a
 *                           misunderstanding (misconception), not just a gap
 *   5. Self rating        - the student's own "this feels hard" rating (if given)
 *   6. Getting worse      - recent answers worse than older answers
 *
 * Newer answers count more than older ones (half-life set in Settings).
 * Each signal is 0..1; the index is the weighted average x 100.
 * If a prerequisite topic is also difficult, it is reported as the likely root cause.
 */
public class DifficultyEngine {
    private final DataStore db;

    public DifficultyEngine(DataStore db) { this.db = db; }

    /** The result for one student and one topic. */
    public static class TopicResult {
        public String topicId;
        public String topic;
        public String subjectId;
        public int unit;
        public double index;            // 0-100, or -1 when there is not enough data
        public Band band = Band.NO_DATA;
        public String bandLabel;
        public String zone;
        public int answered;
        public int correct;
        public double accuracy;         // 0-1 (weighted by recency)
        public double timeRatio;        // 1.0 = as expected, 2.0 = twice as long
        public double hintRate;         // 0-1
        public int sureWrong;           // count
        public int selfRating;          // 0 = not given, else 1-5
        public double earlierAccuracy = -1;
        public double recentAccuracy = -1;
        public String mainCause = "";   // WRONG, SLOW, HINTS, SURE_WRONG, SELF, DECLINE, PREREQ
        public Map<String, Double> signals = new LinkedHashMap<>();
        public List<String> reasons = new ArrayList<>();
        public List<String> prerequisiteIds = new ArrayList<>();
        public List<String> rootCauseIds = new ArrayList<>();
        public List<String> rootCauses = new ArrayList<>();
        public String lastPractised;
    }

    /** A plain item with its date and recency weight, used while computing. */
    private record Dated(AttemptItem item, LocalDateTime at) {}

    // --------------------------------------------------------------- per student

    /** Results for every active topic of a subject (or all subjects when subjectId is null). */
    public synchronized List<TopicResult> forStudent(String studentId, String subjectId) {
        Map<String, List<Dated>> byTopic = itemsOf(studentId);
        Map<String, Integer> self = latestSelfRatings(studentId);
        List<TopicResult> out = new ArrayList<>();
        Map<String, TopicResult> byId = new HashMap<>();
        for (Subject s : db.subjects) {
            if (!s.active || (subjectId != null && !s.id.equals(subjectId))) continue;
            for (Topic t : db.topicsOf(s.id)) {
                if (!t.active) continue;
                TopicResult r = compute(t, byTopic.getOrDefault(t.id, List.of()), self.getOrDefault(t.id, 0));
                out.add(r);
                byId.put(t.id, r);
            }
        }
        // root causes: a prerequisite that is itself Difficult or worse
        for (TopicResult r : out) {
            if (r.band.ordinal() < Band.MODERATE.ordinal()) continue;
            Topic t = db.topic(r.topicId);
            for (String pid : t.prerequisiteIds) {
                TopicResult p = byId.get(pid);
                if (p == null) p = computeOne(studentId, pid, byTopic, self);
                if (p != null && p.band.ordinal() >= Band.DIFFICULT.ordinal()) {
                    r.rootCauseIds.add(pid);
                    r.rootCauses.add(p.topic);
                }
            }
            if (!r.rootCauses.isEmpty()) {
                r.reasons.add("Needs " + String.join(", ", r.rootCauses) + " first - that topic is also difficult");
                if (r.band.ordinal() >= Band.DIFFICULT.ordinal()) r.mainCause = "PREREQ";
            }
        }
        return out;
    }

    /** One topic for one student. */
    public synchronized TopicResult forStudentTopic(String studentId, String topicId) {
        for (TopicResult r : forStudent(studentId, db.topic(topicId).subjectId)) if (r.topicId.equals(topicId)) return r;
        return null;
    }

    private TopicResult computeOne(String studentId, String topicId, Map<String, List<Dated>> byTopic, Map<String, Integer> self) {
        Topic t = db.topic(topicId);
        if (t == null || !t.active) return null;
        return compute(t, byTopic.getOrDefault(topicId, List.of()), self.getOrDefault(topicId, 0));
    }

    private Map<String, List<Dated>> itemsOf(String studentId) {
        Map<String, List<Dated>> map = new HashMap<>();
        for (Attempt a : db.attempts) {
            if (!a.studentId.equals(studentId) || a.finishedAt == null) continue;
            LocalDateTime at = LocalDateTime.parse(a.finishedAt);
            for (AttemptItem it : a.items) map.computeIfAbsent(it.topicId, k -> new ArrayList<>()).add(new Dated(it, at));
        }
        for (List<Dated> l : map.values()) l.sort(Comparator.comparing(Dated::at));
        return map;
    }

    private Map<String, Integer> latestSelfRatings(String studentId) {
        Map<String, Integer> out = new HashMap<>();
        Map<String, String> dates = new HashMap<>();
        for (SelfReport r : db.reports) {
            if (!r.studentId.equals(studentId)) continue;
            String prev = dates.get(r.topicId);
            if (prev == null || r.date.compareTo(prev) >= 0) {
                out.put(r.topicId, r.rating);
                dates.put(r.topicId, r.date);
            }
        }
        return out;
    }

    /** The calculation itself (package-private; the tests use computeForTest). */
    TopicResult compute(Topic t, List<Dated> items, int selfRating) {
        Settings s = db.settings;
        TopicResult r = new TopicResult();
        r.topicId = t.id;
        r.topic = t.name;
        r.subjectId = t.subjectId;
        r.unit = t.unit;
        r.prerequisiteIds = new ArrayList<>(t.prerequisiteIds);
        r.answered = items.size();
        r.selfRating = selfRating;
        for (Dated d : items) if (d.item().correct) r.correct++;
        if (!items.isEmpty()) r.lastPractised = items.get(items.size() - 1).at().toLocalDate().toString();

        if (items.size() < Math.max(1, s.minAnswers)) {
            r.index = -1;
            r.band = Band.NO_DATA;
            r.bandLabel = r.band.label;
            r.zone = r.band.zone;
            r.accuracy = items.isEmpty() ? 0 : (double) r.correct / items.size();
            if (items.isEmpty()) r.reasons.add("No answers yet - take a test to survey this topic");
            else r.reasons.add("Only " + Text.plural(items.size(), "answer") + " so far - need " + s.minAnswers + " to judge");
            if (selfRating >= 4) r.reasons.add("Said this topic feels hard (" + selfRating + "/5)");
            return r;
        }

        LocalDate today = db.today();
        double sumW = 0, wrongW = 0, secW = 0, hintW = 0, sureWrongW = 0, correctW = 0;
        for (Dated d : items) {
            AttemptItem it = d.item();
            long age = Math.max(0, ChronoUnit.DAYS.between(d.at().toLocalDate(), today));
            double w = Math.pow(0.5, age / (double) Math.max(1, s.halfLifeDays));
            sumW += w;
            if (it.correct) correctW += w;
            else wrongW += w * levelWeight(it.level);
            secW += w * it.seconds;
            if (it.hintUsed) hintW += w;
            if (!it.correct && it.confidence >= 3) { sureWrongW += w; r.sureWrong++; }
        }
        r.accuracy = correctW / sumW;
        double wrongSignal = clamp(wrongW / sumW);
        r.timeRatio = (secW / sumW) / Math.max(5, t.expectedSeconds);
        double slowSignal = clamp(r.timeRatio - 1.0);               // 2x the time = full signal
        r.hintRate = hintW / sumW;
        double hintSignal = clamp(r.hintRate);
        double sureWrongSignal = clamp((sureWrongW / sumW) * 2.5);  // 40% wrong-while-sure = full signal

        Map<String, double[]> parts = new LinkedHashMap<>();       // name -> {weight, signal}
        parts.put("WRONG", new double[]{s.wWrong, wrongSignal});
        parts.put("SLOW", new double[]{s.wSlow, slowSignal});
        parts.put("HINTS", new double[]{s.wHints, hintSignal});
        parts.put("SURE_WRONG", new double[]{s.wSureWrong, sureWrongSignal});
        if (selfRating > 0) parts.put("SELF", new double[]{s.wSelf, (selfRating - 1) / 4.0});

        if (items.size() >= 6) {
            int half = items.size() / 2;
            r.earlierAccuracy = share(items.subList(0, half));
            r.recentAccuracy = share(items.subList(items.size() - half, items.size()));
            double drop = r.earlierAccuracy - r.recentAccuracy;
            parts.put("DECLINE", new double[]{s.wDecline, clamp(drop / 0.5)});
        }

        double num = 0, den = 0, best = -1;
        for (Map.Entry<String, double[]> e : parts.entrySet()) {
            double w = Math.max(0, e.getValue()[0]);
            double sig = e.getValue()[1];
            num += w * sig;
            den += w;
            r.signals.put(e.getKey(), Text.r2(sig));
            if (w * sig > best) { best = w * sig; r.mainCause = e.getKey(); }
        }
        r.index = den == 0 ? 0 : Text.r1(100 * num / den);
        r.band = bandOf(r.index);
        r.bandLabel = r.band.label;
        r.zone = r.band.zone;
        r.accuracy = Text.r2(r.accuracy);
        r.timeRatio = Text.r2(r.timeRatio);
        r.hintRate = Text.r2(r.hintRate);

        // plain-language reasons
        r.reasons.add("Right on " + r.correct + " of " + r.answered + " answers (" + Text.pct((double) r.correct / r.answered) + ")");
        if (slowSignal >= 0.25) r.reasons.add("Takes " + Text.r1(r.timeRatio) + "x the expected time per question");
        else if (r.timeRatio < 0.5 && wrongSignal > 0.4) r.reasons.add("Answers very fast but often wrong - may be rushing or guessing");
        if (r.hintRate >= 0.3) r.reasons.add("Needed a hint on " + Text.pct(r.hintRate) + " of questions");
        if (r.sureWrong >= 2) r.reasons.add("Wrong while 'sure' " + r.sureWrong + " times - likely a misunderstanding, not just a gap");
        if (selfRating >= 4) r.reasons.add("Said this topic feels hard (" + selfRating + "/5)");
        if (r.earlierAccuracy >= 0) {
            double change = r.recentAccuracy - r.earlierAccuracy;
            if (change <= -0.15) r.reasons.add("Getting worse: " + Text.pct(r.earlierAccuracy) + " right earlier, " + Text.pct(r.recentAccuracy) + " recently");
            else if (change >= 0.15) r.reasons.add("Improving: " + Text.pct(r.earlierAccuracy) + " right earlier, " + Text.pct(r.recentAccuracy) + " recently");
        }
        if (r.band == Band.EASY) r.mainCause = "";
        return r;
    }

    /** A wrong answer to an easy question says more than one to a hard question. */
    static double levelWeight(int level) {
        return level <= 1 ? 1.0 : level == 2 ? 0.85 : 0.7;
    }

    private static double share(List<Dated> list) {
        if (list.isEmpty()) return 0;
        int c = 0;
        for (Dated d : list) if (d.item().correct) c++;
        return (double) c / list.size();
    }

    private static double clamp(double v) { return Math.max(0, Math.min(1, v)); }

    public Band bandOf(double index) {
        Settings s = db.settings;
        if (index < 0) return Band.NO_DATA;
        if (index >= s.criticalFrom) return Band.CRITICAL;
        if (index >= s.difficultFrom) return Band.DIFFICULT;
        if (index >= s.moderateFrom) return Band.MODERATE;
        return Band.EASY;
    }

    /** Builds a TopicResult from raw items. Used by the tests (dates are "today"). */
    public TopicResult computeForTest(Topic t, List<AttemptItem> items, List<LocalDateTime> when, int selfRating) {
        List<Dated> list = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) list.add(new Dated(items.get(i), when.get(i)));
        list.sort(Comparator.comparing(Dated::at));
        return compute(t, list, selfRating);
    }

    // --------------------------------------------------------------- whole class

    /** Index of every student for every topic of a subject: the heat map. */
    public synchronized Map<String, Object> heatmap(String subjectId, String batch) {
        List<Topic> topics = new ArrayList<>();
        for (Topic t : db.topicsOf(subjectId)) if (t.active) topics.add(t);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (User st : db.students()) {
            if (!st.active || (batch != null && !batch.isBlank() && !batch.equals(st.batch))) continue;
            List<TopicResult> res = forStudent(st.id, subjectId);
            Map<String, TopicResult> byId = new HashMap<>();
            for (TopicResult r : res) byId.put(r.topicId, r);
            List<Object> cells = new ArrayList<>();
            int hard = 0;
            for (Topic t : topics) {
                TopicResult r = byId.get(t.id);
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("index", r == null ? -1 : r.index);
                c.put("band", r == null ? Band.NO_DATA : r.band);
                c.put("root", r != null && !r.rootCauses.isEmpty());
                cells.add(c);
                if (r != null && r.band.ordinal() >= Band.DIFFICULT.ordinal()) hard++;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("studentId", st.id);
            row.put("name", st.name);
            row.put("rollNo", st.rollNo);
            row.put("batch", st.batch);
            row.put("hard", hard);
            row.put("cells", cells);
            rows.add(row);
        }
        List<Map<String, Object>> cols = new ArrayList<>();
        for (Topic t : topics) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", t.id);
            c.put("name", t.name);
            c.put("unit", t.unit);
            cols.add(c);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("topics", cols);
        out.put("rows", rows);
        return out;
    }

    /** Class-wide summary of one topic, plus which questions trip students up. */
    public synchronized Map<String, Object> topicReport(String topicId, String batch) {
        Topic t = db.topic(topicId);
        if (t == null) throw ApiError.notFound("Topic");
        List<Map<String, Object>> students = new ArrayList<>();
        Map<String, Integer> bandCount = new LinkedHashMap<>();
        for (Band b : Band.values()) bandCount.put(b.name(), 0);
        double sum = 0;
        int withData = 0;
        Map<String, Integer> causeCount = new LinkedHashMap<>();
        Set<String> allowed = new HashSet<>();
        for (User st : db.students()) {
            if (!st.active || (batch != null && !batch.isBlank() && !batch.equals(st.batch))) continue;
            allowed.add(st.id);
            TopicResult r = forStudentTopic(st.id, topicId);
            bandCount.merge(r.band.name(), 1, Integer::sum);
            if (r.band != Band.NO_DATA) {
                sum += r.index;
                withData++;
                if (r.band.ordinal() >= Band.DIFFICULT.ordinal() && !r.mainCause.isEmpty()) causeCount.merge(r.mainCause, 1, Integer::sum);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("studentId", st.id);
            m.put("name", st.name);
            m.put("rollNo", st.rollNo);
            m.put("result", r);
            students.add(m);
        }
        students.sort((a, b) -> Double.compare(((TopicResult) b.get("result")).index, ((TopicResult) a.get("result")).index));

        // question analysis
        Map<String, int[]> qStats = new HashMap<>();                 // qid -> {answered, correct, seconds, sureWrong}
        Map<String, int[]> wrongPick = new HashMap<>();              // qid -> count per option
        for (Attempt a : db.attempts) {
            if (!allowed.contains(a.studentId)) continue;
            for (AttemptItem it : a.items) {
                if (!it.topicId.equals(topicId)) continue;
                int[] st = qStats.computeIfAbsent(it.questionId, k -> new int[4]);
                st[0]++;
                if (it.correct) st[1]++;
                st[2] += it.seconds;
                if (!it.correct && it.confidence >= 3) st[3]++;
                if (!it.correct && it.chosen >= 0 && it.chosen < 6) wrongPick.computeIfAbsent(it.questionId, k -> new int[6])[it.chosen]++;
            }
        }
        List<Map<String, Object>> qs = new ArrayList<>();
        for (Question q : db.questions) {
            if (!q.topicId.equals(topicId)) continue;
            int[] st = qStats.getOrDefault(q.id, new int[4]);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", q.id);
            m.put("text", q.text);
            m.put("level", q.level);
            m.put("active", q.active);
            m.put("answered", st[0]);
            m.put("rightRate", st[0] == 0 ? -1 : Text.r2((double) st[1] / st[0]));
            m.put("avgSeconds", st[0] == 0 ? 0 : Math.round((double) st[2] / st[0]));
            m.put("sureWrong", st[3]);
            int[] picks = wrongPick.get(q.id);
            if (picks != null) {
                int bestOpt = -1, bestN = 0, wrongTotal = 0;
                for (int i = 0; i < picks.length; i++) { wrongTotal += picks[i]; if (picks[i] > bestN) { bestN = picks[i]; bestOpt = i; } }
                if (bestOpt >= 0 && bestOpt < q.options.size()) {
                    m.put("commonWrong", q.options.get(bestOpt));
                    m.put("commonWrongShare", Text.r2((double) bestN / wrongTotal));
                }
            }
            qs.add(m);
        }
        qs.sort((a, b) -> Double.compare(rate(a), rate(b)));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("topic", t);
        out.put("subject", db.subject(t.subjectId));
        List<String> pre = new ArrayList<>();
        for (String p : t.prerequisiteIds) { Topic pt = db.topic(p); if (pt != null) pre.add(pt.name); }
        out.put("prerequisites", pre);
        out.put("classIndex", withData == 0 ? -1 : Text.r1(sum / withData));
        out.put("classBand", withData == 0 ? Band.NO_DATA : bandOf(sum / withData));
        out.put("withData", withData);
        out.put("bands", bandCount);
        out.put("causes", causeCount);
        out.put("students", students);
        out.put("questions", qs);
        return out;
    }

    private static double rate(Map<String, Object> q) {
        double r = ((Number) q.get("rightRate")).doubleValue();
        return r < 0 ? 2 : r;   // never-answered questions go last
    }

    /** Average index of every topic of a subject over the whole class (only students with data). */
    public synchronized List<Map<String, Object>> classTopics(String subjectId, String batch) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<User> studs = new ArrayList<>();
        for (User st : db.students()) if (st.active && (batch == null || batch.isBlank() || batch.equals(st.batch))) studs.add(st);
        Map<String, List<TopicResult>> per = new HashMap<>();
        for (User st : studs)
            for (TopicResult r : forStudent(st.id, subjectId)) per.computeIfAbsent(r.topicId, k -> new ArrayList<>()).add(r);
        for (Subject s : db.subjects) {
            if (!s.active || (subjectId != null && !s.id.equals(subjectId))) continue;
            for (Topic t : db.topicsOf(s.id)) {
                if (!t.active) continue;
                List<TopicResult> list = per.getOrDefault(t.id, List.of());
                double sum = 0;
                int n = 0, hard = 0, answered = 0, correct = 0;
                for (TopicResult r : list) {
                    answered += r.answered;
                    correct += r.correct;
                    if (r.band == Band.NO_DATA) continue;
                    sum += r.index;
                    n++;
                    if (r.band.ordinal() >= Band.DIFFICULT.ordinal()) hard++;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("topicId", t.id);
                m.put("topic", t.name);
                m.put("subjectId", s.id);
                m.put("subject", s.code);
                m.put("color", s.color);
                m.put("unit", t.unit);
                m.put("index", n == 0 ? -1 : Text.r1(sum / n));
                m.put("band", n == 0 ? Band.NO_DATA : bandOf(sum / n));
                m.put("students", n);
                m.put("struggling", hard);
                m.put("rightRate", answered == 0 ? -1 : Text.r2((double) correct / answered));
                m.put("prerequisiteIds", t.prerequisiteIds);
                out.add(m);
            }
        }
        return out;
    }

    // --------------------------------------------------------------- study plan

    /**
     * An ordered study plan: Difficult and Very difficult topics first, but a
     * prerequisite always comes before the topic that needs it.
     */
    public synchronized List<Map<String, Object>> studyPlan(String studentId, String subjectId) {
        List<TopicResult> all = forStudent(studentId, subjectId);
        Map<String, TopicResult> byId = new HashMap<>();
        for (TopicResult r : all) byId.put(r.topicId, r);
        List<TopicResult> chosen = new ArrayList<>();
        for (TopicResult r : all) if (r.band.ordinal() >= Band.MODERATE.ordinal()) chosen.add(r);
        chosen.sort((a, b) -> Double.compare(b.index, a.index));

        List<TopicResult> ordered = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        for (TopicResult r : chosen) place(r, byId, chosen, ordered, placed, new HashSet<>());

        List<Map<String, Object>> out = new ArrayList<>();
        int step = 1;
        for (TopicResult r : ordered) {
            Topic t = db.topic(r.topicId);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("step", step++);
            m.put("result", r);
            m.put("subject", db.subject(t.subjectId).code);
            m.put("advice", advice(r, t));
            out.add(m);
        }
        return out;
    }

    private void place(TopicResult r, Map<String, TopicResult> byId, List<TopicResult> chosen, List<TopicResult> ordered,
                       Set<String> placed, Set<String> visiting) {
        if (placed.contains(r.topicId) || !visiting.add(r.topicId)) return;
        Topic t = db.topic(r.topicId);
        for (String pid : t.prerequisiteIds) {
            TopicResult p = byId.get(pid);
            if (p != null && chosen.contains(p)) place(p, byId, chosen, ordered, placed, visiting);
        }
        placed.add(r.topicId);
        ordered.add(r);
    }

    /** What to do about a difficult topic, based on the strongest signal. */
    public static String advice(TopicResult r, Topic t) {
        String base = switch (r.mainCause) {
            case "PREREQ" -> "Revise " + String.join(", ", r.rootCauses) + " first, then come back to this topic.";
            case "SURE_WRONG" -> "You were sure but wrong several times. Read the explanation of each wrong answer and write the correct idea in your own words.";
            case "SLOW" -> "You know some of it but it takes long. Do short timed practice sets of 5 questions.";
            case "HINTS" -> "Try a practice set without opening any hints, then check the ones you missed.";
            case "DECLINE" -> "This was going better before. Revise your notes - it may have been forgotten.";
            case "SELF" -> "You feel unsure here. Start with easy questions to build confidence.";
            default -> "Go back to the notes, then practise easy questions before medium ones.";
        };
        if (t.tip != null && !t.tip.isBlank()) base += " Tip: " + t.tip;
        return base;
    }
}
