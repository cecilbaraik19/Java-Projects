package com.cecil.bijak.model;

import com.cecil.bijak.core.Dunning;
import com.cecil.bijak.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The seller company and the rules of the automation. Only the Owner changes these. */
public class Settings implements Json.Writable {
    public String companyName = "My Company", gstin = "", state = "20", address = "", phone = "", email = "";
    public String bankName = "", bankAccount = "", ifsc = "", upiId = "";
    /** Series prefixes: invoices KVP/26-27/0001, credit notes CN/26-27/0001, receipts RCT/26-27/0001. */
    public String invoicePrefix = "INV", creditPrefix = "CN", receiptPrefix = "RCT";
    /** Invoices above this total (paise) made by Sales need an Accountant's or the Owner's approval. */
    public long approvalLimit = 2_00_000_00L;
    /** Days (from the due date) of reminder levels 1 to 5. */
    public List<Integer> reminderDays = new ArrayList<>(Dunning.DEFAULT_DAYS);
    public boolean remindersOn = true;
    /** Interest on late payment, basis points a year (1800 = 18 %); 0 = off. Shown on the final notice and statements. */
    public int interestBp = 1800;
    /** Use a customer's advance (money received but not yet given to an invoice) as soon as a new invoice is issued. */
    public boolean autoApplyAdvance = true;
    public String terms = "Goods once sold will not be taken back. Interest @18% p.a. on payments after the due date. Subject to Jamshedpur jurisdiction.";

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("companyName", companyName, "gstin", gstin, "state", state, "address", address, "phone", phone,
                "email", email, "bankName", bankName, "bankAccount", bankAccount, "ifsc", ifsc, "upiId", upiId,
                "invoicePrefix", invoicePrefix, "creditPrefix", creditPrefix, "receiptPrefix", receiptPrefix,
                "approvalLimit", approvalLimit, "reminderDays", reminderDays, "remindersOn", remindersOn,
                "interestBp", interestBp, "autoApplyAdvance", autoApplyAdvance, "terms", terms);
    }

    public static Settings fromJson(Map<String, Object> m) {
        Settings s = new Settings();
        s.companyName = Json.str(m, "companyName", s.companyName);
        s.gstin = Json.str(m, "gstin", "");
        s.state = Json.str(m, "state", "20");
        s.address = Json.str(m, "address", "");
        s.phone = Json.str(m, "phone", "");
        s.email = Json.str(m, "email", "");
        s.bankName = Json.str(m, "bankName", "");
        s.bankAccount = Json.str(m, "bankAccount", "");
        s.ifsc = Json.str(m, "ifsc", "");
        s.upiId = Json.str(m, "upiId", "");
        s.invoicePrefix = Json.str(m, "invoicePrefix", "INV");
        s.creditPrefix = Json.str(m, "creditPrefix", "CN");
        s.receiptPrefix = Json.str(m, "receiptPrefix", "RCT");
        s.approvalLimit = Json.num(m, "approvalLimit", s.approvalLimit);
        List<Integer> days = new ArrayList<>();
        for (Object o : Json.list(m, "reminderDays")) if (o instanceof Number n) days.add(n.intValue());
        if (days.size() == 5) s.reminderDays = days;
        s.remindersOn = Json.bool(m, "remindersOn", true);
        s.interestBp = (int) Json.num(m, "interestBp", 1800);
        s.autoApplyAdvance = Json.bool(m, "autoApplyAdvance", true);
        s.terms = Json.str(m, "terms", s.terms);
        return s;
    }
}
