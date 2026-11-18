package com.cecil.swikriti.model;

/**
 * What a person may do in Swikriti.
 *   EMPLOYEE - writes documents and approves the ones routed to them
 *   MANAGER  - the same, plus sees the documents of the people below them
 *   ADMIN    - the Document Controller: workflow templates, people, holidays, the ledger check, all documents
 */
public enum Role {
    EMPLOYEE, MANAGER, ADMIN;

    public static Role of(String s) {
        if (s == null) return EMPLOYEE;
        try { return Role.valueOf(s.trim().toUpperCase()); } catch (IllegalArgumentException e) { return EMPLOYEE; }
    }
}
