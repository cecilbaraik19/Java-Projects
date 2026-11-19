package com.cecil.bijak.model;

/**
 * What a person may do in Bijak.
 *   OWNER       everything, including people and company settings
 *   ACCOUNTANT  invoices, approvals, payments, bank matching, reports
 *   SALES       customers and draft invoices; may issue up to the approval limit; records nothing about money received
 *   AUDITOR     reads everything, changes nothing (the CA who checks the books)
 */
public enum Role {
    OWNER, ACCOUNTANT, SALES, AUDITOR;

    public boolean money() { return this == OWNER || this == ACCOUNTANT; }

    public boolean writes() { return this != AUDITOR; }

    public String label() {
        return switch (this) {
            case OWNER -> "Owner";
            case ACCOUNTANT -> "Accountant";
            case SALES -> "Sales";
            case AUDITOR -> "Auditor (read only)";
        };
    }

    public static Role of(String s) {
        try { return Role.valueOf(s == null ? "" : s.trim().toUpperCase()); } catch (IllegalArgumentException e) { return null; }
    }
}
