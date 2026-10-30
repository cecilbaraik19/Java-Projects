package com.cecil.sld.model;

/** Who is using the system. Each role sees different pages. */
public enum Role {
    ADMIN("Admin"),
    TEACHER("Teacher"),
    STUDENT("Student");

    public final String label;
    Role(String label) { this.label = label; }
}
