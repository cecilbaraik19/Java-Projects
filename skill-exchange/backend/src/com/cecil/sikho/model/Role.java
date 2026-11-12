package com.cecil.sikho.model;

/** What a person may do. A MODERATOR is a normal member who also looks after the community. */
public enum Role {
    MEMBER("Member"),
    MODERATOR("Moderator");

    public final String label;

    Role(String label) { this.label = label; }

    public static Role parse(String s) {
        if (s == null) return null;
        for (Role r : values()) if (r.name().equalsIgnoreCase(s.trim())) return r;
        return null;
    }
}
