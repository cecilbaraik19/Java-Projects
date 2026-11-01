package com.cecil.invoice.model;

/** The result of one validation rule, e.g. "Line items add up to the subtotal". */
public class Check {
    public String code;
    public String label;
    public String result;   // PASS, WARN or FAIL
    public String detail;

    public Check() {}

    public Check(String code, String label, String result, String detail) {
        this.code = code;
        this.label = label;
        this.result = result;
        this.detail = detail;
    }
}
