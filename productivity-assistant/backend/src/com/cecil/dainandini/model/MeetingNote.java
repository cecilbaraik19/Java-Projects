package com.cecil.dainandini.model;

import com.cecil.dainandini.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Notes from a meeting, plus what the assistant found in them: summary, decisions, open questions, action items. */
public class MeetingNote implements Json.Writable {
    public String id;
    public String ownerId;
    public String title;
    public long at;
    public String text = "";
    public List<String> summary = new ArrayList<>();
    public List<String> decisions = new ArrayList<>();
    public List<String> questions = new ArrayList<>();
    /** Each action: text, ownerId (may be null), ownerName, due (ms, 0 = none), taskId (after it became a task). */
    public List<Map<String, Object>> actions = new ArrayList<>();

    /** The owner and everybody who got an action item can open the note. */
    public boolean visibleTo(String userId) {
        if (userId.equals(ownerId)) return true;
        for (Map<String, Object> a : actions) if (userId.equals(a.get("ownerId"))) return true;
        return false;
    }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "ownerId", ownerId, "title", title, "at", at, "text", text, "summary", summary,
                "decisions", decisions, "questions", questions, "actions", actions);
    }

    public static MeetingNote fromJson(Map<String, Object> m) {
        MeetingNote n = new MeetingNote();
        n.id = Json.str(m, "id");
        n.ownerId = Json.str(m, "ownerId");
        n.title = Json.str(m, "title", "");
        n.at = Json.num(m, "at", 0);
        n.text = Json.str(m, "text", "");
        n.summary = new ArrayList<>(Json.strings(m, "summary"));
        n.decisions = new ArrayList<>(Json.strings(m, "decisions"));
        n.questions = new ArrayList<>(Json.strings(m, "questions"));
        for (Object o : Json.list(m, "actions"))
            if (o instanceof Map<?, ?> a) {
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : a.entrySet()) copy.put(String.valueOf(e.getKey()), e.getValue());
                n.actions.add(copy);
            }
        return n;
    }
}
