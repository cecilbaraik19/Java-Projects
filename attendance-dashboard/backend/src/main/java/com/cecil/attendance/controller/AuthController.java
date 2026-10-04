package com.cecil.attendance.controller;

import com.cecil.attendance.dto.Dtos.ChangePasswordRequest;
import com.cecil.attendance.dto.Dtos.LoginRequest;
import com.cecil.attendance.dto.Dtos.LoginResponse;
import com.cecil.attendance.dto.Dtos.MeView;
import com.cecil.attendance.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    /** Public endpoint: exchange username + password for a JWT. */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        return auth.login(req.username(), req.password());
    }

    @GetMapping("/me")
    public MeView me() {
        return auth.me();
    }

    @PostMapping("/change-password")
    public LoginResponse changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        return auth.changePassword(req.currentPassword(), req.newPassword());
    }
}
