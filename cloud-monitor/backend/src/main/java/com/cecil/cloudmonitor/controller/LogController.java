package com.cecil.cloudmonitor.controller;

import com.cecil.cloudmonitor.dto.LogEntry;
import com.cecil.cloudmonitor.service.LogService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/** Recent log lines. New lines also arrive live over WebSocket on /topic/logs. */
@RestController
@RequestMapping("/api/logs")
public class LogController {

    private final LogService logs;

    public LogController(LogService logs) {
        this.logs = logs;
    }

    @GetMapping
    public List<LogEntry> list(@RequestParam(required = false) Long resourceId,
                               @RequestParam(defaultValue = "DEBUG") String level,
                               @RequestParam(required = false) String q,
                               @RequestParam(defaultValue = "500") int limit) {
        return logs.query(resourceId, level, q, limit);
    }

    /** Same filters, as a .log text file. */
    @GetMapping("/download")
    public ResponseEntity<byte[]> download(@RequestParam(required = false) Long resourceId,
                                           @RequestParam(defaultValue = "DEBUG") String level,
                                           @RequestParam(required = false) String q) {
        byte[] body = logs.asText(logs.query(resourceId, level, q, 5000)).getBytes(StandardCharsets.UTF_8);
        String filename = "cloudpulse-logs-" + LocalDate.now() + ".log";
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION)
                .body(body);
    }
}
