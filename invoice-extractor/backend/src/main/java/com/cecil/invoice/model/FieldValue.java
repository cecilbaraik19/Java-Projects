package com.cecil.invoice.model;

/**
 * One value the extractor read, for example the invoice number.
 *   value      - the cleaned value ("INV-2041", "2026-10-12", "15340.00")
 *   confidence - 0 to 1, how sure the extractor is
 *   line       - which line of the invoice text it came from (-1 = not found / typed by a person)
 *   label      - the words in front of the value on the invoice ("Bill No.")
 *   method     - how it was found ("label on same line", "known vendor", "Claude" ...)
 *   corrected  - true when a person changed it during review
 */
public class FieldValue {
    public String value = "";
    public double confidence;
    public int line = -1;
    public String label = "";
    public String method = "";
    public boolean corrected;
    public String original = "";   // what the AI read before a person corrected it

    public FieldValue() {}

    public FieldValue(String value, double confidence, int line, String label, String method) {
        this.value = value == null ? "" : value;
        this.confidence = confidence;
        this.line = line;
        this.label = label == null ? "" : label;
        this.method = method == null ? "" : method;
    }

    public boolean empty() { return value == null || value.isBlank(); }
}
