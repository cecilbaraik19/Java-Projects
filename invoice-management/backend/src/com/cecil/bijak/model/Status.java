package com.cecil.bijak.model;

/**
 * The life of an invoice (and of a credit note):
 *
 *   DRAFT ──issue──► ISSUED ──part payment──► PART_PAID ──rest paid──► PAID
 *     │                │
 *     └─over the limit─► PENDING_APPROVAL ──approve──► ISSUED      (──send back──► DRAFT)
 *                      └──cancel (nothing paid yet)──► CANCELLED
 *
 * "Overdue" is not a status: it is worked out every time (due date has passed and something is still owed).
 * A credit note is ISSUED and then APPLIED when its whole value has been used.
 */
public enum Status {
    DRAFT, PENDING_APPROVAL, ISSUED, PART_PAID, PAID, CANCELLED, APPLIED;

    /** Issued and still part of the books (not a draft, not cancelled). */
    public boolean booked() { return this == ISSUED || this == PART_PAID || this == PAID || this == APPLIED; }

    public boolean open() { return this == ISSUED || this == PART_PAID; }

    public static Status of(String s) {
        try { return Status.valueOf(s); } catch (Exception e) { return DRAFT; }
    }
}
