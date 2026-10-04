package com.cecil.attendance.service;

import com.cecil.attendance.model.AuditLog;
import com.cecil.attendance.repository.AuditLogRepository;
import com.cecil.attendance.security.AccessGuard;
import com.cecil.attendance.security.ClientIpResolver;
import com.cecil.attendance.security.CurrentUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class AuditService {

    /** Action names, kept as constants so the UI filter list stays in sync. */
    public static final String LOGIN = "LOGIN";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";
    public static final String ACCOUNT_LOCKED = "ACCOUNT_LOCKED";
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";
    public static final String USER_CREATED = "USER_CREATED";
    public static final String USER_UPDATED = "USER_UPDATED";
    public static final String USER_DELETED = "USER_DELETED";
    public static final String PASSWORD_RESET = "PASSWORD_RESET";
    public static final String EMPLOYEE_CREATED = "EMPLOYEE_CREATED";
    public static final String EMPLOYEE_UPDATED = "EMPLOYEE_UPDATED";
    public static final String EMPLOYEE_DELETED = "EMPLOYEE_DELETED";
    public static final String CHECK_IN = "CHECK_IN";
    public static final String CHECK_OUT = "CHECK_OUT";
    public static final String ATTENDANCE_EDITED = "ATTENDANCE_EDITED";
    public static final String ATTENDANCE_DELETED = "ATTENDANCE_DELETED";
    public static final String LEAVE_REQUESTED = "LEAVE_REQUESTED";
    public static final String LEAVE_APPROVED = "LEAVE_APPROVED";
    public static final String LEAVE_REJECTED = "LEAVE_REJECTED";
    public static final String LEAVE_CANCELLED = "LEAVE_CANCELLED";
    public static final String CORRECTION_REQUESTED = "CORRECTION_REQUESTED";
    public static final String CORRECTION_APPROVED = "CORRECTION_APPROVED";
    public static final String CORRECTION_REJECTED = "CORRECTION_REJECTED";
    public static final String CORRECTION_CANCELLED = "CORRECTION_CANCELLED";
    public static final String CHECK_IN_REJECTED = "CHECK_IN_REJECTED";
    public static final String SETTINGS_UPDATED = "SETTINGS_UPDATED";
    public static final String HOLIDAY_ADDED = "HOLIDAY_ADDED";
    public static final String HOLIDAY_UPDATED = "HOLIDAY_UPDATED";
    public static final String HOLIDAY_DELETED = "HOLIDAY_DELETED";
    public static final String SHIFT_SAVED = "SHIFT_SAVED";
    public static final String SHIFT_DELETED = "SHIFT_DELETED";

    private final AuditLogRepository repo;
    private final ClientIpResolver ipResolver;

    public AuditService(AuditLogRepository repo, ClientIpResolver ipResolver) {
        this.repo = repo;
        this.ipResolver = ipResolver;
    }

    /** Logs an action by the currently authenticated user. */
    public void log(String action, String entityType, Object entityId, String details) {
        String user = AccessGuard.currentUser().map(CurrentUser::username).orElse("system");
        logAs(user, action, entityType, entityId, details);
    }

    /** Logs an action for an explicit username (e.g. a failed login, where nobody is authenticated). */
    public void logAs(String username, String action, String entityType, Object entityId, String details) {
        repo.save(new AuditLog(Instant.now(), truncate(username, 40), action, entityType,
                entityId == null ? null : truncate(String.valueOf(entityId), 40),
                truncate(details, 500), truncate(ipResolver.current(), 64)));
    }

    public Page<AuditLog> search(String username, String action, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 200);
        return repo.search(username == null ? "" : username.trim(),
                action == null ? "" : action.trim(),
                PageRequest.of(Math.max(page, 0), safeSize));
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
