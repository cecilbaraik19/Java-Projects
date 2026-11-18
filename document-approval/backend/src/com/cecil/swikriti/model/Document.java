package com.cecil.swikriti.model;

import com.cecil.swikriti.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A document that needs approval: a purchase requisition, a contract, a drawing, a policy ...
 * The fields at the top are the WORKING COPY (what the author is editing). Submitting freezes them into a Revision.
 */
public class Document implements Json.Writable {
    public String id;
    public String number;          // e.g. PR/26-27/0042 - given on the first submit
    public String type;            // template code
    public String authorId;
    public String dept;
    public DocStatus status = DocStatus.DRAFT;
    public long createdAt;
    public long updatedAt;
    public long submittedAt;       // first submit
    public long closedAt;          // approved / rejected / withdrawn
    public List<String> cc = new ArrayList<>();   // people who may read it and get told the result

    // working copy
    public String title;
    public long amount;
    public String vendor;
    public boolean urgent;
    public String needBy;
    public String body = "";
    public Attachment attachment;

    public List<Revision> revisions = new ArrayList<>();
    public List<RouteStage> route = new ArrayList<>();   // for the latest revision
    public int stageIndex = -1;                          // the ACTIVE stage, -1 = none
    public int templateVersion;
    public String certificate;     // verification code once approved

    public Revision latest() { return revisions.isEmpty() ? null : revisions.get(revisions.size() - 1); }
    public int rev() { return revisions.size(); }

    @Override public Map<String, Object> toJson() {
        return Json.obj("id", id, "number", number, "type", type, "authorId", authorId, "dept", dept, "status", status,
                "createdAt", createdAt, "updatedAt", updatedAt, "submittedAt", submittedAt, "closedAt", closedAt, "cc", cc,
                "title", title, "amount", amount, "vendor", vendor, "urgent", urgent, "needBy", needBy, "body", body,
                "attachment", attachment, "revisions", revisions, "route", route, "stageIndex", stageIndex,
                "templateVersion", templateVersion, "certificate", certificate);
    }

    public static Document fromJson(Map<String, Object> m) {
        Document d = new Document();
        d.id = Json.str(m, "id");
        d.number = Json.str(m, "number");
        d.type = Json.str(m, "type");
        d.authorId = Json.str(m, "authorId");
        d.dept = Json.str(m, "dept");
        d.status = DocStatus.of(Json.str(m, "status"));
        d.createdAt = Json.num(m, "createdAt", 0);
        d.updatedAt = Json.num(m, "updatedAt", 0);
        d.submittedAt = Json.num(m, "submittedAt", 0);
        d.closedAt = Json.num(m, "closedAt", 0);
        d.cc = Json.strings(m, "cc");
        d.title = Json.str(m, "title");
        d.amount = Json.num(m, "amount", 0);
        d.vendor = Json.str(m, "vendor");
        d.urgent = Json.bool(m, "urgent", false);
        d.needBy = Json.str(m, "needBy");
        d.body = Json.str(m, "body", "");
        d.attachment = Attachment.fromJson(m.get("attachment"));
        for (Object o : Json.list(m, "revisions")) if (o instanceof Map<?, ?> x) d.revisions.add(Revision.fromJson(Template.cast(x)));
        for (Object o : Json.list(m, "route")) if (o instanceof Map<?, ?> x) d.route.add(RouteStage.fromJson(Template.cast(x)));
        d.stageIndex = (int) Json.num(m, "stageIndex", -1);
        d.templateVersion = (int) Json.num(m, "templateVersion", 0);
        d.certificate = Json.str(m, "certificate");
        return d;
    }
}
