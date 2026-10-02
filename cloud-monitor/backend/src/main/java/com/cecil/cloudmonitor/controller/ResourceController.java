package com.cecil.cloudmonitor.controller;

import com.cecil.cloudmonitor.dto.MetricPoint;
import com.cecil.cloudmonitor.dto.ResourceActionRequest;
import com.cecil.cloudmonitor.model.Alert;
import com.cecil.cloudmonitor.model.CloudResource;
import com.cecil.cloudmonitor.service.AlertService;
import com.cecil.cloudmonitor.service.MonitoringService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/resources")
public class ResourceController {

    private final MonitoringService monitoring;
    private final AlertService alertService;

    public ResourceController(MonitoringService monitoring, AlertService alertService) {
        this.monitoring = monitoring;
        this.alertService = alertService;
    }

    @GetMapping
    public List<CloudResource> list() {
        return monitoring.all();
    }

    @GetMapping("/{id}")
    public CloudResource get(@PathVariable Long id) {
        return monitoring.get(id);
    }

    @GetMapping("/{id}/metrics")
    public List<MetricPoint> metrics(@PathVariable Long id, @RequestParam(defaultValue = "1h") String range) {
        return monitoring.resourceHistory(id, range);
    }

    @GetMapping("/{id}/alerts")
    public List<Alert> alerts(@PathVariable Long id, @RequestParam(defaultValue = "20") int limit) {
        return alertService.forResource(id, limit);
    }

    /** START, STOP or RESTART a resource. Admins only. */
    @PostMapping("/{id}/action")
    @PreAuthorize("hasRole('ADMIN')")
    public CloudResource action(@PathVariable Long id, @Valid @RequestBody ResourceActionRequest req,
                                @AuthenticationPrincipal UserDetails user) {
        return monitoring.performAction(id, req.action(), user.getUsername());
    }
}
