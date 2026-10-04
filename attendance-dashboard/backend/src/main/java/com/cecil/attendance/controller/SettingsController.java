package com.cecil.attendance.controller;

import com.cecil.attendance.dto.Dtos.CheckInSettingsUpdate;
import com.cecil.attendance.dto.Dtos.CheckInSettingsView;
import com.cecil.attendance.dto.Dtos.ClientIpView;
import com.cecil.attendance.security.ClientIpResolver;
import com.cecil.attendance.service.CheckInPolicyService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settings")
@PreAuthorize("hasRole('ADMIN')")
public class SettingsController {

    private final CheckInPolicyService policy;
    private final ClientIpResolver ipResolver;

    public SettingsController(CheckInPolicyService policy, ClientIpResolver ipResolver) {
        this.policy = policy;
        this.ipResolver = ipResolver;
    }

    @GetMapping("/checkin")
    public CheckInSettingsView get() {
        return CheckInSettingsView.of(policy.settings());
    }

    @PutMapping("/checkin")
    public CheckInSettingsView update(@Valid @RequestBody CheckInSettingsUpdate req) {
        return policy.update(req);
    }

    /** The IP the server sees for this browser - handy for filling in the office network. */
    @GetMapping("/client-ip")
    public ClientIpView clientIp() {
        return new ClientIpView(ipResolver.current());
    }
}
