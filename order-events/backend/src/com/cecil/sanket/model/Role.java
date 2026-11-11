package com.cecil.sanket.model;

/** Who is signed in. Each role can do everything the role below it can, plus more. */
public enum Role {
    /** Customer: shops, places and cancels their own orders, follows their own orders, reads their notifications. */
    CUSTOMER("Customer"),
    /** Operations staff: sees all orders, the event log and consumers, dispatches and delivers parcels, restocks. */
    STAFF("Operations staff"),
    /** Platform admin: everything + pause/replay consumers, dead letters, fault injection, user accounts, activity log. */
    ADMIN("Platform admin");

    public final String label;

    Role(String label) { this.label = label; }

    public boolean atLeast(Role other) { return this.ordinal() >= other.ordinal(); }

    public static Role parse(String s) {
        if (s == null) return null;
        try { return Role.valueOf(s.trim().toUpperCase()); } catch (IllegalArgumentException e) { return null; }
    }
}
