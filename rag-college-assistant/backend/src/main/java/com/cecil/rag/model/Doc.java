package com.cecil.rag.model;

import com.cecil.rag.util.Json;
import com.cecil.rag.util.Text;
import java.util.Map;

/** One college document in the knowledge base (handbook, fee notice, hostel rules ...). */
public class Doc implements Json.JsonAware {
    public static final String[] CATEGORIES = {
            "Academics", "Examinations", "Fees & Scholarships", "Hostel & Campus Life", "Library",
            "Placements", "Rules & Conduct", "Courses & Syllabus", "Offices & Contacts", "Calendar"};

    public String id;
    public String title;
    public String category = "Academics";
    public String department = "All departments";
    public String audience = "all";        // all | students | faculty
    public String status = "published";    // published | draft | archived
    public String effectiveDate = "";      // yyyy-mm-dd, the date the rule starts
    public String reviewDate = "";         // yyyy-mm-dd, when someone should check it again
    public int version = 1;
    public String fileName = "";
    public String fileType = "md";
    public String uploadedBy = "";
    public long uploadedAt;
    public String updatedBy = "";
    public long updatedAt;
    public String text = "";

    // filled in by the indexer (not saved)
    public transient int chunkCount;
    public transient int flaggedChunks;

    public boolean searchable() { return "published".equals(status); }

    @Override
    public Map<String, Object> toJson() {
        return Json.map("id", id, "title", title, "category", category, "department", department,
                "audience", audience, "status", status, "effectiveDate", effectiveDate, "reviewDate", reviewDate,
                "version", version, "fileName", fileName, "fileType", fileType, "uploadedBy", uploadedBy,
                "uploadedAt", uploadedAt, "updatedBy", updatedBy, "updatedAt", updatedAt, "text", text);
    }

    /** Short form for lists (no full text). */
    public Map<String, Object> summary() {
        Map<String, Object> m = toJson();
        m.remove("text");
        m.put("words", Text.wordCount(text));
        m.put("chunks", chunkCount);
        m.put("flagged", flaggedChunks);
        m.put("preview", preview(220));
        return m;
    }

    /** Plain-text start of the document: no headings, no table lines, title not repeated. */
    public String preview(int max) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("#") || l.startsWith("|")) continue;
            sb.append(l).append(' ');
            if (sb.length() > max * 2) break;
        }
        return Text.clip(sb.toString(), max);
    }

    public static Doc from(Map<String, Object> m) {
        Doc d = new Doc();
        d.id = Json.str(m, "id", null);
        d.title = Json.str(m, "title", "");
        d.category = Json.str(m, "category", "Academics");
        d.department = Json.str(m, "department", "All departments");
        d.audience = Json.str(m, "audience", "all");
        d.status = Json.str(m, "status", "published");
        d.effectiveDate = Json.str(m, "effectiveDate", "");
        d.reviewDate = Json.str(m, "reviewDate", "");
        d.version = (int) Json.num(m, "version", 1);
        d.fileName = Json.str(m, "fileName", "");
        d.fileType = Json.str(m, "fileType", "md");
        d.uploadedBy = Json.str(m, "uploadedBy", "");
        d.uploadedAt = Json.num(m, "uploadedAt", 0);
        d.updatedBy = Json.str(m, "updatedBy", "");
        d.updatedAt = Json.num(m, "updatedAt", 0);
        d.text = Json.str(m, "text", "");
        return d;
    }
}
