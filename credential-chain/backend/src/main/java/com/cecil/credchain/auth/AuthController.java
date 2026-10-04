package com.cecil.credchain.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {}

    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {}

    @PostMapping("/login")
    public AuthService.LoginResult login(@Valid @RequestBody LoginRequest req) {
        return auth.login(req.email(), req.password());
    }

    @PostMapping("/change-password")
    public Map<String, Object> changePassword(@RequestHeader(value = "Authorization", required = false) String authorization,
                                              @Valid @RequestBody ChangePasswordRequest req) {
        auth.changePassword(auth.currentUser(authorization), req.currentPassword(), req.newPassword());
        return Map.of("message", "Password changed");
    }

    @GetMapping("/me")
    public Map<String, Object> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return auth.describe(auth.currentUser(authorization));
    }
}
