package com.cecil.cloudmonitor.controller;

import com.cecil.cloudmonitor.dto.AuthResponse;
import com.cecil.cloudmonitor.dto.LoginRequest;
import com.cecil.cloudmonitor.model.AppUser;
import com.cecil.cloudmonitor.repository.AppUserRepository;
import com.cecil.cloudmonitor.security.JwtService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authManager;
    private final AppUserRepository users;
    private final JwtService jwtService;

    public AuthController(AuthenticationManager authManager, AppUserRepository users, JwtService jwtService) {
        this.authManager = authManager;
        this.users = users;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        try {
            authManager.authenticate(new UsernamePasswordAuthenticationToken(req.username(), req.password()));
        } catch (AuthenticationException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
        AppUser u = users.findByUsername(req.username()).orElseThrow();
        return new AuthResponse(jwtService.generateToken(u.getUsername(), u.getRole().name()),
                u.getUsername(), u.getDisplayName(), u.getRole().name());
    }

    @GetMapping("/me")
    public AuthResponse me(@AuthenticationPrincipal UserDetails principal) {
        AppUser u = users.findByUsername(principal.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return new AuthResponse(null, u.getUsername(), u.getDisplayName(), u.getRole().name());
    }
}
