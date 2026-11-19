package com.cecil.bijak.model;

import com.cecil.bijak.util.Json;

import java.util.Map;

/** One money-in line from an imported bank statement, waiting to be matched to a customer's invoices. */
public class BankLine implements Json.Writable {
    public enum State { NEW, MATCHED, IGNORED }

    public String id, importId, date, narration, reference, paymentId, ignoredBy;
    public long amount, importedAt;
    public State state = State.NEW;

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "importId", importId, "date", date, "narration", narration, "reference", reference,
                "amount", amount, "state", state, "paymentId", paymentId, "ignoredBy", ignoredBy, "importedAt", importedAt);
    }

    public static BankLine fromJson(Map<String, Object> m) {
        BankLine b = new BankLine();
        b.id = Json.str(m, "id");
        b.importId = Json.str(m, "importId");
        b.date = Json.str(m, "date");
        b.narration = Json.str(m, "narration");
        b.reference = Json.str(m, "reference");
        b.amount = Json.num(m, "amount", 0);
        try { b.state = State.valueOf(Json.str(m, "state", "NEW")); } catch (IllegalArgumentException e) { b.state = State.NEW; }
        b.paymentId = Json.str(m, "paymentId");
        b.ignoredBy = Json.str(m, "ignoredBy");
        b.importedAt = Json.num(m, "importedAt", 0);
        return b;
    }
}
