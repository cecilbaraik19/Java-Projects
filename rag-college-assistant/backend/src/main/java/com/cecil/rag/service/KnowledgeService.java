package com.cecil.rag.service;

import com.cecil.rag.model.Chunk;
import com.cecil.rag.model.Doc;
import com.cecil.rag.model.User;
import com.cecil.rag.rag.Chunker;
import com.cecil.rag.rag.DocReader;
import com.cecil.rag.rag.KnowledgeIndex;
import com.cecil.rag.store.DataStore;
import com.cecil.rag.util.Clock;
import com.cecil.rag.util.Json;
import com.cecil.rag.util.Text;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The library of college documents: upload, edit, publish / archive, and rebuild the search index.
 * Faculty can add and edit documents; only admins can delete.
 */
public final class KnowledgeService {
    public static final int MAX_UPLOAD = 8 * 1024 * 1024;

    private final DataStore store;
    private volatile KnowledgeIndex index;

    public KnowledgeService(DataStore store) {
        this.store = store;
        reindex();
    }

    public KnowledgeIndex index() { return index; }

    /** Rebuilds chunks, BM25 and vectors from the documents. Takes well under a second. */
    public synchronized KnowledgeIndex reindex() {
        List<Doc> docs;
        synchronized (store) { docs = new ArrayList<>(store.docs.values()); }
        index = KnowledgeIndex.build(docs, store.settings);
        return index;
    }

    public List<Map<String, Object>> list(User u) {
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (store) {
            for (Doc d : store.docs.values()) {
                if (!visible(u, d)) continue;
                Map<String, Object> m = d.summary();
                m.put("reviewDue", reviewDue(d));
                m.put("cited", citedCount(d.id));
                out.add(m);
            }
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("category")))
                .thenComparing(m -> String.valueOf(m.get("title"))));
        return out;
    }

    private long citedCount(String docId) {
        long n = 0;
        for (var r : store.records) for (var s : r.sources) if (docId.equals(s.get("docId"))) { n++; break; }
        return n;
    }

    public static boolean reviewDue(Doc d) {
        try {
            return !d.reviewDate.isBlank() && LocalDate.parse(d.reviewDate).isBefore(Clock.today().plusDays(30))
                    && d.searchable();
        } catch (Exception e) { return false; }
    }

    /** Students see only published documents meant for them. */
    public static boolean visible(User u, Doc d) {
        if (!u.role.canSee(d.audience)) return false;
        return u.role.canCurate() || d.searchable();
    }

    public Map<String, Object> get(User u, String id) {
        Doc d;
        synchronized (store) { d = store.docs.get(id); }
        if (d == null || !visible(u, d)) throw ApiError.notFound("Document");
        Map<String, Object> m = d.toJson();
        m.put("words", Text.wordCount(d.text));
        m.put("reviewDue", reviewDue(d));
        List<Chunk> chunks = d.searchable() ? index.chunksOf(d.id) : List.of();
        if (chunks.isEmpty()) chunks = new Chunker(store.settings.chunkWords, store.settings.overlapWords).chunk(d);
        List<Map<String, Object>> cl = new ArrayList<>();
        for (Chunk c : chunks) cl.add(c.toJson());
        m.put("chunks", cl);
        m.put("inIndex", d.searchable());
        return m;
    }

    public Doc create(User u, Map<String, Object> body) {
        if (!u.role.canCurate()) throw ApiError.forbidden();
        String fileName = Json.str(body, "fileName", "").replaceAll("[^A-Za-z0-9._ -]", "_");
        String text = Json.str(body, "text", "");
        String data = Json.str(body, "dataBase64", "");
        if (!data.isEmpty()) {
            if (fileName.isBlank() || !DocReader.supported(fileName))
                throw ApiError.bad("Use a .txt, .md, .csv, .html, .docx or .pdf file.");
            if (data.length() > MAX_UPLOAD * 4 / 3 + 16) throw new ApiError(413, "The file is too big (8 MB at most).");
            byte[] bytes;
            try { bytes = Base64.getDecoder().decode(data); } catch (IllegalArgumentException e) { throw ApiError.bad("The file could not be read."); }
            try { text = DocReader.read(fileName, bytes); }
            catch (IllegalArgumentException e) { throw ApiError.bad(e.getMessage()); }
        }
        text = text.replace("\r\n", "\n").strip();
        if (Text.wordCount(text) < 10) throw ApiError.bad("The document has almost no text (at least 10 words are needed).");
        if (text.length() > 400_000) throw ApiError.bad("The document is too long (400,000 characters at most).");
        Doc d = new Doc();
        String title = Json.str(body, "title", "").strip();
        if (title.isEmpty()) title = guessTitle(text, fileName);
        d.title = title;
        d.fileName = fileName.isBlank() ? "typed-in.md" : fileName;
        d.fileType = d.fileName.contains(".") ? d.fileName.substring(d.fileName.lastIndexOf('.') + 1).toLowerCase() : "md";
        d.text = text;
        d.uploadedBy = u.username;
        d.uploadedAt = Clock.now();
        d.updatedBy = u.username;
        d.updatedAt = d.uploadedAt;
        applyMeta(d, body);
        if (!body.containsKey("status")) d.status = "published";
        synchronized (store) {
            d.id = store.nextId("doc-");
            store.docs.put(d.id, d);
            store.saveDocs();
            store.saveSettings(); // keeps the id counter
        }
        reindex();
        return d;
    }

    public Doc update(User u, String id, Map<String, Object> body) {
        if (!u.role.canCurate()) throw ApiError.forbidden();
        Doc d;
        synchronized (store) {
            d = store.docs.get(id);
            if (d == null || !u.role.canSee(d.audience)) throw ApiError.notFound("Document");
            if (body.containsKey("title")) {
                String t = Json.str(body, "title", "").strip();
                if (t.isEmpty()) throw ApiError.bad("The title cannot be empty.");
                d.title = t;
            }
            if (body.containsKey("text")) {
                String t = Json.str(body, "text", "").replace("\r\n", "\n").strip();
                if (Text.wordCount(t) < 10) throw ApiError.bad("The document has almost no text.");
                if (t.length() > 400_000) throw ApiError.bad("The document is too long.");
                if (!t.equals(d.text)) { d.text = t; d.version++; }
            }
            applyMeta(d, body);
            d.updatedBy = u.username;
            d.updatedAt = Clock.now();
            store.saveDocs();
        }
        reindex();
        return d;
    }

    public void delete(User u, String id) {
        if (!u.role.isAdmin()) throw ApiError.forbidden();
        synchronized (store) {
            if (store.docs.remove(id) == null) throw ApiError.notFound("Document");
            store.saveDocs();
        }
        reindex();
    }

    private void applyMeta(Doc d, Map<String, Object> body) {
        if (body.containsKey("category")) {
            String c = Json.str(body, "category", "");
            if (!Arrays.asList(Doc.CATEGORIES).contains(c)) throw ApiError.bad("Unknown category.");
            d.category = c;
        }
        if (body.containsKey("department")) d.department = clip(Json.str(body, "department", ""), 80, "All departments");
        if (body.containsKey("audience")) {
            String a = Json.str(body, "audience", "all");
            if (!a.matches("all|students|faculty")) throw ApiError.bad("Audience must be all, students or faculty.");
            d.audience = a;
        }
        if (body.containsKey("status")) {
            String s = Json.str(body, "status", "published");
            if (!s.matches("published|draft|archived")) throw ApiError.bad("Status must be published, draft or archived.");
            d.status = s;
        }
        if (body.containsKey("effectiveDate")) d.effectiveDate = date(Json.str(body, "effectiveDate", ""));
        if (body.containsKey("reviewDate")) d.reviewDate = date(Json.str(body, "reviewDate", ""));
    }

    private static String date(String s) {
        if (s == null || s.isBlank()) return "";
        try { return LocalDate.parse(s.strip()).toString(); } catch (Exception e) { throw ApiError.bad("Dates must look like 2026-07-01."); }
    }

    private static String clip(String s, int max, String def) {
        s = s == null ? "" : s.strip();
        if (s.isEmpty()) return def;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String guessTitle(String text, String fileName) {
        for (String line : text.split("\n")) {
            String l = line.replaceFirst("^#+\\s*", "").strip();
            if (l.length() >= 4 && l.length() <= 90) return l;
        }
        return fileName.isBlank() ? "Untitled document" : fileName.replaceAll("\\.[A-Za-z]+$", "").replace('-', ' ');
    }

    public Map<String, Object> stats() {
        KnowledgeIndex ix = index;
        int published = 0, drafts = 0, archived = 0, due = 0;
        synchronized (store) {
            for (Doc d : store.docs.values()) {
                switch (d.status) {
                    case "published" -> published++;
                    case "draft" -> drafts++;
                    default -> archived++;
                }
                if (reviewDue(d)) due++;
            }
        }
        return Json.map("published", published, "drafts", drafts, "archived", archived, "reviewDue", due,
                "chunks", ix.chunks.size(), "flagged", ix.flagged.size(), "vocabulary", ix.bm25.vocabularySize(),
                "avgChunkWords", Math.round(ix.bm25.averageLength()), "vectorDims", ix.lsa.dims(),
                "lsaVocabulary", ix.lsa.vocabularySize(), "builtAt", ix.builtAt, "buildMs", ix.buildMs,
                "chunkWords", ix.chunkWords, "overlapWords", ix.overlapWords);
    }
}
