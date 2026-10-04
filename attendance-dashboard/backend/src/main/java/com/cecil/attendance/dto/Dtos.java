package com.cecil.attendance.dto;

import com.cecil.attendance.model.AttendanceRecord;
import com.cecil.attendance.model.AttendanceStatus;
import com.cecil.attendance.model.Employee;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;

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
}
