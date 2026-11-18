package com.cecil.swikriti.model;

import com.cecil.swikriti.util.Json;

import java.util.Map;

/**
 * A frozen copy of the document as it was sent for approval. Every submit makes a new revision: A, B, C ...
 * Approvers always sign a revision (its hash), never a text that can still change.
 */
public class Revision implements Json.Writable {
    public int no;              // 1, 2, 3 ...
    public long at;
    public String title;
    public long amount;
    public String vendor;
    public boolean urgent;
    public String needBy;       // yyyy-mm-dd or null
    public String body;
    public Attachment attachment;
    public String note;         // what the author changed (from revision B on)
    public String hash;         // SHA-256 of all the content above

    /** 1 -> A, 2 -> B ... 26 -> Z, 27 -> AA. */
    public static String letter(int no) {
        StringBuilder sb = new StringBuilder();
        for (int n = no; n > 0; n = (n - 1) / 26) sb.insert(0, (char) ('A' + (n - 1) % 26));
        return sb.toString();
    }

    public String letter() { return letter(no); }

    @Override public Map<String, Object> toJson() {
        return Json.obj("no", no, "letter", letter(), "at", at, "title", title, "amount", amount, "vendor", vendor,
                "urgent", urgent, "needBy", needBy, "body", body, "attachment", attachment, "note", note, "hash", hash);
    }

    public static Revision fromJson(Map<String, Object> m) {
        Revision r = new Revision();
        r.no = (int) Json.num(m, "no", 1);
        r.at = Json.num(m, "at", 0);
        r.title = Json.str(m, "title");
        r.amount = Json.num(m, "amount", 0);
        r.vendor = Json.str(m, "vendor");
        r.urgent = Json.bool(m, "urgent", false);
        r.needBy = Json.str(m, "needBy");
        r.body = Json.str(m, "body", "");
        r.attachment = Attachment.fromJson(m.get("attachment"));
        r.note = Json.str(m, "note");
        r.hash = Json.str(m, "hash");
        return r;
    }
}
