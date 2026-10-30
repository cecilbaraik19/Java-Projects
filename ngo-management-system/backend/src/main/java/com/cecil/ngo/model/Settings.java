package com.cecil.ngo.model;

import com.cecil.ngo.util.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/** Details about the NGO itself, printed on receipts and reports. */
public class Settings implements Json.JsonAble {
    public String orgName = "Asha Kiran Foundation";
    public String regNo = "JH/2014/0217";
    public String address = "Lalpur, Ranchi, Jharkhand 834001";
    public String email = "hello@ashakiran.example";
    /** Line printed on every receipt (for example the 80G tax note). */
    public String receiptNote = "Donations to this organisation are eligible for deduction under Section 80G of the Income Tax Act (demo text).";

    /** Last receipt number used in each financial year ("2026-27" -> 25). Kept so a deleted receipt's number is never given again. */
    public Map<String, Integer> lastReceipt = new LinkedHashMap<>();

    @Override
    public Object toJson() {
        return Json.map("orgName", orgName, "regNo", regNo, "address", address, "email", email, "receiptNote", receiptNote,
                "lastReceipt", lastReceipt);
    }

    public static Settings fromJson(Map<String, Object> m) {
        Settings s = new Settings();
        s.orgName = Json.str(m, "orgName", s.orgName);
        s.regNo = Json.str(m, "regNo", s.regNo);
        s.address = Json.str(m, "address", s.address);
        s.email = Json.str(m, "email", s.email);
        s.receiptNote = Json.str(m, "receiptNote", s.receiptNote);
        Map<String, Object> lr = Json.obj(m, "lastReceipt");
        if (lr != null) for (Map.Entry<String, Object> e : lr.entrySet())
            if (e.getValue() instanceof Number n) s.lastReceipt.put(e.getKey(), n.intValue());
        return s;
    }
}
