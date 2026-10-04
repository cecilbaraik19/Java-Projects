package com.cecil.attendance.controller;

import com.cecil.attendance.dto.Dtos.KioskCode;
import com.cecil.attendance.service.CheckInPolicyService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Feeds the office kiosk screen with the current rotating check-in code. */
@RestController
@RequestMapping("/api/kiosk")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
public class KioskController {

    private final CheckInPolicyService policy;

    public KioskController(CheckInPolicyService policy) {
        this.policy = policy;
    }

    @GetMapping("/code")
    public ResponseEntity<KioskCode> code() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(policy.currentCode());
    }
}
