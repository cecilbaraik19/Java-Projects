package com.cecil.chatbot.service;

import com.cecil.chatbot.knowledge.Retriever;
import com.cecil.chatbot.model.KnowledgeDoc;
import com.cecil.chatbot.model.User;
import com.cecil.chatbot.store.DataStore;
import com.cecil.chatbot.util.Json;
import com.cecil.chatbot.util.Passwords;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The knowledge base: notes the chatbot can search (RAG). Everyone can read; AI trainers and admin can change. */
public class KnowledgeService {

    static final int MAX_DOCS = 300;
    static final int MAX_CHARS = 200_000;

    private final DataStore store;
    public final Retriever retriever = new Retriever();

    public KnowledgeService(DataStore store) {
        this.store = store;
        reindex();
    }

    public void reindex() {
        List<KnowledgeDoc> copy;
        synchronized (store.lock) {
            copy = new ArrayList<>(store.knowledge);
        }
        retriever.rebuild(copy);
    }

    public Map<String, Object> list() {
        List<Map<String, Object>> docs = new ArrayList<>();
        synchronized (store.lock) {
            for (KnowledgeDoc d : store.knowledge) docs.add(d.summary());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("docs", docs);
        m.put("chunks", retriever.chunkCount());
        return m;
    }

    public Map<String, Object> get(String id) {
        synchronized (store.lock) {
            KnowledgeDoc d = store.doc(id);
            if (d == null) throw ServiceException.notFound("No such note.");
            return d.toMap();
        }
    }

    public Map<String, Object> save(User me, String id, Map<String, Object> body) {
        AuthService.requireTrainer(me);
        Map<String, Object> out;
        synchronized (store.lock) {
            KnowledgeDoc d;
            if (id == null) {
                if (store.knowledge.size() >= MAX_DOCS) throw ServiceException.bad("The knowledge base is full (" + MAX_DOCS + " notes).");
                d = new KnowledgeDoc();
                d.id = "kb" + Passwords.randomId();
                d.createdBy = me.username;
            } else {
                d = store.doc(id);
                if (d == null) throw ServiceException.notFound("No such note.");
            }
            String title = AuthService.clean(Json.str(body, "title", d.title), 100);
            if (title == null || title.length() < 2) throw ServiceException.bad("Give the note a title.");
            String text = Json.str(body, "text", d.text);
            text = text == null ? "" : text.replace("\r", "").replaceAll("[\\p{Cntrl}&&[^\n\t]]", "").trim();
            if (text.length() < 20) throw ServiceException.bad("The note is too short (at least 20 characters).");
            if (text.length() > MAX_CHARS) throw ServiceException.bad("The note is too long (at most 200,000 characters). Split it into parts.");
            d.title = title;
            d.text = text;
            d.tags = AuthService.clean(Json.str(body, "tags", d.tags), 120);
            d.updatedAt = store.nowText();
            if (id == null) store.knowledge.add(d);
            store.saveKnowledge();
            out = d.summary();
        }
        reindex();
        return out;
    }

    public void delete(User me, String id) {
        AuthService.requireTrainer(me);
        synchronized (store.lock) {
            KnowledgeDoc d = store.doc(id);
            if (d == null) throw ServiceException.notFound("No such note.");
            store.knowledge.remove(d);
            store.saveKnowledge();
        }
        reindex();
    }

    /** Search test box: shows which parts the bot would use for a question, with scores. */
    public List<Map<String, Object>> search(String q, int k) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Retriever.Hit h : retriever.search(q == null ? "" : q, Math.max(1, Math.min(10, k)))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("n", h.n());
            m.put("docId", h.docId());
            m.put("title", h.title());
            m.put("text", h.text());
            m.put("score", h.score());
            out.add(m);
        }
        return out;
    }
}
