package com.cecil.swikriti.model;

import com.cecil.swikriti.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A document type and its workflow (its "route"), e.g. PR = Purchase Requisition:
 *   Line manager -> Procurement -> Finance (if amount > 50000) -> 2 of 3 Directors (if amount > 500000)
 *
 * When a document is submitted, the stages are COPIED into the document. So changing a template later never changes
 * documents that are already on their way (they finish with the route they started with).
 */
public class Template implements Json.Writable {
    public String code;                 // PR, EXP, TRV, CON, POL, DRG - also the start of the document number
    public String name;
    public String description;
    public boolean hasAmount;           // show the amount field (Rs.)
    public boolean publishOnApproval;   // approved documents go to the library that everybody can read (policies)
    public boolean distinct;            // one person may sign only ONE stage of a document
    public boolean active = true;
    public int version = 1;             // goes up on every change
    public List<Stage> stages = new ArrayList<>();

    @Override public Map<String, Object> toJson() {
        return Json.obj("code", code, "name", name, "description", description, "hasAmount", hasAmount,
                "publishOnApproval", publishOnApproval, "distinct", distinct, "active", active, "version", version,
                "stages", stages);
    }

    public static Template fromJson(Map<String, Object> m) {
        Template t = new Template();
        t.code = Json.str(m, "code");
        t.name = Json.str(m, "name");
        t.description = Json.str(m, "description", "");
        t.hasAmount = Json.bool(m, "hasAmount", false);
        t.publishOnApproval = Json.bool(m, "publishOnApproval", false);
        t.distinct = Json.bool(m, "distinct", false);
        t.active = Json.bool(m, "active", true);
        t.version = (int) Json.num(m, "version", 1);
        for (Object o : Json.list(m, "stages")) if (o instanceof Map<?, ?> sm) t.stages.add(Stage.fromJson(cast(sm)));
        return t;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> cast(Map<?, ?> m) { return (Map<String, Object>) m; }
}
