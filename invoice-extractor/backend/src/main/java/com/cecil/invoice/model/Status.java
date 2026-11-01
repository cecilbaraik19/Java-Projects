package com.cecil.invoice.model;

/** Where an invoice is in its journey. Each one is shown as a rubber stamp. */
public enum Status {
    TO_REVIEW("To review"),     // read by the AI, waiting for a person to check it
    APPROVED("Approved"),       // checked and accepted - goes into the ledger
    REJECTED("Rejected"),       // not accepted (wrong, unreadable or a duplicate)
    EXPORTED("Exported");       // approved and already sent to accounts as CSV / JSON

    public final String label;
    Status(String label) { this.label = label; }
}
