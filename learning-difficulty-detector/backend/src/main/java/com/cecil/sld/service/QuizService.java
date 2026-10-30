package com.cecil.sld.service;

import com.cecil.sld.model.*;
import com.cecil.sld.store.DataStore;
import com.cecil.sld.util.Text;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Taking quizzes.
 *
 *  - TEST:      covers every topic of a subject (2 questions per topic, mixed levels).
 *  - PRACTICE:  "adaptive" - most questions come from the student's difficult topics,
 *               starting with easier questions there, so practice goes where it is needed.
 *
 * The right answers stay on the server until the quiz is submitted. Hints are
 * fetched from the server one at a time, so the server knows which hints were used.
 */
public class QuizService {
    public static final Duration QUIZ_LIFETIME = Duration.ofHours(3);
    public static final int MAX_SECONDS = 30 * 60;

    private final DataStore db;
    private final DifficultyEngine engine;
    private final Random random;
    private final Map<String, QuizSession> open = new ConcurrentHashMap<>();

    public QuizService(DataStore db, DifficultyEngine engine, Random random) {
        this.db = db;
        this.engine = engine;
        this.random = random;
    }

    /** A quiz that has been started but not yet submitted. */
    public static final class QuizSession {
        public String id;
        public String studentId;
        public String subjectId;
        public String kind;
        public List<String> questionIds = new ArrayList<>();
        public Set<String> hintsUsed = new HashSet<>();
        public LocalDateTime startedAt;
    }

    // ------------------------------------------------------------ start

    public synchronized Map<String, Object> start(User student, String subjectId, String kind, int size) {
        Subject s = db.subject(subjectId);
        if (s == null || !s.active) throw ApiError.notFound("Subject");
        kind = "PRACTICE".equalsIgnoreCase(kind) ? "PRACTICE" : "TEST";
        List<Question> pool = activeQuestions(subjectId);
        if (pool.size() < 3) throw ApiError.bad("This subject does not have enough questions yet.");

        List<Question> picked = kind.equals("TEST") ? pickTest(subjectId) : pickPractice(student.id, subjectId, Math.max(5, Math.min(20, size <= 0 ? 10 : size)));
        if (picked.isEmpty()) throw ApiError.bad("No questions could be chosen.");

        // forget this student's older unfinished quizzes
        open.values().removeIf(q -> q.studentId.equals(student.id));

        QuizSession qs = new QuizSession();
        qs.id = db.nextId("QZ");
        qs.studentId = student.id;
        qs.subjectId = subjectId;
        qs.kind = kind;
        qs.startedAt = db.now();
        for (Question q : picked) qs.questionIds.add(q.id);
        open.put(qs.id, qs);

        List<Map<String, Object>> qOut = new ArrayList<>();
        for (Question q : picked) {
            Topic t = db.topic(q.topicId);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", q.id);
            m.put("topic", t.name);
            m.put("topicId", t.id);
            m.put("level", q.level);
            m.put("text", q.text);
            m.put("options", q.options);
            m.put("hasHint", q.hint != null && !q.hint.isBlank());
            m.put("expectedSeconds", t.expectedSeconds);
            qOut.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("quizId", qs.id);
        out.put("kind", kind);
        out.put("subject", s);
        out.put("questions", qOut);
        return out;
    }

    private List<Question> activeQuestions(String subjectId) {
        List<Question> out = new ArrayList<>();
        for (Question q : db.questions) {
            if (!q.active) continue;
            Topic t = db.topic(q.topicId);
            if (t != null && t.active && t.subjectId.equals(subjectId)) out.add(q);
        }
        return out;
    }

    /** Two questions per topic: one easier (level 1-2) and one harder (level 2-3). */
    List<Question> pickTest(String subjectId) {
        List<Question> out = new ArrayList<>();
        for (Topic t : db.topicsOf(subjectId)) {
            if (!t.active) continue;
            List<Question> qs = new ArrayList<>();
            for (Question q : db.questions) if (q.active && q.topicId.equals(t.id)) qs.add(q);
            Collections.shuffle(qs, random);
            Question easy = null, hard = null;
            for (Question q : qs) if (q.level <= 2) { easy = q; break; }
            for (Question q : qs) if (q.level >= 2 && q != easy) { hard = q; break; }
            if (easy != null) out.add(easy);
            if (hard != null) out.add(hard);
            if (easy == null && hard == null && !qs.isEmpty()) out.add(qs.get(0));
        }
        return out;
    }

    /**
     * Adaptive practice. Each topic gets a weight from how difficult it is
     * (Very difficult 5, Difficult 4, Moderate 2, not surveyed 2, Easy 1) and a
     * prerequisite of a difficult topic gets +2. Questions are drawn by weight.
     * In difficult topics easy questions come first; in easy topics hard ones.
     */
    List<Question> pickPractice(String studentId, String subjectId, int size) {
        List<DifficultyEngine.TopicResult> results = engine.forStudent(studentId, subjectId);
        Map<String, Double> weight = new LinkedHashMap<>();
        for (DifficultyEngine.TopicResult r : results) {
            double w = switch (r.band) {
                case CRITICAL -> 5;
                case DIFFICULT -> 4;
                case MODERATE, NO_DATA -> 2;
                case EASY -> 1;
            };
            weight.merge(r.topicId, w, Double::sum);
            if (r.band.ordinal() >= Band.DIFFICULT.ordinal())
                for (String pid : r.rootCauseIds) weight.merge(pid, 2.0, Double::sum);
        }
        Map<String, Band> bandOf = new HashMap<>();
        for (DifficultyEngine.TopicResult r : results) bandOf.put(r.topicId, r.band);

        // questions answered correctly in the last two attempts are left out if possible
        Set<String> recentRight = new HashSet<>();
        List<Attempt> mine = new ArrayList<>();
        for (Attempt a : db.attempts) if (a.studentId.equals(studentId) && a.subjectId.equals(subjectId)) mine.add(a);
        mine.sort(Comparator.comparing((Attempt a) -> a.finishedAt).reversed());
        for (int i = 0; i < Math.min(2, mine.size()); i++) for (AttemptItem it : mine.get(i).items) if (it.correct) recentRight.add(it.questionId);

        Map<String, Deque<Question>> queue = new HashMap<>();
        for (String tid : weight.keySet()) {
            List<Question> qs = new ArrayList<>();
            for (Question q : db.questions) if (q.active && q.topicId.equals(tid)) qs.add(q);
            Collections.shuffle(qs, random);
            boolean hardTopic = bandOf.getOrDefault(tid, Band.NO_DATA).ordinal() >= Band.MODERATE.ordinal();
            qs.sort(Comparator.comparingInt((Question q) -> recentRight.contains(q.id) ? 1 : 0)
                    .thenComparingInt(q -> hardTopic ? q.level : -q.level));
            if (!qs.isEmpty()) queue.put(tid, new ArrayDeque<>(qs));
        }
        List<Question> out = new ArrayList<>();
        while (out.size() < size && !queue.isEmpty()) {
            double total = 0;
            for (String tid : queue.keySet()) total += weight.get(tid);
            double pick = random.nextDouble() * total;
            String chosen = null;
            for (String tid : queue.keySet()) {
                pick -= weight.get(tid);
                if (pick <= 0) { chosen = tid; break; }
            }
            if (chosen == null) chosen = queue.keySet().iterator().next();
            Deque<Question> dq = queue.get(chosen);
            out.add(dq.poll());
            if (dq.isEmpty()) queue.remove(chosen);
        }
        return out;
    }

    // ------------------------------------------------------------ hints

    public synchronized String hint(User student, String quizId, String questionId) {
        QuizSession qs = session(student, quizId);
        if (!qs.questionIds.contains(questionId)) throw ApiError.notFound("Question");
        Question q = db.question(questionId);
        if (q.hint == null || q.hint.isBlank()) throw ApiError.bad("This question has no hint.");
        qs.hintsUsed.add(questionId);
        return q.hint;
    }

    private QuizSession session(User student, String quizId) {
        QuizSession qs = open.get(quizId);
        if (qs == null || !qs.studentId.equals(student.id)) throw new ApiError(404, "This quiz is not open any more. Please start again.");
        if (Duration.between(qs.startedAt, db.now()).compareTo(QUIZ_LIFETIME) > 0) {
            open.remove(quizId);
            throw new ApiError(410, "This quiz was open for more than 3 hours and has expired. Please start again.");
        }
        return qs;
    }

    // ------------------------------------------------------------ submit

    /**
     * answers: list of {questionId, chosen (-1 = skipped), seconds, confidence (1-3)}.
     * Returns the marked quiz with explanations and how each topic changed.
     */
    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> submit(User student, String quizId, List<Object> answers) {
        QuizSession qs = session(student, quizId);
        if (answers == null) throw ApiError.bad("No answers were sent.");
        Map<String, Map<String, Object>> byQ = new HashMap<>();
        for (Object o : answers) {
            if (!(o instanceof Map)) throw ApiError.bad("Bad answer format.");
            Map<String, Object> m = (Map<String, Object>) o;
            String qid = String.valueOf(m.get("questionId"));
            if (!qs.questionIds.contains(qid)) throw ApiError.bad("An answer was for a question that is not in this quiz.");
            if (byQ.put(qid, m) != null) throw ApiError.bad("A question was answered twice.");
        }

        // topic results before, to show the change
        Map<String, Double> before = new HashMap<>();
        for (DifficultyEngine.TopicResult r : engine.forStudent(student.id, qs.subjectId)) before.put(r.topicId, r.index);

        Attempt a = new Attempt();
        a.id = db.nextId("A");
        a.studentId = student.id;
        a.subjectId = qs.subjectId;
        a.kind = qs.kind;
        a.startedAt = qs.startedAt.toString();
        a.finishedAt = db.nowText();
        List<Map<String, Object>> marked = new ArrayList<>();
        for (String qid : qs.questionIds) {
            Question q = db.question(qid);
            Map<String, Object> ans = byQ.getOrDefault(qid, Map.of());
            AttemptItem it = new AttemptItem();
            it.questionId = qid;
            it.topicId = q.topicId;
            it.level = q.level;
            it.chosen = intOf(ans.get("chosen"), -1);
            if (it.chosen < -1 || it.chosen >= q.options.size()) throw ApiError.bad("An answer option is out of range.");
            it.correct = it.chosen == q.answer;
            it.seconds = Math.max(1, Math.min(MAX_SECONDS, intOf(ans.get("seconds"), 1)));
            it.confidence = Math.max(1, Math.min(3, intOf(ans.get("confidence"), 2)));
            it.hintUsed = qs.hintsUsed.contains(qid);
            a.items.add(it);
            if (it.correct) a.score++;

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", qid);
            m.put("text", q.text);
            m.put("topic", db.topic(q.topicId).name);
            m.put("options", q.options);
            m.put("chosen", it.chosen);
            m.put("answer", q.answer);
            m.put("correct", it.correct);
            m.put("confidence", it.confidence);
            m.put("hintUsed", it.hintUsed);
            m.put("seconds", it.seconds);
            m.put("explanation", q.explanation);
            marked.add(m);
        }
        a.total = a.items.size();
        db.attempts.add(a);
        open.remove(quizId);
        db.log(student.name, "Finished " + (a.kind.equals("TEST") ? "test" : "practice"),
                db.subject(a.subjectId).code + " - " + a.score + "/" + a.total);
        db.save("attempts", "events");

        List<Map<String, Object>> changes = new ArrayList<>();
        Set<String> touched = new LinkedHashSet<>();
        for (AttemptItem it : a.items) touched.add(it.topicId);
        for (DifficultyEngine.TopicResult r : engine.forStudent(student.id, qs.subjectId)) {
            if (!touched.contains(r.topicId)) continue;
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("topicId", r.topicId);
            c.put("topic", r.topic);
            c.put("before", before.getOrDefault(r.topicId, -1.0));
            c.put("after", r.index);
            c.put("band", r.band);
            changes.add(c);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("attemptId", a.id);
        out.put("score", a.score);
        out.put("total", a.total);
        out.put("items", marked);
        out.put("changes", changes);
        return out;
    }

    private static int intOf(Object o, int dflt) {
        if (o instanceof Number n) return n.intValue();
        if (o == null) return dflt;
        try { return Integer.parseInt(String.valueOf(o)); } catch (NumberFormatException e) { return dflt; }
    }

    // ------------------------------------------------------------ history

    /** Attempts, newest first, optionally only one student's. */
    public synchronized List<Map<String, Object>> attempts(String studentId, String subjectId, int limit) {
        List<Attempt> list = new ArrayList<>();
        for (Attempt a : db.attempts) {
            if (studentId != null && !a.studentId.equals(studentId)) continue;
            if (subjectId != null && !a.subjectId.equals(subjectId)) continue;
            list.add(a);
        }
        list.sort(Comparator.comparing((Attempt a) -> a.finishedAt).reversed());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Attempt a : list) {
            if (limit > 0 && out.size() >= limit) break;
            out.add(summary(a));
        }
        return out;
    }

    public Map<String, Object> summary(Attempt a) {
        Map<String, Object> m = new LinkedHashMap<>();
        User st = db.user(a.studentId);
        Subject s = db.subject(a.subjectId);
        m.put("id", a.id);
        m.put("studentId", a.studentId);
        m.put("student", st == null ? "?" : st.name);
        m.put("subjectId", a.subjectId);
        m.put("subject", s == null ? "?" : s.code);
        m.put("color", s == null ? "moss" : s.color);
        m.put("kind", a.kind);
        m.put("finishedAt", a.finishedAt);
        m.put("score", a.score);
        m.put("total", a.total);
        int secs = 0, hints = 0, sureWrong = 0;
        for (AttemptItem it : a.items) {
            secs += it.seconds;
            if (it.hintUsed) hints++;
            if (!it.correct && it.confidence >= 3) sureWrong++;
        }
        m.put("minutes", Text.r1(secs / 60.0));
        m.put("hints", hints);
        m.put("sureWrong", sureWrong);
        return m;
    }

    /** One attempt in full (with answers) - for the student who took it, or a teacher. */
    public synchronized Map<String, Object> detail(User viewer, String attemptId) {
        Attempt a = db.attempt(attemptId);
        if (a == null) throw ApiError.notFound("Attempt");
        if (viewer.role == Role.STUDENT && !a.studentId.equals(viewer.id)) throw ApiError.forbidden();
        Map<String, Object> out = summary(a);
        List<Map<String, Object>> items = new ArrayList<>();
        for (AttemptItem it : a.items) {
            Question q = db.question(it.questionId);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("text", q == null ? "(deleted question)" : q.text);
            m.put("topic", db.topic(it.topicId) == null ? "?" : db.topic(it.topicId).name);
            m.put("options", q == null ? List.of() : q.options);
            m.put("answer", q == null ? -1 : q.answer);
            m.put("explanation", q == null ? "" : q.explanation);
            m.put("chosen", it.chosen);
            m.put("correct", it.correct);
            m.put("seconds", it.seconds);
            m.put("confidence", it.confidence);
            m.put("hintUsed", it.hintUsed);
            m.put("level", it.level);
            items.add(m);
        }
        out.put("items", items);
        return out;
    }

    // ------------------------------------------------------------ self rating

    public synchronized SelfReport rate(User student, String topicId, int rating, String note) {
        Topic t = db.topic(topicId);
        if (t == null || !t.active) throw ApiError.notFound("Topic");
        if (rating < 1 || rating > 5) throw ApiError.bad("Rating must be from 1 (easy) to 5 (very hard).");
        String today = db.today().toString();
        SelfReport r = null;
        for (SelfReport x : db.reports) if (x.studentId.equals(student.id) && x.topicId.equals(topicId) && x.date.equals(today)) r = x;
        if (r == null) {                      // one rating per topic per day; a second one replaces it
            r = new SelfReport();
            r.id = db.nextId("R");
            r.studentId = student.id;
            r.topicId = topicId;
            r.date = today;
            db.reports.add(r);
        }
        r.rating = rating;
        r.note = Text.cut(note, 300);
        db.log(student.name, "Rated a topic", t.name + " - " + rating + "/5");
        db.save("reports", "events");
        return r;
    }

    public int openCount() { return open.size(); }
}
