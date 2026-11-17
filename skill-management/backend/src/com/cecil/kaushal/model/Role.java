package com.cecil.kaushal.model;

/** What a person may do. ADMIN = HR / skills admin, MANAGER = leads a team, EMPLOYEE = everybody else. */
public enum Role {
    ADMIN, MANAGER, EMPLOYEE;

    public static Role of(String s) {
        if (s == null) return EMPLOYEE;
        try { return valueOf(s.trim().toUpperCase()); } catch (IllegalArgumentException e) { return EMPLOYEE; }
    }
}
