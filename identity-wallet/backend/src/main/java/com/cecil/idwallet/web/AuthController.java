package com.cecil.idwallet.web;

import com.cecil.idwallet.auth.AuthUser;
import com.cecil.idwallet.auth.RateLimiter;
import com.cecil.idwallet.domain.Role;
import com.cecil.idwallet.domain.UserAccount;
import com.cecil.idwallet.service.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final UserService users;
    private final AuditService audit;
    private final DashboardService dashboard;
    private final RateLimiter limiter;

    public AuthController(AuthService auth, UserService users, AuditService audit, DashboardService dashboard, RateLimiter limiter) {
        this.auth = auth;
        this.users = users;
        this.audit = audit;
        this.dashboard = dashboard;
        this.limiter = limiter;
    }

    public record RegisterReq(String fullName, String email, String password) {}
    public record LoginReq(String email, String password) {}
    public record MfaReq(String ticket, String code) {}
    public record CodeReq(String code, String password) {}
    public record PasswordReq(String currentPassword, String newPassword) {}

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody RegisterReq r, HttpServletRequest req) {
        String ip = RequestInfo.ip(req);
        if (!limiter.allow("register:" + ip, 5, 3_600_000)) throw ApiException.tooMany("Too many sign-ups from your network, try later");
        UserAccount u = users.create(r.email(), r.fullName(), r.password(), Role.USER, null);
        audit.log(u.id, u.email, "REGISTERED", "Wallet created with DID " + u.did, ip);
        return auth.login(r.email(), r.password(), ip);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginReq r, HttpServletRequest req) {
        String ip = RequestInfo.ip(req);
        if (!limiter.allow("login:" + ip, 20, 300_000)) throw ApiException.tooMany("Too many attempts, wait a few minutes");
        return auth.login(r.email(), r.password(), ip);
    }

    @PostMapping("/mfa")
    public Map<String, Object> mfa(@RequestBody MfaReq r, HttpServletRequest req) {
        String ip = RequestInfo.ip(req);
        if (!limiter.allow("mfa:" + ip, 10, 300_000)) throw ApiException.tooMany("Too many attempts, wait a few minutes");
        return auth.verifyMfa(r.ticket(), r.code(), ip);
    }

    @GetMapping("/me")
    public Map<String, Object> me(AuthUser u) {
        return Views.user(users.get(u.id()));
    }

    @PostMapping("/mfa/setup")
    public Map<String, Object> mfaSetup(AuthUser u) {
        return auth.startMfaSetup(users.get(u.id()));
    }

    @PostMapping("/mfa/confirm")
    public Map<String, Object> mfaConfirm(AuthUser u, @RequestBody CodeReq r, HttpServletRequest req) {
        auth.confirmMfa(users.get(u.id()), r.code(), RequestInfo.ip(req));
        return Views.user(users.get(u.id()));
    }

    @PostMapping("/mfa/disable")
    public Map<String, Object> mfaDisable(AuthUser u, @RequestBody CodeReq r, HttpServletRequest req) {
        auth.disableMfa(users.get(u.id()), r.password(), r.code(), RequestInfo.ip(req));
        return Views.user(users.get(u.id()));
    }

    @PostMapping("/password")
    public Map<String, String> password(AuthUser u, @RequestBody PasswordReq r, HttpServletRequest req) {
        auth.changePassword(users.get(u.id()), r.currentPassword(), r.newPassword(), RequestInfo.ip(req));
        return Map.of("status", "ok");
    }

    @GetMapping("/activity")
    public List<Map<String, Object>> activity(AuthUser u) {
        return dashboard.activity(u);
    }
}
