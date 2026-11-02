package com.cecil.rag.model;

/** Who someone is decides what they can do. */
public enum Role {
    STUDENT("Student"),
    FACULTY("Faculty"),
    ADMIN("Admin");

    public final String label;
    Role(String label) { this.label = label; }

    /** Faculty and admins can add documents, see gaps and write verified answers. */
    public boolean canCurate() { return this == FACULTY || this == ADMIN; }
    public boolean isAdmin() { return this == ADMIN; }

    /** Which document audiences this role is allowed to read. */
    public boolean canSee(String audience) {
        if (audience == null || audience.equals("all")) return true;
        if (this == ADMIN) return true;
        if (this == FACULTY) return audience.equals("faculty");
        return audience.equals("students");
    }

    public static Role parse(String s) {
        try { return Role.valueOf(s.toUpperCase()); } catch (Exception e) { return STUDENT; }
    }
}
