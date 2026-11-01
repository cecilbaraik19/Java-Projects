package com.cecil.invoice.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An uploaded invoice and everything the extractor read from it.
 * The field names used in {@link #fields} are listed in {@link #FIELDS}.
 */
public class Invoice {
    /** Every header field the extractor looks for, in the order shown on screen. */
    public static final String[] FIELDS = {
            "vendorName", "vendorGstin", "invoiceNo", "invoiceDate", "dueDate", "poNumber",
            "buyerName", "buyerGstin", "currency", "subtotal", "cgst", "sgst", "igst", "taxTotal", "total",
            "amountInWords", "paymentTerms"
    };

    public String id;
    public String fileName = "";
    public String source = "";          // PASTE, TXT, PDF, IMAGE, SAMPLE
    public String rawText = "";         // the invoice as plain text (what the local extractor reads)
    public String uploadedBy;           // user id
    public String uploadedAt;
    public String engine = "";          // "Local AI" or "Claude (model)"
    public Status status = Status.TO_REVIEW;

    public Map<String, FieldValue> fields = new LinkedHashMap<>();
    public List<LineItem> items = new ArrayList<>();
    public List<Check> checks = new ArrayList<>();

    public String vendorId = "";
    public String category = "";
    public double categoryConfidence;
    public Map<String, Double> categoryScores = new LinkedHashMap<>();
    public boolean categoryCorrected;

    public double confidence;           // overall 0..1
    public String duplicateOf = "";     // id of an earlier invoice that looks the same
    public int extractMillis;
    public String reviewedBy = "";
    public String reviewedAt = "";
    public String note = "";
    public boolean autoApproved;

    public FieldValue f(String name) {
        FieldValue v = fields.get(name);
        return v == null ? new FieldValue() : v;
    }

    public String v(String name) {
        FieldValue fv = fields.get(name);
        return fv == null || fv.value == null ? "" : fv.value;
    }

    public double num(String name) {
        try { return Double.parseDouble(v(name)); } catch (NumberFormatException e) { return 0; }
    }
}
