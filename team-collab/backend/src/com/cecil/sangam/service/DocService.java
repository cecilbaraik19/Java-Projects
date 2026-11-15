package com.cecil.sangam.service;

import com.cecil.sangam.core.EventBus;
import com.cecil.sangam.core.TextDiff;
import com.cecil.sangam.model.Doc;
import com.cecil.sangam.model.Project;
import com.cecil.sangam.model.User;
import com.cecil.sangam.store.DataStore;
import com.cecil.sangam.util.Clock;
import com.cecil.sangam.util.Json;
import com.cecil.sangam.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared documents with versions.
 *
 * Saving sends "baseVersion" = the version the person started editing from.
 *  - base == current version → saved as the next version.
 *  - base is older (someone saved in between) → 3-way merge (TextDiff.merge3) of base, mine and theirs.
 *      clean   → the merged text is saved, and the person is told it was combined with the other change.
 *      clashes → nothing is saved; the person gets the merged text with conflict markers, fixes it and saves again.
 * People who have the editor open send a small "I am editing" signal, so others see "Lena is editing too".
 */
public class DocService {
    public static final int MAX_CHARS = 60_000;
    public static final long EDITING_MS = 40_000;

    private final DataStore store;
    private final EventBus events;
    /** docId -> (userId -> last "I am editing" signal). Only in memory. */
    private final Map<String, Map<String, Long>> editing = new ConcurrentHashMap<>();

    public DocService(DataStore store, EventBus events) {
        this.store = store;
        this.events = events;
    }

    public Doc create(User u, String projectId, Map<String, Object> b) {
        synchronized (store) {
            Project p = Access.project(store, u, projectId);
            if (!Access.workOn(u, p)) throw Problem.forbidden("Guests can read documents but not create them.");
            String title = Text.clean(Json.str(b, "title"), 80);
            if (Text.blank(title) || title.length() < 3) throw Problem.bad("A document title needs 3 to 80 characters.");
            Doc d = new Doc();
            d.id = store.nextId("D", 1);
            d.projectId = p.id;
            d.title = title;
            d.body = body(Json.str(b, "body", ""));
            d.createdAt = d.updatedAt = Clock.millis();
            d.createdBy = d.updatedBy = u.id;
            store.docs.put(d.id, d);
            store.log(u.id, "doc.create", p.key, title);
            store.save("docs", "audit");
            events.publish("doc", null, null, p.id, Json.obj("id", d.id, "version", d.version));
            return d;
        }
    }

    public record Saved(Doc doc, boolean merged, String with) { }

    public Saved save(User u, String id, Map<String, Object> b) {
        synchronized (store) {
            Doc d = doc(u, id);
            if (!Access.workOn(u, store.projects.get(d.projectId))) throw Problem.forbidden("Guests can read documents but not change them.");
            int base = (int) Json.num(b, "baseVersion", -1);
            if (base < 1 || base > d.version) throw Problem.bad("Unknown base version.");
            String mine = body(Json.str(b, "body", ""));
            String title = b.containsKey("title") ? Text.clean(Json.str(b, "title"), 80) : d.title;
            if (Text.blank(title) || title.length() < 3) throw Problem.bad("A document title needs 3 to 80 characters.");
            List<String> mineLines = TextDiff.lines(mine);
            if (TextDiff.hasMarkers(mineLines)) throw Problem.bad("The text still has conflict markers (<<<<<<< ======= >>>>>>>). Choose what to keep first.");
            if (mineLines.size() > TextDiff.MAX_LINES) throw Problem.bad("A document can have at most " + TextDiff.MAX_LINES + " lines.");
            String note = Text.clean(Json.str(b, "note", ""), 120);
            String finalText = mine;
            boolean merged = false;
            String with = null;
            if (base != d.version) {
                String baseText = d.bodyAt(base);
                if (baseText == null) throw Problem.conflict("Your copy is too old to combine. Copy your text, reload the document and paste your changes again.");
                if (!d.body.equals(mine)) {
                    with = store.userName(d.updatedBy);
                    TextDiff.Merge m = TextDiff.merge3(TextDiff.lines(baseText), mineLines, TextDiff.lines(d.body),
                            with + "'s version (v" + d.version + ")");
                    if (!m.clean())
                        throw Problem.conflict(with + " saved this document while you were editing, and you both changed the same lines. Choose what to keep.")
                                .with(Json.obj("merged", TextDiff.join(m.lines()), "conflicts", m.parts(), "version", d.version, "by", with));
                    finalText = TextDiff.join(m.lines());
                    merged = true;
                }
            }
            if (finalText.equals(d.body) && title.equals(d.title)) return new Saved(d, merged, with);
            d.history.add(Json.obj("v", d.version, "by", d.updatedBy, "at", d.updatedAt, "body", d.body, "title", d.title, "note", d.note));
            while (d.history.size() > Doc.KEEP_VERSIONS) d.history.remove(0);
            int[] st = TextDiff.stats(TextDiff.lines(d.body), TextDiff.lines(finalText));
            d.body = finalText;
            d.title = title;
            d.version++;
            d.updatedAt = Clock.millis();
            d.updatedBy = u.id;
            d.note = ((Text.blank(note) ? "" : note) + (merged ? " (combined with " + with + "'s changes)" : "")).trim();
            store.log(u.id, "doc.save", d.title, "v" + d.version + " +" + st[0] + " −" + st[1] + (merged ? " merged" : ""));
            store.save("docs", "audit");
            events.publish("doc", null, null, d.projectId, Json.obj("id", d.id, "version", d.version, "by", u.id));
            return new Saved(d, merged, with);
        }
    }

    /** Make an old version the newest one again (nothing is lost: the current text stays in the history). */
    public Saved restore(User u, String id, int v) {
        String old;
        int current;
        synchronized (store) {
            Doc d = doc(u, id);
            old = d.bodyAt(v);
            if (old == null) throw Problem.notFound("Version");
            current = d.version;
        }
        return save(u, id, Json.obj("baseVersion", current, "body", old, "note", "restored version " + v));
    }

    /** "I am editing this document" (sent every 20 seconds by the open editor). Returns the OTHER people editing. */
    public List<String> editing(User u, String id, boolean stop) {
        synchronized (store) { doc(u, id); }
        Map<String, Long> m = editing.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        long now = Clock.millis();
        if (stop) m.remove(u.id); else m.put(u.id, now);
        return editors(id, u.id);
    }

    public List<String> editors(String docId, String except) {
        List<String> out = new ArrayList<>();
        long now = Clock.millis();
        Map<String, Long> m = editing.get(docId);
        if (m == null) return out;
        m.entrySet().removeIf(e -> now - e.getValue() > EDITING_MS);
        for (String who : m.keySet()) if (!who.equals(except)) out.add(who);
        return out;
    }

    Doc doc(User u, String id) {
        Doc d = store.docs.get(id);
        if (d == null || !Access.seeProject(u, store.projects.get(d.projectId))) throw Problem.notFound("Document");
        return d;
    }

    private static String body(String s) {
        String t = s == null ? "" : s.replace("\r\n", "\n");
        if (t.length() > MAX_CHARS) throw Problem.bad("A document can have at most " + MAX_CHARS + " characters.");
        return t;
    }
}
