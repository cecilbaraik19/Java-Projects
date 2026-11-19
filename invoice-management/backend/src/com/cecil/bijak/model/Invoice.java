package com.cecil.bijak.model;

import com.cecil.bijak.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A tax invoice or a credit note (kind = "CN").
 *
 * While it is a DRAFT anything may change and it has no number. When it is ISSUED it gets the next number of its
 * series (gap-free), the buyer's details are copied in (so a later change of address does not change an old bill),
 * and a SHA-256 fingerprint of the content is kept. After that the content never changes: a mistake is corrected
 * with a credit note, as the GST rules want.
 */
public class Invoice implements Json.Writable {
    public static final String INVOICE = "INV", CREDIT_NOTE = "CN";

    public String id, kind = INVOICE, number, customerId;
    // the buyer as printed (copied at issue)
    public String buyerName, buyerGstin, buyerAddress, shipTo;
    public String placeOfSupply;              // state code, e.g. "20"
    public String issueDate, dueDate;         // yyyy-mm-dd
    public List<Line> lines = new ArrayList<>();
    public long taxable, cgst, sgst, igst, roundOff, total;
    /** Settled by payments (incl. TDS) and by credit notes. Kept up to date by the services. */
    public long paid, credited;
    public Status status = Status.DRAFT;
    public String poNumber, notes;
    public String createdBy, issuedBy, approvedBy, cancelledBy;
    public long createdAt, issuedAt, cancelledAt;
    public String fingerprint, cancelReason, overrideNote, sentBackNote;
    public String recurringId;
    /** Credit note only: which invoice it corrects, why, and where its value went. */
    public String againstId, reason;
    public List<Alloc> applied = new ArrayList<>();
    /** Reminders: the highest level already sent (0 = none) and when. */
    public int reminderLevel;
    public long lastReminderAt;
    /** true = customer disputes it: no reminders until it is cleared. */
    public boolean onHold;
    public String holdNote;

    public boolean creditNote() { return CREDIT_NOTE.equals(kind); }

    /** Still to be paid (for an invoice) or still to be used (for a credit note). */
    public long balance() {
        if (!status.booked()) return 0;
        if (creditNote()) return total - Alloc.sum(applied);
        return total - paid - credited;
    }

    public long tax() { return cgst + sgst + igst; }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "kind", kind, "number", number, "customerId", customerId,
                "buyerName", buyerName, "buyerGstin", buyerGstin, "buyerAddress", buyerAddress, "shipTo", shipTo,
                "placeOfSupply", placeOfSupply, "issueDate", issueDate, "dueDate", dueDate, "lines", lines,
                "taxable", taxable, "cgst", cgst, "sgst", sgst, "igst", igst, "roundOff", roundOff, "total", total,
                "paid", paid, "credited", credited, "status", status, "poNumber", poNumber, "notes", notes,
                "createdBy", createdBy, "issuedBy", issuedBy, "approvedBy", approvedBy, "cancelledBy", cancelledBy,
                "createdAt", createdAt, "issuedAt", issuedAt, "cancelledAt", cancelledAt, "fingerprint", fingerprint,
                "cancelReason", cancelReason, "overrideNote", overrideNote, "sentBackNote", sentBackNote,
                "recurringId", recurringId, "againstId", againstId, "reason", reason, "applied", applied,
                "reminderLevel", reminderLevel, "lastReminderAt", lastReminderAt, "onHold", onHold, "holdNote", holdNote);
    }

    public static Invoice fromJson(Map<String, Object> m) {
        Invoice i = new Invoice();
        i.id = Json.str(m, "id");
        i.kind = Json.str(m, "kind", INVOICE);
        i.number = Json.str(m, "number");
        i.customerId = Json.str(m, "customerId");
        i.buyerName = Json.str(m, "buyerName");
        i.buyerGstin = Json.str(m, "buyerGstin");
        i.buyerAddress = Json.str(m, "buyerAddress");
        i.shipTo = Json.str(m, "shipTo");
        i.placeOfSupply = Json.str(m, "placeOfSupply");
        i.issueDate = Json.str(m, "issueDate");
        i.dueDate = Json.str(m, "dueDate");
        i.lines = Line.list(Json.list(m, "lines"));
        i.taxable = Json.num(m, "taxable", 0);
        i.cgst = Json.num(m, "cgst", 0);
        i.sgst = Json.num(m, "sgst", 0);
        i.igst = Json.num(m, "igst", 0);
        i.roundOff = Json.num(m, "roundOff", 0);
        i.total = Json.num(m, "total", 0);
        i.paid = Json.num(m, "paid", 0);
        i.credited = Json.num(m, "credited", 0);
        i.status = Status.of(Json.str(m, "status"));
        i.poNumber = Json.str(m, "poNumber");
        i.notes = Json.str(m, "notes");
        i.createdBy = Json.str(m, "createdBy");
        i.issuedBy = Json.str(m, "issuedBy");
        i.approvedBy = Json.str(m, "approvedBy");
        i.cancelledBy = Json.str(m, "cancelledBy");
        i.createdAt = Json.num(m, "createdAt", 0);
        i.issuedAt = Json.num(m, "issuedAt", 0);
        i.cancelledAt = Json.num(m, "cancelledAt", 0);
        i.fingerprint = Json.str(m, "fingerprint");
        i.cancelReason = Json.str(m, "cancelReason");
        i.overrideNote = Json.str(m, "overrideNote");
        i.sentBackNote = Json.str(m, "sentBackNote");
        i.recurringId = Json.str(m, "recurringId");
        i.againstId = Json.str(m, "againstId");
        i.reason = Json.str(m, "reason");
        i.applied = Alloc.list(Json.list(m, "applied"));
        i.reminderLevel = (int) Json.num(m, "reminderLevel", 0);
        i.lastReminderAt = Json.num(m, "lastReminderAt", 0);
        i.onHold = Json.bool(m, "onHold", false);
        i.holdNote = Json.str(m, "holdNote");
        return i;
    }
}
