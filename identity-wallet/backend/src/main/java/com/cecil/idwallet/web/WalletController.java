package com.cecil.idwallet.web;

import com.cecil.idwallet.auth.AuthUser;
import com.cecil.idwallet.service.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Holder-facing endpoints: dashboard, credentials, shares. */
@RestController
@RequestMapping("/api/wallet")
public class WalletController {

    private final CredentialService credentials;
    private final ShareService shares;
    private final DashboardService dashboard;

    public WalletController(CredentialService credentials, ShareService shares, DashboardService dashboard) {
        this.credentials = credentials;
        this.shares = shares;
        this.dashboard = dashboard;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(AuthUser u) {
        return dashboard.summary(u);
    }

    @GetMapping("/credentials")
    public List<Map<String, Object>> credentials(AuthUser u) {
        return credentials.walletOf(u);
    }

    @GetMapping("/credentials/{id}")
    public Map<String, Object> credential(AuthUser u, @PathVariable String id) {
        return credentials.detail(u, id);
    }

    @DeleteMapping("/credentials/{id}")
    public Map<String, String> remove(AuthUser u, @PathVariable String id, HttpServletRequest req) {
        credentials.remove(u, id, RequestInfo.ip(req));
        return Map.of("status", "removed");
    }

    @GetMapping("/shares")
    public List<Map<String, Object>> shares(AuthUser u) {
        return shares.list(u);
    }

    @PostMapping("/shares")
    public Map<String, Object> share(AuthUser u, @RequestBody ShareService.ShareRequest r, HttpServletRequest req) {
        return shares.create(u, r, RequestInfo.ip(req));
    }

    @PostMapping("/shares/{id}/revoke")
    public Map<String, String> revokeShare(AuthUser u, @PathVariable Long id, HttpServletRequest req) {
        shares.revoke(u, id, RequestInfo.ip(req));
        return Map.of("status", "revoked");
    }
}
