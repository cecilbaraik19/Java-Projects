package com.cecil.bijak.model;

import com.cecil.bijak.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A repeating invoice ("bill Adityapur Auto Ltd. Rs. 42,000 for AMC on the 1st of every month").
 * The background job makes the invoice on nextDate, issues it if autoIssue is on, and moves nextDate on.
 * anchorDay remembers the wanted day: "31st" becomes 28/29 Feb and then 31 Mar again (not 28 Mar).
 */
public class Recurring implements Json.Writable {
    public enum Every { WEEKLY, MONTHLY, QUARTERLY, YEARLY }

    public String id, name, customerId, startDate, endDate, nextDate, lastInvoiceId, createdBy, poNumber, notes;
    public Every every = Every.MONTHLY;
    public int anchorDay = 1;           // day of month 1-31 (for WEEKLY: 1 = Monday ... 7 = Sunday)
    public boolean autoIssue = true, active = true;
    public int generated;
    public long createdAt;
    public List<Line> lines = new ArrayList<>();

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "name", name, "customerId", customerId, "every", every, "anchorDay", anchorDay,
                "startDate", startDate, "endDate", endDate, "nextDate", nextDate, "autoIssue", autoIssue, "active", active,
                "generated", generated, "lastInvoiceId", lastInvoiceId, "lines", lines, "createdBy", createdBy,
                "createdAt", createdAt, "poNumber", poNumber, "notes", notes);
    }

    public static Recurring fromJson(Map<String, Object> m) {
        Recurring r = new Recurring();
        r.id = Json.str(m, "id");
        r.name = Json.str(m, "name");
        r.customerId = Json.str(m, "customerId");
        try { r.every = Every.valueOf(Json.str(m, "every", "MONTHLY")); } catch (IllegalArgumentException e) { r.every = Every.MONTHLY; }
        r.anchorDay = (int) Json.num(m, "anchorDay", 1);
        r.startDate = Json.str(m, "startDate");
        r.endDate = Json.str(m, "endDate");
        r.nextDate = Json.str(m, "nextDate");
        r.autoIssue = Json.bool(m, "autoIssue", true);
        r.active = Json.bool(m, "active", true);
        r.generated = (int) Json.num(m, "generated", 0);
        r.lastInvoiceId = Json.str(m, "lastInvoiceId");
        r.lines = Line.list(Json.list(m, "lines"));
        r.createdBy = Json.str(m, "createdBy");
        r.createdAt = Json.num(m, "createdAt", 0);
        r.poNumber = Json.str(m, "poNumber");
        r.notes = Json.str(m, "notes");
        return r;
    }
}
