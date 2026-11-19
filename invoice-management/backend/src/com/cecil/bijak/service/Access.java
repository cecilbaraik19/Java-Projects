package com.cecil.bijak.service;

import com.cecil.bijak.model.User;

/**
 * Who may do what. Everyone who signs in may READ everything (it is one small company);
 * the checks below are for CHANGES.
 */
public final class Access {
    private Access() { }

    /** Anything that changes data: not the Auditor. */
    public static void writes(User u) {
        if (u == null || !u.role.writes()) throw Problem.forbidden("Your account can only read (Auditor).");
    }

    /** Money matters: approving, cancelling, credit notes, payments, bank matching. Owner and Accountant. */
    public static void money(User u) {
        if (u == null || !u.role.money()) throw Problem.forbidden("Only the Accountant or the Owner can do this.");
    }

    /** People and company settings: Owner only. */
    public static void owner(User u) {
        if (u == null || !u.owner()) throw Problem.forbidden("Only the Owner can do this.");
    }
}
