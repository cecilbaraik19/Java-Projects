package com.cecil.idwallet.service;

import com.cecil.idwallet.auth.AuthUser;
import com.cecil.idwallet.domain.*;
import com.cecil.idwallet.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class DashboardService {

    private final CredentialRepository creds;
    private final ShareRepository shares;
    private final VaultItemRepository vault;
    private final AuditEventRepository events;
    private final UserService userService;

    public DashboardService(CredentialRepository creds, ShareRepository shares, VaultItemRepository vault,
                            AuditEventRepository events, UserService userService) {
        this.creds = creds;
        this.shares = shares;
        this.vault = vault;
        this.events = events;
        this.userService = userService;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary(AuthUser actor) {
        UserAccount u = userService.get(actor.id());
        List<Credential> cs = creds.findByHolderIdOrderByIssuedAtDesc(u.id);
        List<Share> ss = shares.findByHolderIdOrderByCreatedAtDesc(u.id);
        List<VaultItem> vs = vault.findByOwnerIdOrderByUpdatedAtDesc(u.id);
        List<AuditEvent> recent = events.findTop100ByUserIdOrderByIdDesc(u.id);

        Instant soon = Instant.now().plus(60, ChronoUnit.DAYS);
        List<Map<String, Object>> expiring = new ArrayList<>();
        cs.stream().filter(c -> c.status == CredentialStatus.ACTIVE && c.expiresAt != null && c.expiresAt.isBefore(soon))
                .forEach(c -> expiring.add(Map.of("kind", "credential", "title", c.title, "date", c.expiresAt.toString(), "id", c.id)));
        LocalDate soonDate = LocalDate.now().plusDays(60);
        vs.stream().filter(v -> v.expiryDate != null && v.expiryDate.isBefore(soonDate))
                .forEach(v -> expiring.add(Map.of("kind", "document", "title", v.title, "date", v.expiryDate.toString(), "id", v.id)));

        long activeShares = ss.stream().filter(s -> ShareService.status(s).equals("ACTIVE")).count();
        long unlimited = ss.stream().filter(s -> ShareService.status(s).equals("ACTIVE") && s.maxViews == null).count();
        long failedLogins = recent.stream().filter(e -> e.at.isAfter(Instant.now().minus(7, ChronoUnit.DAYS)))
                .filter(e -> e.action.equals("LOGIN_FAILED") || e.action.equals("MFA_FAILED")).count();

        // simple security score with explainable checklist
        List<Map<String, Object>> checklist = List.of(
                item("Two-factor authentication on", u.mfaEnabled, 40),
                item("No failed sign-ins in the last 7 days", failedLogins == 0, 20),
                item("All active shares have a view limit", unlimited == 0, 20),
                item("No expired documents in your vault", vs.stream().noneMatch(v -> v.expiryDate != null && v.expiryDate.isBefore(LocalDate.now())), 20));
        int score = checklist.stream().filter(i -> (boolean) i.get("ok")).mapToInt(i -> (int) i.get("points")).sum();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user", Views.user(u));
        m.put("credentials", cs.size());
        m.put("activeCredentials", cs.stream().filter(c -> CredentialService.effectiveStatus(c).equals("ACTIVE")).count());
        m.put("activeShares", activeShares);
        m.put("totalViews", ss.stream().mapToInt(s -> s.views).sum());
        m.put("vaultItems", vs.size());
        m.put("expiring", expiring);
        m.put("securityScore", score);
        m.put("checklist", checklist);
        m.put("failedLogins7d", failedLogins);
        m.put("activity", recent.stream().limit(8).map(Views::audit).toList());
        return m;
    }

    private static Map<String, Object> item(String label, boolean ok, int points) {
        return Map.of("label", label, "ok", ok, "points", points);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> activity(AuthUser actor) {
        return events.findTop100ByUserIdOrderByIdDesc(actor.id()).stream().map(Views::audit).toList();
    }
}
