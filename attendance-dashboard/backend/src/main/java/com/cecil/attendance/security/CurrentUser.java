package com.cecil.attendance.security;

import com.cecil.attendance.model.Role;
import com.cecil.attendance.model.UserAccount;

/** The authenticated user, stored as the principal in Spring Security's context. */
public record CurrentUser(Long id, String username, Role role, Long employeeId, String department) {

    public static CurrentUser of(UserAccount u) {
        var e = u.getEmployee();
        return new CurrentUser(u.getId(), u.getUsername(), u.getRole(),
                e != null ? e.getId() : null, e != null ? e.getDepartment() : null);
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
