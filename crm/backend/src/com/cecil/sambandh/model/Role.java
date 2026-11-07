package com.cecil.sambandh.model;

/** Who a staff member is. Each role can do everything the role below it can, plus more. */
public enum Role {
    /** Sales agent: works only on the customers they own. */
    AGENT("Sales agent"),
    /** Manager: sees every customer, re-assigns owners, imports, merges duplicates, reads the activity log. */
    MANAGER("Manager"),
    /** Admin: everything a manager can do, plus staff accounts and erasing a customer's personal data. */
    ADMIN("Admin");

    public final String label;

    Role(String label) { this.label = label; }

    public boolean atLeast(Role other) { return this.ordinal() >= other.ordinal(); }

    public static Role parse(String s) {
        if (s == null) return null;
        try { return Role.valueOf(s.trim().toUpperCase()); } catch (IllegalArgumentException e) { return null; }
    }
}
