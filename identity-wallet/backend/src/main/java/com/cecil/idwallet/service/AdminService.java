package com.cecil.idwallet.service;

import com.cecil.idwallet.crypto.EcKeys;
import com.cecil.idwallet.crypto.KeyVault;
import com.cecil.idwallet.domain.*;
import com.cecil.idwallet.repo.*;
import com.cecil.idwallet.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.KeyPair;
import java.util.*;

@Service
public class AdminService {

    private final IssuerRepository issuers;
    private final UserRepository users;
    private final CredentialRepository creds;
    private final ShareRepository shares;
    private final AuditEventRepository events;
    private final UserService userService;
    private final KeyVault keyVault;
    private final AuditService audit;

    public AdminService(IssuerRepository issuers, UserRepository users, CredentialRepository creds, ShareRepository shares,
                        AuditEventRepository events, UserService userService, KeyVault keyVault, AuditService audit) {
        this.issuers = issuers;
        this.users = users;
        this.creds = creds;
        this.shares = shares;
        this.events = events;
        this.userService = userService;
        this.keyVault = keyVault;
        this.audit = audit;
    }

    public record IssuerRequest(String name, String category, String website, String staffName, String staffEmail, String staffPassword) {}

    /** Registers an organisation: generates its signing key pair and its first staff login. */
    @Transactional
    public Map<String, Object> createIssuer(IssuerRequest r, String actor, Long actorId, String ip) {
        if (r.name() == null || r.name().isBlank() || r.name().length() > 150) throw ApiException.bad("Organisation name is required");
        if (issuers.findByNameIgnoreCase(r.name().trim()).isPresent()) throw ApiException.bad("An issuer with this name exists");
        IssuerCategory cat;
        try {
            cat = IssuerCategory.valueOf(r.category());
        } catch (Exception e) {
            throw ApiException.bad("Choose a valid category");
        }
        KeyPair kp = EcKeys.generate();
        Issuer i = new Issuer();
        i.name = r.name().trim();
        i.category = cat;
        i.website = r.website() == null || r.website().isBlank() ? null : r.website().trim();
        i.did = EcKeys.didFor(kp.getPublic());
        i.publicKey = EcKeys.encodePublic(kp.getPublic());
        i.wrappedPrivateKey = keyVault.wrapPrivateKey(kp.getPrivate(), i.did);
        issuers.save(i);
        UserAccount staff = userService.create(r.staffEmail(), r.staffName(), r.staffPassword(), Role.ISSUER, i.id);
        audit.log(actorId, actor, "ISSUER_REGISTERED", i.name + " (" + i.did + "), staff " + staff.email, ip);
        Map<String, Object> m = Views.issuer(i);
        m.put("staff", List.of(Views.user(staff)));
        return m;
    }

    @Transactional
    public Map<String, Object> setTrusted(Long id, boolean trusted, String actor, Long actorId, String ip) {
        Issuer i = issuers.findById(id).orElseThrow(() -> ApiException.notFound("Issuer not found"));
        i.trusted = trusted;
        audit.log(actorId, actor, trusted ? "ISSUER_RESTORED" : "ISSUER_SUSPENDED", i.name, ip);
        return Views.issuer(i);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> issuers() {
        return issuers.findAll().stream().map(i -> {
            Map<String, Object> m = Views.issuer(i);
            m.put("issued", creds.countByIssuerId(i.id));
            m.put("staff", users.findByIssuerId(i.id).stream().map(Views::user).toList());
            return m;
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> users() {
        return users.findAll().stream().map(Views::user).toList();
    }

    @Transactional
    public Map<String, Object> setActive(Long id, boolean active, String actor, Long actorId, String ip) {
        if (id.equals(actorId)) throw ApiException.bad("You can't disable your own account");
        UserAccount u = users.findById(id).orElseThrow(() -> ApiException.notFound("User not found"));
        u.active = active;
        audit.log(actorId, actor, active ? "USER_ENABLED" : "USER_DISABLED", u.email, ip);
        return Views.user(u);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("users", users.countByRole(Role.USER));
        m.put("issuerStaff", users.countByRole(Role.ISSUER));
        m.put("issuers", issuers.count());
        m.put("credentials", creds.count());
        m.put("revoked", creds.countByStatus(CredentialStatus.REVOKED));
        m.put("shares", shares.count());
        m.put("auditChain", audit.verifyChain());
        return m;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> auditLog() {
        return events.findTop200ByOrderByIdDesc().stream().map(Views::audit).toList();
    }
}
