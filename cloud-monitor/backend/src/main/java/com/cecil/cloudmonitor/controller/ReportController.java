package com.cecil.cloudmonitor.controller;

import com.cecil.cloudmonitor.service.ReportService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** File downloads: a PDF summary report and CSV exports (open them in Excel). */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private static final MediaType CSV = MediaType.parseMediaType("text/csv; charset=UTF-8");

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/summary.pdf")
    public ResponseEntity<byte[]> summaryPdf(@AuthenticationPrincipal UserDetails user) {
        return file(reports.summaryPdf(user.getUsername()), "cloudpulse-report", "pdf", MediaType.APPLICATION_PDF);
    }

    @GetMapping("/resources.csv")
    public ResponseEntity<byte[]> resourcesCsv() {
        return file(reports.resourcesCsv(), "cloudpulse-resources", "csv", CSV);
    }

    @GetMapping("/alerts.csv")
    public ResponseEntity<byte[]> alertsCsv(@RequestParam(defaultValue = "500") int limit) {
        return file(reports.alertsCsv(limit), "cloudpulse-alerts", "csv", CSV);
    }

    @GetMapping("/costs.csv")
    public ResponseEntity<byte[]> costsCsv() {
        return file(reports.costsCsv(), "cloudpulse-costs", "csv", CSV);
    }

    private static ResponseEntity<byte[]> file(byte[] body, String name, String ext, MediaType type) {
        String filename = name + "-" + LocalDate.now() + "." + ext;
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION)
                .body(body);
    }
}
