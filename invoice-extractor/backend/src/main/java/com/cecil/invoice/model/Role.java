package com.cecil.invoice.model;

/** Who can do what. The server checks the role on every API call. */
public enum Role {
    ADMIN("Admin"),             // everything, plus logins, settings and the AI model
    ACCOUNTANT("Accountant"),   // review, approve, reject, export, vendors
    CLERK("Clerk");             // upload invoices and correct what the AI read

    public final String label;
    Role(String label) { this.label = label; }
}
