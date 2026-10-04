package com.cecil.attendance.model;

public enum Role {
    /** Full access: all employees, users, settings and the audit log. */
    ADMIN,
    /** Sees and manages attendance only for employees in their own department. */
    MANAGER,
    /** Sees only their own attendance. */
    EMPLOYEE;

    public String authority() {
        return "ROLE_" + name();
    }
}
