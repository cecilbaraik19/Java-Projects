package com.cecil.parisar.model;

/** Who is signed in. Each role can do everything the role below it can, plus more. */
public enum Role {
    /** Student: sees only their own attendance, risk report, notices, the campus map and the assistant. */
    STUDENT("Student"),
    /** Faculty: runs classes (attendance), sees the students in their courses, acknowledges alerts, posts notices. */
    FACULTY("Faculty"),
    /** Campus admin: everything + IoT devices, remote control, cloud sync and backups, AI model, staff accounts, activity log. */
    ADMIN("Campus admin");

    public final String label;

    Role(String label) { this.label = label; }

    public boolean atLeast(Role other) { return this.ordinal() >= other.ordinal(); }

    public static Role parse(String s) {
        if (s == null) return null;
        try { return Role.valueOf(s.trim().toUpperCase()); } catch (IllegalArgumentException e) { return null; }
    }
}
