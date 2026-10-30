package com.cecil.ngo.model;

import com.cecil.ngo.model.Enums.PayMode;
import com.cecil.ngo.util.Json;

import java.util.Map;

/** Money received from a donor. If programId is empty, it goes to the general fund. */
public class Donation implements Json.JsonAble {
    public String id;
    /** Receipt number like RCPT/2026-27/0004 (numbering restarts each financial year). */
    public String receiptNo;
    public String donorId;
    public long amount;
    public String date;
    public PayMode mode;
    public String programId;
    /** UTR, cheque number or similar. */
    public String reference;
    public String note;
    public String recordedBy;
    public String recordedAt;

    @Override
    public Object toJson() {
        return Json.map("id", id, "receiptNo", receiptNo, "donorId", donorId, "amount", amount, "date", date, "mode", mode,
                "programId", programId, "reference", reference, "note", note, "recordedBy", recordedBy, "recordedAt", recordedAt);
    }

    public static Donation fromJson(Map<String, Object> m) {
        Donation d = new Donation();
        d.id = Json.str(m, "id");
        d.receiptNo = Json.str(m, "receiptNo");
        d.donorId = Json.str(m, "donorId");
        Long a = Json.longOrNull(m, "amount");
        d.amount = a == null ? 0 : a;
        d.date = Json.str(m, "date");
        d.mode = PayMode.valueOf(Json.str(m, "mode", "UPI"));
        String p = Json.str(m, "programId");
        d.programId = p == null || p.isBlank() ? null : p;
        d.reference = Json.str(m, "reference", "");
        d.note = Json.str(m, "note", "");
        d.recordedBy = Json.str(m, "recordedBy");
        d.recordedAt = Json.str(m, "recordedAt");
        return d;
    }
}
