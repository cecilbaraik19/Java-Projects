package com.cecil.attendance.service;

import com.cecil.attendance.dto.Dtos.CreateUserRequest;
import com.cecil.attendance.dto.Dtos.UpdateUserRequest;
import com.cecil.attendance.dto.Dtos.UserView;
import com.cecil.attendance.exception.ApiException;
import com.cecil.attendance.model.Employee;
import com.cecil.attendance.model.Role;
import com.cecil.attendance.model.UserAccount;
import com.cecil.attendance.repository.UserAccountRepository;
import com.cecil.attendance.security.AccessGuard;
import com.cecil.attendance.security.PasswordPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Admin-only management of login accounts. */
@Service
public class UserService {

    private final UserAccountRepository users;
    private final EmployeeService employees;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final AccessGuard guard;

    public UserService(UserAccountRepository users, EmployeeService employees, PasswordEncoder encoder,
                       AuditService audit, AccessGuard guard) {
        this.users = users;
        this.employees = employees;
        this.encoder = encoder;
        this.audit = audit;
        this.guard = guard;
    }

    @Transactional(readOnly = true)
    public List<UserView> list() {
        Instant now = Instant.now();
        return users.findAllByOrderByUsernameAsc().stream().map(u -> UserView.of(u, now)).toList();
    }

    @Transactional
    public UserView create(CreateUserRequest req) {
        String username = req.username().trim().toLowerCase();
        if (users.existsByUsernameIgnoreCase(username)) {
            throw ApiException.conflict("Username '" + username + "' is already taken");
        }
        PasswordPolicy.validate(req.password(), username);
        Employee emp = resolveEmployee(req.role(), req.employeeId(), null);

        UserAccount u = users.save(new UserAccount(username, encoder.encode(req.password()), req.role(), emp));
        audit.log(AuditService.USER_CREATED, "User", u.getId(),
                username + " as " + req.role() + (emp != null ? " linked to " + emp.getEmployeeCode() : ""));
        return UserView.of(u, Instant.now());
    }

    @Transactional
    public UserView update(Long id, UpdateUserRequest req) {
        UserAccount u = get(id);
        boolean self = u.getId().equals(guard.requireUser().id());
        if (self && (req.role() != u.getRole() || !req.enabled())) {
            throw ApiException.badRequest("You cannot change your own role or disable your own account");
        }
        boolean losingAdmin = u.getRole() == Role.ADMIN && u.isEnabled()
                && (req.role() != Role.ADMIN || !req.enabled());
        if (losingAdmin && users.countByRoleAndEnabledTrue(Role.ADMIN) <= 1) {
            throw ApiException.badRequest("There must be at least one active admin");
        }

        Employee emp = resolveEmployee(req.role(), req.employeeId(), u.getId());
        StringBuilder changes = new StringBuilder();
        if (u.getRole() != req.role()) changes.append("role ").append(u.getRole()).append("->").append(req.role()).append("; ");
        if (u.isEnabled() != req.enabled()) changes.append(req.enabled() ? "enabled; " : "disabled; ");

        if (u.getRole() != req.role() || (u.isEnabled() && !req.enabled())) {
            u.invalidateTokens(); // force re-login with the new permissions
        }
        u.setRole(req.role());
        u.setEnabled(req.enabled());
        u.setEmployee(emp);
        if (req.enabled()) {
            u.setLockedUntil(null);   // enabling also unlocks
            u.setFailedAttempts(0);
        }
        users.save(u);
        audit.log(AuditService.USER_UPDATED, "User", u.getId(), u.getUsername() + ": " + changes);
        return UserView.of(u, Instant.now());
    }

    @Transactional
    public void resetPassword(Long id, String newPassword) {
        UserAccount u = get(id);
        PasswordPolicy.validate(newPassword, u.getUsername());
        u.setPasswordHash(encoder.encode(newPassword));
        u.setLockedUntil(null);
        u.setFailedAttempts(0);
        u.invalidateTokens();
        users.save(u);
        audit.log(AuditService.PASSWORD_RESET, "User", u.getId(), "Password reset for " + u.getUsername());
    }

    @Transactional
    public void delete(Long id) {
        UserAccount u = get(id);
        if (u.getId().equals(guard.requireUser().id())) {
            throw ApiException.badRequest("You cannot delete your own account");
        }
        if (u.getRole() == Role.ADMIN && u.isEnabled() && users.countByRoleAndEnabledTrue(Role.ADMIN) <= 1) {
            throw ApiException.badRequest("There must be at least one active admin");
        }
        users.delete(u);
        audit.log(AuditService.USER_DELETED, "User", id, u.getUsername());
    }

    private UserAccount get(Long id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("User " + id + " not found"));
    }

    /** Managers and employees must be linked to exactly one employee record. */
    private Employee resolveEmployee(Role role, Long employeeId, Long currentUserId) {
        if (employeeId == null) {
            if (role != Role.ADMIN) throw ApiException.badRequest(role + " accounts must be linked to an employee");
            return null;
        }
        Employee emp = employees.get(employeeId);
        users.findByEmployeeId(employeeId)
                .filter(other -> !other.getId().equals(currentUserId))
                .ifPresent(other -> {
                    throw ApiException.conflict(emp.getFullName() + " already has an account (" + other.getUsername() + ")");
                });
        return emp;
    }
}
