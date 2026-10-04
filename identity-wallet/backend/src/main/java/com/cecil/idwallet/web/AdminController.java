package com.cecil.idwallet.web;

import com.cecil.idwallet.auth.AuthUser;
import com.cecil.idwallet.domain.IssuerCategory;
import com.cecil.idwallet.domain.Role;
import com.cecil.idwallet.service.AdminService;
import com.cecil.idwallet.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService admin;
    private final AuditService audit;

    public AdminController(AdminService admin, AuditService audit) {
        this.admin = admin;
        this.audit = audit;
    }

    public record Toggle(boolean value) {}

    @GetMapping("/stats")
    public Map<String, Object> stats(AuthUser u) {
        u.require(Role.ADMIN);
        return admin.stats();
    }

    @GetMapping("/categories")
    public IssuerCategory[] categories() {
        return IssuerCategory.values();
    }

    @GetMapping("/issuers")
    public List<Map<String, Object>> issuers(AuthUser u) {
        u.require(Role.ADMIN);
        return admin.issuers();
    }

    @PostMapping("/issuers")
    public Map<String, Object> createIssuer(AuthUser u, @RequestBody AdminService.IssuerRequest r, HttpServletRequest req) {
        u.require(Role.ADMIN);
        return admin.createIssuer(r, u.email(), u.id(), RequestInfo.ip(req));
    }

    @PostMapping("/issuers/{id}/trusted")
    public Map<String, Object> trust(AuthUser u, @PathVariable Long id, @RequestBody Toggle t, HttpServletRequest req) {
        u.require(Role.ADMIN);
        return admin.setTrusted(id, t.value(), u.email(), u.id(), RequestInfo.ip(req));
    }

    @GetMapping("/users")
    public List<Map<String, Object>> users(AuthUser u) {
        u.require(Role.ADMIN);
        return admin.users();
    }

    @PostMapping("/users/{id}/active")
    public Map<String, Object> active(AuthUser u, @PathVariable Long id, @RequestBody Toggle t, HttpServletRequest req) {
        u.require(Role.ADMIN);
        return admin.setActive(id, t.value(), u.email(), u.id(), RequestInfo.ip(req));
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> auditLog(AuthUser u) {
        u.require(Role.ADMIN);
        return admin.auditLog();
    }

    @GetMapping("/audit/verify")
    public Map<String, Object> verifyChain(AuthUser u) {
        u.require(Role.ADMIN);
        return audit.verifyChain();
    }
}
