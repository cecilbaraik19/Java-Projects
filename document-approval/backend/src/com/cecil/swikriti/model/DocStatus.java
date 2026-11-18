package com.cecil.swikriti.model;

/**
 * The life of a document:
 *   DRAFT --submit--> IN_REVIEW --all stages done--> APPROVED
 *                        |  \--a stage cannot get enough "yes"--> REJECTED
 *                        \--sent back--> RETURNED --author edits, submits (new revision)--> IN_REVIEW
 *   DRAFT / IN_REVIEW / RETURNED --author withdraws--> WITHDRAWN
 * APPROVED, REJECTED and WITHDRAWN are final.
 */
public enum DocStatus {
    DRAFT, IN_REVIEW, RETURNED, APPROVED, REJECTED, WITHDRAWN;

    public boolean closed() { return this == APPROVED || this == REJECTED || this == WITHDRAWN; }

    public static DocStatus of(String s) {
        try { return DocStatus.valueOf(s); } catch (Exception e) { return DRAFT; }
    }
}
