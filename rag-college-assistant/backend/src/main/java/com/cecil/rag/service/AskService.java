package com.cecil.rag.service;

import com.cecil.rag.model.QaRecord;
import com.cecil.rag.model.Role;
import com.cecil.rag.model.Settings;
import com.cecil.rag.model.User;
import com.cecil.rag.model.Verified;
import com.cecil.rag.rag.Answer;
import com.cecil.rag.rag.ClaudeGenerator;
import com.cecil.rag.rag.ExtractiveGenerator;
import com.cecil.rag.rag.Grounding;
import com.cecil.rag.rag.QueryRewriter;
import com.cecil.rag.rag.Retriever;
import com.cecil.rag.store.DataStore;
import com.cecil.rag.util.Clock;
import com.cecil.rag.util.Json;
import com.cecil.rag.util.Text;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The whole question-answer flow:
 *   question -> standalone question (follow-ups) -> verified answer? -> retrieve -> generate -> ground-check -> save
 * Also: feedback, chat history, verified answers, and the "gaps" list (questions the documents could not answer).
 */
public final class AskService {
    public static final int MAX_QUESTION = 500;
    public static final int PER_MINUTE = 20;

    private final DataStore store;
    private final KnowledgeService knowledge;
    private final ExtractiveGenerator extractive = new ExtractiveGenerator();
    private final Map<String, Deque<Long>> rate = new ConcurrentHashMap<>();
    private volatile String apiKey = ClaudeGenerator.keyFromEnv();

    public AskService(DataStore store, KnowledgeService knowledge) {
        this.store = store;
        this.knowledge = knowledge;
    }

    public boolean claudeAvailable() { return apiKey != null; }
    public void setApiKeyForTests(String key) { apiKey = key; }

    // ================================================================= ask

    public Map<String, Object> ask(User u, Map<String, Object> body) {
        String question = Json.str(body, "question", "").replaceAll("\\s+", " ").strip();
        if (question.length() < 3) throw ApiError.bad("Please type a question.");
        if (question.length() > MAX_QUESTION) throw ApiError.bad("The question is too long (" + MAX_QUESTION + " characters at most).");
        checkRate(u.id);
        String category = Json.str(body, "category", "");
        String sessionId = Json.str(body, "sessionId", "");
        return answer(u, question, sessionId, category, true);
    }

    /** Runs the pipeline. save=false is used by the seed data generator's dry runs and by tests. */
    public Map<String, Object> answer(User u, String question, String sessionId, String category, boolean save) {
        long t0 = System.nanoTime();
        Settings s = store.settings;

        // 1. follow-up? make the question stand alone using the previous question of this chat
        String previous = null;
        synchronized (store) {
            if (!sessionId.isBlank()) {
                for (int i = store.records.size() - 1; i >= 0; i--) {
                    QaRecord r = store.records.get(i);
                    if (r.sessionId.equals(sessionId)) {
                        if (!r.userId.equals(u.id)) { sessionId = ""; break; }
                        if (Clock.now() - r.at < 60 * 60 * 1000) previous = r.standalone.isBlank() ? r.question : r.standalone;
                        break;
                    }
                }
            }
            if (sessionId.isBlank()) sessionId = store.nextId("s");
        }
        String standalone = QueryRewriter.rewrite(question, previous);

        // 2. retrieve
        Retriever.Result rr = new Retriever(knowledge.index(), s).search(standalone, u.role, category, Retriever.Mode.FULL);

        QaRecord rec = new QaRecord();
        rec.userId = u.id;
        rec.sessionId = sessionId;
        rec.question = question;
        rec.standalone = standalone.equals(question) ? "" : standalone;
        rec.topScore = rr.topScore();
        rec.at = Clock.now();
        Map<String, Object> extra = new LinkedHashMap<>();

        // 3. a verified answer written by faculty wins if the question is almost the same
        Verified v = matchVerified(standalone);
        Answer a;
        if (v != null) {
            a = new Answer();
            a.mode = "verified";
            a.text = v.answer;
            a.confidence = 0.97;
            extra.put("verifiedBy", v.byName);
            extra.put("verifiedQuestion", v.question);
            synchronized (store) { v.uses++; if (save) store.saveVerified(); }
        } else if (!rr.answerable) {
            a = new Answer();
            a.found = false;
        } else {
            a = generate(standalone, rr, s);
        }

        // 4. fill in the record
        rec.mode = a.found ? a.mode : "none";
        rec.status = a.found ? "answered" : "unanswered";
        rec.answer = a.found ? a.text : "Sorry, I could not find this in the college documents. Your question has been sent to the faculty so the answer can be added.";
        rec.shortAnswer = a.found ? a.shortAnswer : "";
        rec.confidence = a.found ? a.confidence : 0;
        rec.grounded = a.grounded;
        List<Retriever.Hit> sources = a.found ? rr.selected : List.of();
        for (int i = 0; i < sources.size(); i++) {
            Retriever.Hit h = sources.get(i);
            rec.sources.add(Json.map("n", i + 1, "docId", h.chunk.doc.id, "chunkId", h.chunk.id, "title", h.chunk.doc.title,
                    "section", h.chunk.section, "category", h.chunk.doc.category, "text", Text.clip(h.chunk.text, 700),
                    "score", Math.round(h.score * 1000) / 1000.0, "effectiveDate", h.chunk.doc.effectiveDate));
        }
        if (!sources.isEmpty()) rec.category = sources.get(0).chunk.doc.category;
        else if (!rr.ranked.isEmpty()) rec.category = rr.ranked.get(0).chunk.doc.category;
        rec.ms = (System.nanoTime() - t0) / 1_000_000;

        if (!a.found) {
            Set<String> seen = new LinkedHashSet<>();
            List<Map<String, Object>> sug = new ArrayList<>();
            for (Retriever.Hit h : rr.ranked) {
                if (seen.add(h.chunk.doc.id)) sug.add(Json.map("docId", h.chunk.doc.id, "title", h.chunk.doc.title, "section", h.chunk.section));
                if (sug.size() == 3) break;
            }
            extra.put("suggestions", sug);
        }
        if (save) {
            synchronized (store) {
                rec.id = store.nextId("q");
                store.records.add(rec);
                store.saveRecords();
            }
        } else rec.id = "dry";

        Map<String, Object> out = rec.toJson();
        out.putAll(extra);
        out.put("note", a.note);
        out.put("unsupported", a.unsupported);
        out.put("trace", rr.toJson());
        if (!a.prompt.isEmpty()) out.put("prompt", a.prompt);
        out.put("questionType", rr.type.label);
        return out;
    }

    private Answer generate(String question, Retriever.Result rr, Settings s) {
        List<String> texts = new ArrayList<>();
        for (Retriever.Hit h : rr.selected) texts.add(h.chunk.text);
        Answer a = null;
        String note = "";
        if (s.useClaude && apiKey != null) {
            try {
                a = new ClaudeGenerator(apiKey, s.claudeModel).generate(question, rr.selected);
                if (a.found) {
                    Grounding.check(a, texts);
                    a.shortAnswer = new ExtractiveGenerator().generate(rr).shortAnswer;
                    a.confidence = Math.max(0, Math.min(0.98, 0.5 * Math.min(1, rr.topScore() / 0.8) + 0.5 * a.grounded));
                }
                return a;
            } catch (Exception e) {
                note = "Claude could not be reached (" + Text.clip(e.getMessage(), 90) + "), so the offline answer is shown.";
            }
        } else if (s.useClaude) {
            note = "Claude is switched on but no API key is set, so the offline answer is shown.";
        }
        a = extractive.generate(rr);
        Grounding.check(a, texts);
        a.note = note;
        // a fallback prompt is still shown so students can see what WOULD be sent
        a.prompt = ClaudeGenerator.SYSTEM + "\n\n" + ClaudeGenerator.buildPrompt(question, rr.selected);
        return a;
    }

    private Verified matchVerified(String question) {
        Set<String> q = Text.tokenSet(question);
        Verified best = null;
        double bestSim = 0;
        synchronized (store) {
            for (Verified v : store.verified.values()) {
                double sim = Text.dice(q, Text.tokenSet(v.question));
                if (sim > bestSim) { bestSim = sim; best = v; }
            }
        }
        return bestSim >= store.settings.verifiedMatch ? best : null;
    }

    private void checkRate(String userId) {
        Deque<Long> d = rate.computeIfAbsent(userId, k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        synchronized (d) {
            while (!d.isEmpty() && now - d.peekFirst() > 60_000) d.pollFirst();
            if (d.size() >= PER_MINUTE) throw new ApiError(429, "Too many questions in one minute. Please wait a little.");
            d.addLast(now);
        }
    }

    /** The Press Room: run only the search part for any question and show every stage. */
    public Map<String, Object> trace(User u, String question, String mode) {
        if (question == null || question.isBlank()) throw ApiError.bad("Please type a question.");
        if (question.length() > MAX_QUESTION) throw ApiError.bad("The question is too long.");
        Retriever.Mode m;
        try { m = Retriever.Mode.valueOf(mode == null || mode.isBlank() ? "FULL" : mode.toUpperCase()); }
        catch (Exception e) { throw ApiError.bad("Unknown mode."); }
        Retriever.Result rr = new Retriever(knowledge.index(), store.settings).search(question.strip(), u.role, "", m);
        Map<String, Object> out = rr.toJson();
        out.put("minScore", store.settings.minScore);
        out.put("minCoverage", Retriever.MIN_COVERAGE);
        out.put("topK", store.settings.topK);
        out.put("weights", Json.map("coverage", Retriever.W_COVER, "bm25", Retriever.W_BM25, "cosine", Retriever.W_COS,
                "proximity", Retriever.W_PROX, "heading", Retriever.W_HEAD, "type", Retriever.W_TYPE));
        out.put("queryWeights", rr.toJson().get("weights"));
        if (rr.answerable) {
            Answer a = extractive.generate(rr);
            out.put("answerPreview", a.text);
            out.put("prompt", ClaudeGenerator.SYSTEM + "\n\n" + ClaudeGenerator.buildPrompt(question, rr.selected));
        }
        return out;
    }

    // ================================================================= history and feedback

    public List<Map<String, Object>> sessions(User u) {
        Map<String, Map<String, Object>> map = new LinkedHashMap<>();
        synchronized (store) {
            for (QaRecord r : store.records) {
                if (!r.userId.equals(u.id)) continue;
                Map<String, Object> s = map.computeIfAbsent(r.sessionId, k -> Json.map("id", k, "title", r.question,
                        "count", 0L, "first", r.at, "last", r.at));
                s.put("count", (Long) s.get("count") + 1);
                s.put("last", r.at);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(map.values());
        out.sort(Comparator.comparingLong((Map<String, Object> m) -> (Long) m.get("last")).reversed());
        return out.size() > 60 ? out.subList(0, 60) : out;
    }

    public List<Map<String, Object>> session(User u, String sessionId) {
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (store) {
            for (QaRecord r : store.records) if (r.sessionId.equals(sessionId) && r.userId.equals(u.id)) out.add(r.toJson());
        }
        return out;
    }

    public List<Map<String, Object>> history(User u, String q) {
        List<Map<String, Object>> out = new ArrayList<>();
        String needle = q == null ? "" : q.toLowerCase().strip();
        synchronized (store) {
            for (int i = store.records.size() - 1; i >= 0 && out.size() < 200; i--) {
                QaRecord r = store.records.get(i);
                if (!r.userId.equals(u.id)) continue;
                if (!needle.isEmpty() && !r.question.toLowerCase().contains(needle)) continue;
                out.add(r.toJson());
            }
        }
        return out;
    }

    public QaRecord feedback(User u, String id, Map<String, Object> body) {
        String vote = Json.str(body, "vote", "");
        if (!vote.matches("up|down|")) throw ApiError.bad("Vote must be up or down.");
        synchronized (store) {
            QaRecord r = find(id);
            if (!r.userId.equals(u.id)) throw ApiError.forbidden();
            r.feedback = vote;
            r.feedbackNote = Text.clip(Json.str(body, "note", ""), 300);
            if ("down".equals(vote)) r.resolved = false;
            store.saveRecords();
            return r;
        }
    }

    private QaRecord find(String id) {
        for (QaRecord r : store.records) if (r.id.equals(id)) return r;
        throw ApiError.notFound("Question");
    }

    // ================================================================= gaps (curators)

    /** Questions the documents could not answer, or answers marked "not helpful", grouped by similar wording. */
    public Map<String, Object> gaps(User u, boolean showResolved) {
        if (!u.role.canCurate()) throw ApiError.forbidden();
        List<Map<String, Object>> groups = new ArrayList<>();
        List<Set<String>> groupTokens = new ArrayList<>();
        int open = 0;
        synchronized (store) {
            for (int i = store.records.size() - 1; i >= 0; i--) {
                QaRecord r = store.records.get(i);
                boolean gap = "unanswered".equals(r.status) || "down".equals(r.feedback);
                if (!gap || (r.resolved && !showResolved)) continue;
                if (!r.resolved) open++;
                Set<String> toks = Text.tokenSet(r.standalone.isBlank() ? r.question : r.standalone);
                int found = -1;
                for (int g = 0; g < groupTokens.size(); g++) if (Text.dice(toks, groupTokens.get(g)) >= 0.6) { found = g; break; }
                User asker = store.users.get(r.userId);
                Map<String, Object> item = Json.map("id", r.id, "question", r.question, "at", r.at, "kind",
                        "unanswered".equals(r.status) ? "Not found" : "Not helpful", "note", r.feedbackNote,
                        "answer", r.answer, "resolved", r.resolved, "resolvedNote", r.resolvedNote,
                        "asker", asker == null ? "?" : asker.role.label, "topScore", r.topScore, "category", r.category);
                if (found < 0) {
                    groupTokens.add(toks);
                    groups.add(Json.map("question", r.question, "count", 1L, "last", r.at, "kind", item.get("kind"),
                            "category", r.category, "resolved", r.resolved, "items", new ArrayList<>(List.of(item))));
                } else {
                    Map<String, Object> grp = groups.get(found);
                    grp.put("count", (Long) grp.get("count") + 1);
                    @SuppressWarnings("unchecked") List<Object> items = (List<Object>) grp.get("items");
                    items.add(item);
                    if (!r.resolved) grp.put("resolved", false);
                }
            }
        }
        groups.sort(Comparator.comparingLong((Map<String, Object> g) -> -(Long) g.get("count")));
        return Json.map("open", open, "groups", groups);
    }

    public int resolve(User u, Map<String, Object> body) {
        if (!u.role.canCurate()) throw ApiError.forbidden();
        String note = Text.clip(Json.str(body, "note", ""), 300);
        int n = 0;
        synchronized (store) {
            for (Object o : Json.list(body, "ids")) {
                for (QaRecord r : store.records) if (r.id.equals(String.valueOf(o))) {
                    r.resolved = true;
                    r.resolvedNote = note.isBlank() ? "Resolved by " + u.name : note;
                    n++;
                }
            }
            store.saveRecords();
        }
        return n;
    }

    // ================================================================= verified answers

    public List<Map<String, Object>> verifiedList() {
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (store) {
            for (Verified v : store.verified.values()) {
                Map<String, Object> m = v.toJson();
                List<Map<String, Object>> docs = new ArrayList<>();
                for (String id : v.docIds) {
                    var d = store.docs.get(id);
                    if (d != null) docs.add(Json.map("id", d.id, "title", d.title));
                }
                m.put("docs", docs);
                out.add(m);
            }
        }
        out.sort(Comparator.comparingLong((Map<String, Object> m) -> -((Number) m.get("uses")).longValue()));
        return out;
    }

    public Verified saveVerified(User u, String id, Map<String, Object> body) {
        if (!u.role.canCurate()) throw ApiError.forbidden();
        String q = Json.str(body, "question", "").strip();
        String a = Json.str(body, "answer", "").strip();
        if (q.length() < 5 || q.length() > 300) throw ApiError.bad("The question must have 5 to 300 characters.");
        if (a.length() < 5 || a.length() > 2000) throw ApiError.bad("The answer must have 5 to 2000 characters.");
        synchronized (store) {
            Verified v = id == null ? new Verified() : store.verified.get(id);
            if (v == null) throw ApiError.notFound("Verified answer");
            if (id == null) { v.id = store.nextId("v"); v.at = Clock.now(); }
            v.question = q;
            v.answer = a;
            v.byId = u.id;
            v.byName = u.name;
            v.docIds.clear();
            for (Object o : Json.list(body, "docIds")) if (store.docs.containsKey(String.valueOf(o))) v.docIds.add(String.valueOf(o));
            store.verified.put(v.id, v);
            store.saveVerified();
            store.saveSettings();
            return v;
        }
    }

    public void deleteVerified(User u, String id) {
        if (!u.role.canCurate()) throw ApiError.forbidden();
        synchronized (store) {
            if (store.verified.remove(id) == null) throw ApiError.notFound("Verified answer");
            store.saveVerified();
        }
    }

    public static boolean canSeeRecord(User u, QaRecord r) { return r.userId.equals(u.id) || u.role == Role.ADMIN; }
}
