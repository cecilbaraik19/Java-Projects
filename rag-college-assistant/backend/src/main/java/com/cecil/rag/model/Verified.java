package com.cecil.rag.model;

import com.cecil.rag.util.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A "verified answer": a question + answer checked by a faculty member.
 * When a new question is almost the same, this answer is shown first.
 */
public class Verified implements Json.JsonAware {
    public String id;
    public String question;
    public String answer;
    public List<String> docIds = new ArrayList<>();
    public String byId;
    public String byName;
    public long at;
    public int uses;

    @Override
    public Map<String, Object> toJson() {
        return Json.map("id", id, "question", question, "answer", answer, "docIds", docIds, "byId", byId,
                "byName", byName, "at", at, "uses", uses);
    }

    public static Verified from(Map<String, Object> m) {
        Verified v = new Verified();
        v.id = Json.str(m, "id", null);
        v.question = Json.str(m, "question", "");
        v.answer = Json.str(m, "answer", "");
        for (Object o : Json.list(m, "docIds")) v.docIds.add(String.valueOf(o));
        v.byId = Json.str(m, "byId", "");
        v.byName = Json.str(m, "byName", "");
        v.at = Json.num(m, "at", 0);
        v.uses = (int) Json.num(m, "uses", 0);
        return v;
    }
}
