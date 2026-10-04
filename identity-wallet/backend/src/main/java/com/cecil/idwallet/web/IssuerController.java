package com.cecil.idwallet.web;

import com.cecil.idwallet.auth.AuthUser;
import com.cecil.idwallet.domain.Role;
import com.cecil.idwallet.repo.IssuerRepository;
import com.cecil.idwallet.service.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Issuer portal: templates, issue, list issued, revoke. */
@RestController
@RequestMapping("/api/issuer")
public class IssuerController {

    private final CredentialService credentials;
    private final UserService users;
    private final IssuerRepository issuers;

    public IssuerController(CredentialService credentials, UserService users, IssuerRepository issuers) {
        this.credentials = credentials;
        this.users = users;
        this.issuers = issuers;
    }

    public record RevokeReq(String reason) {}

    @GetMapping("/me")
    public Map<String, Object> me(AuthUser u) {
        u.require(Role.ISSUER);
        Long issuerId = users.get(u.id()).issuerId;
        return Views.issuer(issuers.findById(issuerId).orElseThrow(() -> ApiException.notFound("Issuer not found")));
    }

    @GetMapping("/templates")
    public List<CredentialTemplates.Template> templates() {
        return CredentialTemplates.ALL;
    }

    @GetMapping("/credentials")
    public List<Map<String, Object>> issued(AuthUser u) {
        return credentials.issuedBy(u.require(Role.ISSUER));
    }

    @PostMapping("/credentials")
    public Map<String, Object> issue(AuthUser u, @RequestBody CredentialService.IssueRequest r, HttpServletRequest req) {
        return credentials.issue(u.require(Role.ISSUER), r, RequestInfo.ip(req));
    }

    @PostMapping("/credentials/{id}/revoke")
    public Map<String, String> revoke(AuthUser u, @PathVariable String id, @RequestBody(required = false) RevokeReq r, HttpServletRequest req) {
        credentials.revoke(u.require(Role.ISSUER), id, r == null ? null : r.reason(), RequestInfo.ip(req));
        return Map.of("status", "revoked");
    }
}
