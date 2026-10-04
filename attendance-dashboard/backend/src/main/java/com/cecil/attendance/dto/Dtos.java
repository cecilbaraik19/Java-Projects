package com.cecil.attendance.dto;

import com.cecil.attendance.model.AttendanceRecord;
import com.cecil.attendance.model.AttendanceStatus;
import com.cecil.attendance.model.CorrectionRequest;
import com.cecil.attendance.model.Employee;
import com.cecil.attendance.model.LeaveRequest;
import com.cecil.attendance.model.LeaveType;
import com.cecil.attendance.model.Role;
import com.cecil.attendance.model.UserAccount;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** All request/response shapes used by the REST API. */
public final class Dtos {

    private Dtos() {
    }

    // ---------- Requests ----------

    public record EmployeeRequest(
            @NotBlank @Size(max = 20) String employeeCode,
            @NotBlank @Size(max = 100) String fullName,
            @NotBlank @Email @Size(max = 120) String email,
            @NotBlank @Size(max = 60) String department,
            @Size(max = 80) String designation,
            LocalDate joinDate,
            Boolean active
    ) {
    }

    public record CheckRequest(@NotNull Long employeeId) {
    }

    public record ManualEntryRequest(
            @NotNull Long employeeId,
            @NotNull LocalDate date,
            @NotNull AttendanceStatus status,
            LocalTime checkIn,
            LocalTime checkOut,
            @Size(max = 255) String note
    ) {
    }

    // ---------- Responses ----------

    /** One row on the daily board. status is NOT_MARKED when no record exists yet. */
    public record AttendanceView(
            Long id,
            Long employeeId,
            String employeeCode,
            String employeeName,
            String department,
            LocalDate date,
            LocalTime checkIn,
            LocalTime checkOut,
            String status,
            double hoursWorked,
            String note
    ) {
        public static AttendanceView of(AttendanceRecord r) {
            Employee e = r.getEmployee();
            return new AttendanceView(r.getId(), e.getId(), e.getEmployeeCode(), e.getFullName(), e.getDepartment(),
                    r.getDate(), r.getCheckIn(), r.getCheckOut(), r.getStatus().name(), r.getHoursWorked(), r.getNote());
        }

        public static AttendanceView notMarked(Employee e, LocalDate date) {
            return new AttendanceView(null, e.getId(), e.getEmployeeCode(), e.getFullName(), e.getDepartment(),
                    date, null, null, "NOT_MARKED", 0, null);
        }
    }

    public record DashboardStats(
            LocalDate date,
            boolean workingDay,
            long totalEmployees,
            long present,
            long late,
            long halfDay,
            long onLeave,
            long absent,
            long stillInOffice,
            double attendanceRate,
            LocalTime averageCheckIn
    ) {
    }

    public record TrendPoint(
            LocalDate date,
            long present,
            long late,
            long halfDay,
            long onLeave,
            long absent,
            double attendanceRate
    ) {
    }

    public record DepartmentStat(
            String department,
            long total,
            long attended,
            long onLeave,
            double attendanceRate
    ) {
    }

    public record EmployeeSummary(
            Long employeeId,
            String employeeCode,
            String employeeName,
            String department,
            long workingDays,
            long present,
            long late,
            long halfDay,
            long onLeave,
            long absent,
            double totalHours,
            double attendanceRate
    ) {
    }

    public record ApiError(int status, String error, String message) {
    }

    // ---------- Auth & users ----------

    public record LoginRequest(@NotBlank @Size(max = 40) String username,
                               @NotBlank @Size(max = 128) String password) {
    }

    public record MeView(Long id, String username, String role, Long employeeId,
                         String employeeName, String department) {
        public static MeView of(UserAccount u) {
            Employee e = u.getEmployee();
            return new MeView(u.getId(), u.getUsername(), u.getRole().name(),
                    e != null ? e.getId() : null, e != null ? e.getFullName() : null,
                    e != null ? e.getDepartment() : null);
        }
    }

    public record LoginResponse(String token, long expiresInSeconds, MeView user) {
    }

    public record ChangePasswordRequest(@NotBlank @Size(max = 128) String currentPassword,
                                        @NotBlank @Size(max = 128) String newPassword) {
    }

    public record CreateUserRequest(
            @NotBlank @Size(min = 3, max = 40) @Pattern(regexp = "^[A-Za-z0-9._-]+$",
                    message = "may only contain letters, digits, dot, dash and underscore") String username,
            @NotBlank @Size(max = 128) String password,
            @NotNull Role role,
            Long employeeId
    ) {
    }

    public record UpdateUserRequest(@NotNull Role role, @NotNull Boolean enabled, Long employeeId) {
    }

    public record ResetPasswordRequest(@NotBlank @Size(max = 128) String newPassword) {
    }

    public record UserView(Long id, String username, String role, boolean enabled, boolean locked,
                           Long employeeId, String employeeName, String department,
                           Instant lastLoginAt, Instant createdAt) {
        public static UserView of(UserAccount u, Instant now) {
            Employee e = u.getEmployee();
            return new UserView(u.getId(), u.getUsername(), u.getRole().name(), u.isEnabled(), u.isLocked(now),
                    e != null ? e.getId() : null, e != null ? e.getFullName() : null,
                    e != null ? e.getDepartment() : null, u.getLastLoginAt(), u.getCreatedAt());
        }
    }

    public record PageView<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    }

    // ---------- Leave & correction requests ----------

    public record LeaveCreateRequest(@NotNull LeaveType type, @NotNull LocalDate fromDate, @NotNull LocalDate toDate,
                                     @NotBlank @Size(max = 300) String reason) {
    }

    public record CorrectionCreateRequest(@NotNull LocalDate date, @NotNull LocalTime checkIn, LocalTime checkOut,
                                          @NotBlank @Size(max = 300) String reason) {
    }

    public record ReviewRequest(@NotNull Boolean approve, @Size(max = 300) String comment) {
    }

    public record LeaveBalance(String type, Integer allowance, int used, int pending, Integer remaining) {
    }

    public record ApprovalCounts(long leave, long corrections) {
    }

    public record LeaveView(Long id, Long employeeId, String employeeCode, String employeeName, String department,
                            String type, LocalDate fromDate, LocalDate toDate, int days, String reason,
                            String status, String reviewedBy, Instant reviewedAt, String reviewComment,
                            Instant createdAt) {
        public static LeaveView of(LeaveRequest l) {
            Employee e = l.getEmployee();
            return new LeaveView(l.getId(), e.getId(), e.getEmployeeCode(), e.getFullName(), e.getDepartment(),
                    l.getType().name(), l.getFromDate(), l.getToDate(), l.getDays(), l.getReason(),
                    l.getStatus().name(), l.getReviewedBy(), l.getReviewedAt(), l.getReviewComment(), l.getCreatedAt());
        }
    }

    public record CorrectionView(Long id, Long employeeId, String employeeCode, String employeeName, String department,
                                 LocalDate date, LocalTime previousCheckIn, LocalTime previousCheckOut,
                                 String previousStatus, LocalTime requestedCheckIn, LocalTime requestedCheckOut,
                                 String reason, String status, String reviewedBy, Instant reviewedAt,
                                 String reviewComment, Instant createdAt) {
        public static CorrectionView of(CorrectionRequest c) {
            Employee e = c.getEmployee();
            return new CorrectionView(c.getId(), e.getId(), e.getEmployeeCode(), e.getFullName(), e.getDepartment(),
                    c.getDate(), c.getPreviousCheckIn(), c.getPreviousCheckOut(), c.getPreviousStatus(),
                    c.getRequestedCheckIn(), c.getRequestedCheckOut(), c.getReason(), c.getStatus().name(),
                    c.getReviewedBy(), c.getReviewedAt(), c.getReviewComment(), c.getCreatedAt());
        }
    }
}
