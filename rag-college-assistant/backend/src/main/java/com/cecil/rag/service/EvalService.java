package com.cecil.rag.service;

import com.cecil.rag.model.Doc;
import com.cecil.rag.model.Role;
import com.cecil.rag.model.User;
import com.cecil.rag.rag.Answer;
import com.cecil.rag.rag.ExtractiveGenerator;
import com.cecil.rag.rag.Retriever;
import com.cecil.rag.store.DataStore;
import com.cecil.rag.util.Clock;
import com.cecil.rag.util.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluation: a fixed list of test questions, each with the document that holds the answer and the
 * words the answer must contain. The test is run with 4 set-ups so you can SEE what each part adds:
 *   BM25 only  ·  Vectors only  ·  Hybrid (BM25 + vectors)  ·  Full (hybrid + re-rank + MMR)
 * Measures:
 *   Hit@1     the first chunk is from the right document
 *   Recall@K  a chunk from the right document is among the K chunks given to the answer
 *   MRR       1 / position of the first right chunk (1.0 = always first)
 *   Answer    the final answer contains all the expected words (Full only)
 */
public final class EvalService {
    private final DataStore store;
    private final KnowledgeService knowledge;

    public EvalService(DataStore store, KnowledgeService knowledge) {
        this.store = store;
        this.knowledge = knowledge;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> cases() {
        synchronized (store) {
            return (List<Map<String, Object>>) (List<?>) Json.list(store.evaluation, "cases");
        }
    }

    public Map<String, Object> view() {
        synchronized (store) {
            Map<String, Object> out = new LinkedHashMap<>(store.evaluation);
            out.putIfAbsent("cases", new ArrayList<>());
            List<Map<String, Object>> docs = new ArrayList<>();
            for (Doc d : store.docs.values()) docs.add(Json.map("id", d.id, "title", d.title, "status", d.status));
            out.put("docs", docs);
            return out;
        }
    }

    public Map<String, Object> addCase(User u, Map<String, Object> body) {
        if (!u.role.isAdmin()) throw ApiError.forbidden();
        String q = Json.str(body, "question", "").strip();
        String doc = Json.str(body, "expectedDoc", "");
        if (q.length() < 5 || q.length() > 300) throw ApiError.bad("The question must have 5 to 300 characters.");
        List<Object> kw = new ArrayList<>();
        for (String k : Json.str(body, "keywords", "").split(",")) if (!k.isBlank()) kw.add(k.strip());
        synchronized (store) {
            if (!store.docs.containsKey(doc)) throw ApiError.bad("Choose the document that has the answer.");
            Map<String, Object> c = Json.map("id", store.nextId("e"), "question", q, "expectedDoc", doc, "keywords", kw);
            List<Object> cases = Json.list(store.evaluation, "cases");
            cases.add(c);
            store.evaluation.put("cases", cases);
            store.saveEvaluation();
            store.saveSettings();
            return c;
        }
    }

    public void deleteCase(User u, String id) {
        if (!u.role.isAdmin()) throw ApiError.forbidden();
        synchronized (store) {
            List<Object> cases = Json.list(store.evaluation, "cases");
            if (!cases.removeIf(o -> o instanceof Map<?, ?> m && id.equals(m.get("id")))) throw ApiError.notFound("Test question");
            store.evaluation.put("cases", cases);
            store.saveEvaluation();
        }
    }

    public Map<String, Object> run() {
        User admin = new User();
        admin.role = Role.ADMIN;
        List<Map<String, Object>> cases = cases();
        var index = knowledge.index();
        var settings = store.settings;
        ExtractiveGenerator gen = new ExtractiveGenerator();

        List<Map<String, Object>> summary = new ArrayList<>();
        List<Map<String, Object>> details = new ArrayList<>();
        for (Map<String, Object> c : cases) details.add(Json.map("id", c.get("id"), "question", c.get("question"),
                "expectedDoc", c.get("expectedDoc"), "ranks", new LinkedHashMap<String, Object>()));

        for (Retriever.Mode mode : Retriever.Mode.values()) {
            double hit1 = 0, recall = 0, mrr = 0, answerOk = 0, answered = 0;
            long t0 = System.nanoTime();
            for (int i = 0; i < cases.size(); i++) {
                Map<String, Object> c = cases.get(i);
                String expected = String.valueOf(c.get("expectedDoc"));
                Retriever.Result r = new Retriever(index, settings).search(String.valueOf(c.get("question")), admin.role, "", mode);
                int rank = 0;
                for (int k = 0; k < Math.min(10, r.ranked.size()); k++) {
                    if (r.ranked.get(k).chunk.doc.id.equals(expected)) { rank = k + 1; break; }
                }
                boolean inSelected = r.selected.stream().anyMatch(h -> h.chunk.doc.id.equals(expected));
                if (rank == 1) hit1++;
                if (inSelected) recall++;
                if (rank > 0) mrr += 1.0 / rank;
                @SuppressWarnings("unchecked") Map<String, Object> ranks = (Map<String, Object>) details.get(i).get("ranks");
                ranks.put(mode.name(), rank);
                if (mode == Retriever.Mode.FULL) {
                    boolean ok = false;
                    String answerText = "";
                    if (r.answerable) {
                        answered++;
                        Answer a = gen.generate(r);
                        answerText = a.text;
                        ok = true;
                        for (Object kw : Json.list(c, "keywords")) {
                            String k = String.valueOf(kw).toLowerCase().replace(",", "");
                            if (!a.text.toLowerCase().replace(",", "").contains(k)) { ok = false; break; }
                        }
                        if (ok) answerOk++;
                    }
                    details.get(i).put("answerOk", ok);
                    details.get(i).put("answerable", r.answerable);
                    details.get(i).put("answer", answerText);
                    details.get(i).put("topDoc", r.selected.isEmpty() ? "" : r.selected.get(0).chunk.doc.title);
                }
            }
            int n = Math.max(1, cases.size());
            Map<String, Object> row = Json.map("mode", mode.name(), "label", label(mode), "hit1", hit1 / n,
                    "recall", recall / n, "mrr", mrr / n, "msPerQuestion", (System.nanoTime() - t0) / 1e6 / n);
            if (mode == Retriever.Mode.FULL) { row.put("answerAccuracy", answerOk / n); row.put("answered", answered / n); }
            summary.add(row);
        }
        Map<String, Object> run = Json.map("at", Clock.now(), "count", cases.size(), "topK", settings.topK,
                "summary", summary, "details", details);
        synchronized (store) {
            store.evaluation.put("lastRun", run);
            store.saveEvaluation();
        }
        return run;
    }

    public static String label(Retriever.Mode m) {
        return switch (m) {
            case BM25 -> "Keywords only (BM25)";
            case VECTOR -> "Vectors only (LSA)";
            case HYBRID -> "Hybrid (BM25 + vectors, RRF)";
            case FULL -> "Full pipeline (hybrid + re-rank + MMR)";
        };
    }
}
