package com.cecil.attendance.security;

import com.cecil.attendance.exception.ApiException;
import com.cecil.attendance.model.Employee;
import com.cecil.attendance.model.Role;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Data-level access rules (which rows a user may see), on top of the role checks
 * done with {@code @PreAuthorize} on the controllers.
 */
@Component
public class AccessGuard {

    /** Department value that matches nobody - used for a manager not linked to an employee. */
    public static final String NO_DEPARTMENT = "__no_department__";

    public static Optional<CurrentUser> currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CurrentUser cu) return Optional.of(cu);
        return Optional.empty();
    }

    public CurrentUser requireUser() {
        return currentUser().orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Please log in"));
    }

    /**
     * Department the current user is limited to: null = everything (admin),
     * otherwise the manager's own department.
     */
    public String departmentScope() {
        CurrentUser u = requireUser();
        if (u.role() == Role.ADMIN) return null;
        if (u.role() == Role.MANAGER) return u.department() != null ? u.department() : NO_DEPARTMENT;
        throw forbidden();
    }

    /** Throws 403 unless the current user may manage this employee. */
    public void checkCanManage(Employee e) {
        String scope = departmentScope();
        if (scope != null && !scope.equals(e.getDepartment())) throw forbidden();
    }

    public static boolean inScope(Employee e, String scope) {
        return scope == null || scope.equals(e.getDepartment());
    }

    private static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "You do not have permission to do this");
    }
}
