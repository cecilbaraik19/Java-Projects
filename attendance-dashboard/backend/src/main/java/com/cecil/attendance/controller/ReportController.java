package com.cecil.attendance.controller;

import com.cecil.attendance.dto.Dtos.DashboardStats;
import com.cecil.attendance.dto.Dtos.DepartmentStat;
import com.cecil.attendance.dto.Dtos.EmployeeSummary;
import com.cecil.attendance.dto.Dtos.TrendPoint;
import com.cecil.attendance.service.ReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService service;
    private final Clock clock;

    public ReportController(ReportService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @GetMapping("/stats")
    public DashboardStats stats(@RequestParam(required = false)
                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.stats(date != null ? date : LocalDate.now(clock));
    }

    @GetMapping("/trend")
    public List<TrendPoint> trend(@RequestParam(defaultValue = "14") int days) {
        return service.trend(days);
    }

    @GetMapping("/departments")
    public List<DepartmentStat> departments(@RequestParam(required = false)
                                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.departments(date != null ? date : LocalDate.now(clock));
    }

    @GetMapping("/summary")
    public List<EmployeeSummary> summary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.summary(from, to);
    }

    @GetMapping(value = "/summary.csv", produces = "text/csv")
    public ResponseEntity<String> summaryCsv(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"attendance_" + from + "_to_" + to + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(service.summaryCsv(from, to));
    }
}
