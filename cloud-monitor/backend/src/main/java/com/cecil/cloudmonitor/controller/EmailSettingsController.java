package com.cecil.cloudmonitor.controller;

import com.cecil.cloudmonitor.dto.EmailSettingsDto;
import com.cecil.cloudmonitor.dto.EmailSettingsRequest;
import com.cecil.cloudmonitor.service.EmailAlertService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settings/email")
public class EmailSettingsController {

    private final EmailAlertService email;

    public EmailSettingsController(EmailAlertService email) {
        this.email = email;
    }

    @GetMapping
    public EmailSettingsDto get() {
        return email.settings();
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public EmailSettingsDto update(@Valid @RequestBody EmailSettingsRequest req) {
        return email.update(req);
    }

    @PostMapping("/test")
    @PreAuthorize("hasRole('ADMIN')")
    public EmailSettingsDto test() {
        return email.sendTest();
    }
}
