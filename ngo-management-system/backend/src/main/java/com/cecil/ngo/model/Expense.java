package com.cecil.ngo.model;

import com.cecil.ngo.model.Enums.ExpenseCategory;
import com.cecil.ngo.model.Enums.Review;
import com.cecil.ngo.util.Json;

import java.util.Map;

/** Money spent for a program. Someone submits it, and a different person (finance) approves it. */
public class Expense implements Json.JsonAble {
    public String id;
    public String programId;
    public ExpenseCategory category;
    public long amount;
    public String date;
    public String vendor;
    public String description;
    public Review status = Review.PENDING;
    public String submittedBy;
    public String reviewedBy;
    public String reviewNote;
    public String submittedAt;

    @Override
    public Object toJson() {
        return Json.map("id", id, "programId", programId, "category", category, "amount", amount, "date", date,
                "vendor", vendor, "description", description, "status", status, "submittedBy", submittedBy,
                "reviewedBy", reviewedBy, "reviewNote", reviewNote, "submittedAt", submittedAt);
    }

    public static Expense fromJson(Map<String, Object> m) {
        Expense e = new Expense();
        e.id = Json.str(m, "id");
        e.programId = Json.str(m, "programId");
        e.category = ExpenseCategory.valueOf(Json.str(m, "category", "OTHER"));
        Long a = Json.longOrNull(m, "amount");
        e.amount = a == null ? 0 : a;
        e.date = Json.str(m, "date");
        e.vendor = Json.str(m, "vendor", "");
        e.description = Json.str(m, "description", "");
        e.status = Review.valueOf(Json.str(m, "status", "PENDING"));
        e.submittedBy = Json.str(m, "submittedBy");
        e.reviewedBy = Json.str(m, "reviewedBy");
        e.reviewNote = Json.str(m, "reviewNote", "");
        e.submittedAt = Json.str(m, "submittedAt");
        return e;
    }
}
