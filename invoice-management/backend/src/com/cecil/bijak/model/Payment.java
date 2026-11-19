package com.cecil.bijak.model;

import com.cecil.bijak.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Money received from a customer. amount = what reached the bank; tds = income tax the customer kept back
 * (it still counts as paid - the customer pays it to the government for us). Together they settle invoices.
 * What is not given to an invoice stays as an ADVANCE and is used automatically for the next invoice.
 */
public class Payment implements Json.Writable {
    public static final List<String> MODES = List.of("UPI", "NEFT", "RTGS", "IMPS", "CHEQUE", "CASH");

    public String id, number, customerId, date, mode, reference, notes, createdBy, bankLineId, voidReason, voidedBy;
    public long amount, tds, createdAt, voidedAt;
    public boolean voided;
    public List<Alloc> allocations = new ArrayList<>();

    public long value() { return voided ? 0 : amount + tds; }

    public long unapplied() { return voided ? 0 : amount + tds - Alloc.sum(allocations); }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "number", number, "customerId", customerId, "date", date, "mode", mode,
                "reference", reference, "notes", notes, "amount", amount, "tds", tds, "allocations", allocations,
                "createdBy", createdBy, "createdAt", createdAt, "bankLineId", bankLineId, "voided", voided,
                "voidReason", voidReason, "voidedBy", voidedBy, "voidedAt", voidedAt);
    }

    public static Payment fromJson(Map<String, Object> m) {
        Payment p = new Payment();
        p.id = Json.str(m, "id");
        p.number = Json.str(m, "number");
        p.customerId = Json.str(m, "customerId");
        p.date = Json.str(m, "date");
        p.mode = Json.str(m, "mode");
        p.reference = Json.str(m, "reference");
        p.notes = Json.str(m, "notes");
        p.amount = Json.num(m, "amount", 0);
        p.tds = Json.num(m, "tds", 0);
        p.allocations = Alloc.list(Json.list(m, "allocations"));
        p.createdBy = Json.str(m, "createdBy");
        p.createdAt = Json.num(m, "createdAt", 0);
        p.bankLineId = Json.str(m, "bankLineId");
        p.voided = Json.bool(m, "voided", false);
        p.voidReason = Json.str(m, "voidReason");
        p.voidedBy = Json.str(m, "voidedBy");
        p.voidedAt = Json.num(m, "voidedAt", 0);
        return p;
    }
}
