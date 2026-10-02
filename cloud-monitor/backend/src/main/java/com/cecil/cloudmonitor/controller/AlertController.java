package com.cecil.cloudmonitor.controller;

import com.cecil.cloudmonitor.dto.AlertRuleRequest;
import com.cecil.cloudmonitor.model.Alert;
import com.cecil.cloudmonitor.model.AlertRule;
import com.cecil.cloudmonitor.service.AlertService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping
    public List<Alert> recent(@RequestParam(defaultValue = "100") int limit) {
        return alertService.recent(limit);
    }

    @GetMapping("/active")
    public List<Alert> active() {
        return alertService.active();
    }

    @PostMapping("/{id}/ack")
    public Alert acknowledge(@PathVariable Long id, @AuthenticationPrincipal UserDetails user) {
        return alertService.acknowledge(id, user.getUsername());
    }

    @PostMapping("/ack-all")
    public Map<String, Integer> acknowledgeAll(@AuthenticationPrincipal UserDetails user) {
        return Map.of("acknowledged", alertService.acknowledgeAll(user.getUsername()));
    }

    // ---------- rules ----------

    @GetMapping("/rules")
    public List<AlertRule> rules() {
        return alertService.rules();
    }

    @PostMapping("/rules")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public AlertRule create(@Valid @RequestBody AlertRuleRequest req) {
        return alertService.createRule(req);
    }

    @PutMapping("/rules/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public AlertRule update(@PathVariable Long id, @Valid @RequestBody AlertRuleRequest req) {
        return alertService.updateRule(id, req);
    }

    @DeleteMapping("/rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {
        alertService.deleteRule(id);
    }
}
