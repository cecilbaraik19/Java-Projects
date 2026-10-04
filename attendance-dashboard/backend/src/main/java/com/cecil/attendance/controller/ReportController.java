package com.cecil.attendance.controller;

import com.cecil.attendance.dto.Dtos.DashboardStats;
import com.cecil.attendance.dto.Dtos.DepartmentStat;
import com.cecil.attendance.dto.Dtos.EmployeeSummary;
import com.cecil.attendance.dto.Dtos.LatePattern;
import com.cecil.attendance.service.ExportService;
import org.springframework.beans.factory.annotation.Value;
import com.cecil.attendance.dto.Dtos.TrendPoint;
import com.cecil.attendance.security.AccessGuard;
import com.cecil.attendance.service.ReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/** Reports: admins see the whole company, managers only their department. */
@RestController
@RequestMapping("/api/reports")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
public class ReportController {

    private final ReportService service;
    private final ExportService exports;
    private final AccessGuard guard;
    private final Clock clock;
    private final int lateThreshold;

    public ReportController(ReportService service, ExportService exports, AccessGuard guard, Clock clock,
                            @Value("${attendance.alerts.late-threshold:3}") int lateThreshold) {
        this.service = service;
        this.exports = exports;
        this.guard = guard;
        this.clock = clock;
        this.lateThreshold = lateThreshold;
    }

    private String scopeLabel() {
        String d = guard.departmentScope();
        return d == null ? "All departments" : d;
    }

    private static ResponseEntity<byte[]> file(byte[] body, String name, String type) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.parseMediaType(type))
                .body(body);
    }

    @GetMapping("/summary.xlsx")
    public ResponseEntity<byte[]> summaryXlsx(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        byte[] xlsx = exports.xlsx(service.summary(from, to, guard.departmentScope()), from, to, scopeLabel());
        return file(xlsx, "attendance_" + from + "_to_" + to + ".xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    @GetMapping("/summary.pdf")
    public ResponseEntity<byte[]> summaryPdf(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        byte[] pdf = exports.pdf(service.summary(from, to, guard.departmentScope()), from, to, scopeLabel());
        return file(pdf, "attendance_" + from + "_to_" + to + ".pdf", "application/pdf");
    }

    /** People who were late or absent often in the period ("needs attention"). */
    @GetMapping("/patterns")
    public List<LatePattern> patterns(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer threshold) {
        int t = threshold != null ? Math.max(1, Math.min(threshold, 31)) : lateThreshold;
        return service.latePatterns(from, to, guard.departmentScope(), t);
    }

    @GetMapping("/stats")
    public DashboardStats stats(@RequestParam(required = false)
                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.stats(date != null ? date : LocalDate.now(clock), guard.departmentScope());
    }

    @GetMapping("/trend")
    public List<TrendPoint> trend(@RequestParam(defaultValue = "14") int days) {
        return service.trend(days, guard.departmentScope());
    }

    @GetMapping("/departments")
    public List<DepartmentStat> departments(@RequestParam(required = false)
                                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.departments(date != null ? date : LocalDate.now(clock), guard.departmentScope());
    }

    @GetMapping("/summary")
    public List<EmployeeSummary> summary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.summary(from, to, guard.departmentScope());
    }

    @GetMapping(value = "/summary.csv", produces = "text/csv")
    public ResponseEntity<String> summaryCsv(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"attendance_" + from + "_to_" + to + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(service.summaryCsv(from, to, guard.departmentScope()));
    }
}
