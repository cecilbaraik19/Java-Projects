package com.cecil.dainandini.service;

import com.cecil.dainandini.ai.NoteAnalyzer;
import com.cecil.dainandini.model.MeetingNote;
import com.cecil.dainandini.model.Task;
import com.cecil.dainandini.model.User;
import com.cecil.dainandini.store.DataStore;
import com.cecil.dainandini.util.Clock;
import com.cecil.dainandini.util.Json;
import com.cecil.dainandini.util.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Meeting notes: paste rough notes, the analyzer finds the summary, decisions, open questions and action items.
 * Each action item becomes a task with one click – in the list of the person it names
 * (an action item from a meeting is the one case where a member may put a task in a colleague's list).
 */
public class NoteService {
    public static final int MAX_TEXT = 20_000;

    private final DataStore store;
    private final TaskService tasks;

    public NoteService(DataStore store, TaskService tasks) { this.store = store; this.tasks = tasks; }

    /** Looks at notes without saving them. */
    public Map<String, Object> analyze(User u, String text, long at) {
        if (text == null || text.length() > MAX_TEXT) throw Problem.bad("Notes must be under " + MAX_TEXT + " characters.");
        NoteAnalyzer.Result r = NoteAnalyzer.analyze(text, u, at, store::userByMention);
        return Json.obj("summary", r.summary, "decisions", r.decisions, "questions", r.questions, "actions", r.actions,
                "sentences", NoteAnalyzer.sentences(text).size());
    }

    public MeetingNote save(User u, Map<String, Object> b) {
        String text = Json.str(b, "text", "");
        if (Text.blank(text)) throw Problem.bad("Paste or type the meeting notes first.");
        if (text.length() > MAX_TEXT) throw Problem.bad("Notes must be under " + MAX_TEXT + " characters.");
        synchronized (store) {
            MeetingNote n = new MeetingNote();
            n.id = store.nextId("M", 1);
            n.ownerId = u.id;
            n.title = Text.clean(Json.str(b, "title", ""), 100);
            if (Text.blank(n.title)) n.title = "Meeting notes";
            n.at = Json.num(b, "at", 0) > 0 ? Json.num(b, "at", 0) : Clock.millis();
            n.text = text.trim();
            NoteAnalyzer.Result r = NoteAnalyzer.analyze(n.text, u, n.at, store::userByMention);
            n.summary.addAll(r.summary);
            n.decisions.addAll(r.decisions);
            n.questions.addAll(r.questions);
            n.actions.addAll(r.actions);
            store.notes.put(n.id, n);
            store.log(u.id, "note.create", n.id, n.title);
            store.save("notes", "audit");
            return n;
        }
    }

    public List<Object> list(User u) {
        synchronized (store) {
            List<MeetingNote> mine = new ArrayList<>();
            for (MeetingNote n : store.notes.values()) if (n.visibleTo(u.id)) mine.add(n);
            mine.sort((a, b) -> Long.compare(b.at, a.at));
            List<Object> out = new ArrayList<>();
            for (MeetingNote n : mine) {
                int open = 0;
                for (Map<String, Object> a : n.actions) if (a.get("taskId") == null) open++;
                out.add(Json.obj("id", n.id, "title", n.title, "at", n.at, "ownerName", store.userName(n.ownerId),
                        "actions", n.actions.size(), "notTasks", open, "decisions", n.decisions.size(), "mine", n.ownerId.equals(u.id)));
            }
            return out;
        }
    }

    public Map<String, Object> view(User u, String id) {
        synchronized (store) {
            MeetingNote n = store.notes.get(id);
            if (n == null || !n.visibleTo(u.id)) throw Problem.notFound("Note");
            Map<String, Object> j = new LinkedHashMap<>(n.toJson());
            List<Object> acts = new ArrayList<>();
            for (Map<String, Object> a : n.actions) {
                Map<String, Object> x = new LinkedHashMap<>(a);
                Task t = a.get("taskId") == null ? null : store.tasks.get(String.valueOf(a.get("taskId")));
                x.put("taskStatus", t == null ? null : t.status);
                acts.add(x);
            }
            j.put("actions", acts);
            j.put("ownerName", store.userName(n.ownerId));
            j.put("mine", n.ownerId.equals(u.id));
            return j;
        }
    }

    /** Turns action item number `index` into a task (for the person it names, or for the note's writer). */
    public Task makeTask(User u, String id, int index, String ownerId) {
        synchronized (store) {
            MeetingNote n = store.notes.get(id);
            if (n == null || !n.visibleTo(u.id)) throw Problem.notFound("Note");
            if (index < 0 || index >= n.actions.size()) throw Problem.notFound("Action item");
            Map<String, Object> a = n.actions.get(index);
            if (a.get("taskId") != null && store.tasks.containsKey(String.valueOf(a.get("taskId"))))
                throw Problem.conflict("This action item is already a task.");
            String owner = !Text.blank(ownerId) ? ownerId : a.get("ownerId") != null ? String.valueOf(a.get("ownerId")) : n.ownerId;
            User to = store.users.get(owner);
            if (to == null || !to.active) throw Problem.bad("Unknown person.");
            // only the note's writer may hand out its actions; anyone named may take their own
            if (!u.id.equals(n.ownerId) && !u.id.equals(to.id)) throw Problem.forbidden("Only the writer of these notes can give this action to someone else.");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("text", a.get("text"));     // quick capture also works out deep / quick work from the verb
            body.put("title", a.get("text"));
            long due = a.get("due") instanceof Number num ? num.longValue() : 0;
            if (due > 0) body.put("due", due);
            Task t = tasks.create(u, body);
            if (!t.ownerId.equals(to.id)) { t.ownerId = to.id; }
            t.sourceNoteId = n.id;
            t.notes = "From the meeting “" + n.title + "”.";
            a.put("taskId", t.id);
            a.put("ownerId", to.id);
            a.put("ownerName", to.name);
            store.save("tasks", "notes");
            return t;
        }
    }

    public void delete(User u, String id) {
        synchronized (store) {
            MeetingNote n = store.notes.get(id);
            if (n == null || !n.visibleTo(u.id)) throw Problem.notFound("Note");
            if (!n.ownerId.equals(u.id)) throw Problem.forbidden("Only the writer can delete these notes.");
            store.notes.remove(id);
            store.log(u.id, "note.delete", id, n.title);
            store.save("notes", "audit");
        }
    }
}
