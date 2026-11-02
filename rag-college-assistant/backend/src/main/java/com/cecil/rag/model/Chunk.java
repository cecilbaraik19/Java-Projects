package com.cecil.rag.model;

import com.cecil.rag.util.Json;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A small piece of a document (about 100 words). The search looks at chunks, not whole documents,
 * so the answer can point to the exact paragraph.
 */
public class Chunk {
    public String id;           // e.g. "d4#3" = 3rd piece of document d4
    public Doc doc;
    public int ordinal;
    public String section;      // heading path, e.g. "Hostel Rules > Mess timings"
    public String text;
    public List<String> tokens; // words used for search (stemmed)
    public Set<String> tokenSet;
    public double[] vector;     // meaning vector from LSA
    public boolean flagged;     // looks like a prompt-injection attempt; kept out of answers
    public String flagReason;

    public Map<String, Object> toJson() {
        return Json.map("id", id, "docId", doc.id, "docTitle", doc.title, "ordinal", ordinal,
                "section", section, "text", text, "words", tokens.size(), "flagged", flagged,
                "flagReason", flagReason);
    }
}
