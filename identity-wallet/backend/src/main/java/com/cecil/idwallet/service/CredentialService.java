package com.cecil.idwallet.service;

import com.cecil.idwallet.auth.AuthUser;
import com.cecil.idwallet.crypto.*;
import com.cecil.idwallet.crypto.SelectiveDisclosure.Disclosure;
import com.cecil.idwallet.domain.*;
import com.cecil.idwallet.repo.*;
import com.cecil.idwallet.web.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class CredentialService {

    private final CredentialRepository creds;
    private final IssuerRepository issuers;
    private final UserRepository users;
    private final ShareRepository shares;
    private final UserService userService;
    private final KeyVault keyVault;
    private final AuditService audit;
    private final ObjectMapper json = new ObjectMapper();

    public CredentialService(CredentialRepository creds, IssuerRepository issuers, UserRepository users, ShareRepository shares,
                             UserService userService, KeyVault keyVault, AuditService audit) {
        this.creds = creds;
        this.issuers = issuers;
        this.users = users;
        this.shares = shares;
        this.userService = userService;
        this.keyVault = keyVault;
        this.audit = audit;
    }

    public record IssueRequest(String holderEmail, String type, String title, Map<String, String> claims, Integer validityDays) {}

    // ------------------------------------------------------------------ issuing

    @Transactional
    public Map<String, Object> issue(AuthUser actor, IssueRequest r, String ip) {
        UserAccount staff = userService.get(actor.id());
        if (staff.issuerId == null) throw ApiException.forbidden("Your account isn't linked to an issuing organisation");
        Issuer issuer = issuers.findById(staff.issuerId).orElseThrow(() -> ApiException.notFound("Issuer not found"));
        if (!issuer.trusted) throw ApiException.forbidden("Your organisation has been suspended by the administrator");
        UserAccount holder = users.findByEmailIgnoreCase(r.holderEmail() == null ? "" : r.holderEmail().trim())
                .filter(u -> u.active).orElseThrow(() -> ApiException.bad("No wallet found for " + r.holderEmail()
                        + ". The person must create an account first"));

        String type = (r.type() == null ? "" : r.type().trim().toUpperCase());
        if (!type.matches("[A-Z0-9_]{2,40}")) throw ApiException.bad("Type must be 2-40 letters, digits or _");
        var template = CredentialTemplates.find(type);
        String title = (r.title() != null && !r.title().isBlank()) ? r.title().trim()
                : template.map(CredentialTemplates.Template::label).orElse(type);
        if (title.length() > 150) throw ApiException.bad("Title is too long");

        Map<String, Object> claims = cleanClaims(r.claims());
        template.ifPresent(t -> t.fields().stream().filter(CredentialTemplates.Field::required)
                .filter(f -> !claims.containsKey(f.name()))
                .findFirst().ifPresent(f -> { throw ApiException.bad(f.label() + " is required"); }));
        addDerivedClaims(claims);

        int days = r.validityDays() != null ? r.validityDays() : template.map(CredentialTemplates.Template::validityDays).orElse(365);
        if (days < 0 || days > 36500) throw ApiException.bad("Validity must be 0-36500 days (0 = never expires)");

        Credential c = new Credential();
        c.id = "cred_" + CryptoUtil.randomToken(12);
        c.type = type;
        c.title = title;
        c.issuerId = issuer.id;
        c.holderId = holder.id;
        c.issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        c.expiresAt = days == 0 ? null : c.issuedAt.plus(days, ChronoUnit.DAYS);

        List<Disclosure> disclosures = claims.entrySet().stream()
                .map(e -> SelectiveDisclosure.create(e.getKey(), e.getValue())).toList();
        List<String> digests = disclosures.stream().map(Disclosure::digest).sorted().toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("@context", "https://idwallet.local/credentials/v1");
        payload.put("id", c.id);
        payload.put("type", c.type);
        payload.put("title", c.title);
        payload.put("issuer", Map.of("did", issuer.did, "name", issuer.name));
        payload.put("holder", holder.did);
        payload.put("issuedAt", c.issuedAt.toString());
        payload.put("expiresAt", c.expiresAt == null ? null : c.expiresAt.toString());
        payload.put("_sd_alg", "sha-256");
        payload.put("_sd", digests);
        try {
            c.payload = json.writeValueAsString(payload);
            c.disclosuresEnc = AesGcm.encryptString(userService.dek(holder),
                    json.writeValueAsString(disclosures.stream().map(Disclosure::encoded).toList()), "cred:" + c.id);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        c.signature = EcKeys.sign(keyVault.unwrapPrivateKey(issuer.wrappedPrivateKey, issuer.did), c.payload);
        c.claimNames = String.join(",", claims.keySet());
        creds.save(c);

        audit.log(staff.id, staff.email, "CREDENTIAL_ISSUED", c.title + " (" + c.id + ") to " + holder.email, ip);
        audit.log(holder.id, issuer.name, "CREDENTIAL_RECEIVED", c.title + " from " + issuer.name, ip);
        return issuedView(c, holder);
    }

    private Map<String, Object> cleanClaims(Map<String, String> in) {
        if (in == null || in.isEmpty()) throw ApiException.bad("Add at least one claim");
        if (in.size() > 30) throw ApiException.bad("At most 30 claims");
        Map<String, Object> out = new LinkedHashMap<>();
        in.forEach((k, v) -> {
            if (k == null || !k.matches("[A-Za-z][A-Za-z0-9_]{0,39}")) throw ApiException.bad("Invalid claim name: " + k);
            if (k.startsWith("age_over_")) throw ApiException.bad("age_over_* claims are calculated automatically");
            if (v == null || v.isBlank()) return;
            if (v.length() > 500) throw ApiException.bad("Value of " + k + " is too long");
            out.put(k, v.trim());
        });
        if (out.isEmpty()) throw ApiException.bad("Add at least one claim");
        return out;
    }

    /** From a date of birth the issuer adds yes/no claims, so holders can prove age without revealing it. */
    static void addDerivedClaims(Map<String, Object> claims) {
        Object dob = claims.get("dateOfBirth");
        if (dob == null) return;
        try {
            LocalDate d = LocalDate.parse(dob.toString());
            if (d.isAfter(LocalDate.now())) throw ApiException.bad("Date of birth is in the future");
            int age = Period.between(d, LocalDate.now()).getYears();
            claims.put("age_over_18", age >= 18);
            claims.put("age_over_21", age >= 21);
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiException.bad("Date of birth must be YYYY-MM-DD");
        }
    }

    @Transactional
    public void revoke(AuthUser actor, String credentialId, String reason, String ip) {
        UserAccount staff = userService.get(actor.id());
        Credential c = creds.findById(credentialId).orElseThrow(() -> ApiException.notFound("Credential not found"));
        if (staff.issuerId == null || !staff.issuerId.equals(c.issuerId)) throw ApiException.forbidden("Only the issuing organisation can revoke this");
        if (c.status == CredentialStatus.REVOKED) throw ApiException.bad("Already revoked");
        c.status = CredentialStatus.REVOKED;
        c.revokedAt = Instant.now();
        c.revocationReason = (reason == null || reason.isBlank()) ? "No reason given" : reason.trim();
        if (c.revocationReason.length() > 300) c.revocationReason = c.revocationReason.substring(0, 300);
        creds.save(c);
        audit.log(staff.id, staff.email, "CREDENTIAL_REVOKED", c.id + ": " + c.revocationReason, ip);
        audit.log(c.holderId, staff.email, "CREDENTIAL_REVOKED", c.title + " was revoked: " + c.revocationReason, ip);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> issuedBy(AuthUser actor) {
        UserAccount staff = userService.get(actor.id());
        if (staff.issuerId == null) return List.of();
        Map<Long, UserAccount> holders = new HashMap<>();
        return creds.findByIssuerIdOrderByIssuedAtDesc(staff.issuerId).stream()
                .map(c -> issuedView(c, holders.computeIfAbsent(c.holderId, id -> users.findById(id).orElse(null))))
                .toList();
    }

    private Map<String, Object> issuedView(Credential c, UserAccount holder) {
        Map<String, Object> m = baseView(c);
        m.put("holderEmail", holder == null ? "?" : holder.email);
        m.put("holderName", holder == null ? "?" : holder.fullName);
        return m;
    }

    // ------------------------------------------------------------------ holder side

    @Transactional(readOnly = true)
    public List<Map<String, Object>> walletOf(AuthUser actor) {
        Map<Long, Issuer> cache = new HashMap<>();
        return creds.findByHolderIdOrderByIssuedAtDesc(actor.id()).stream().map(c -> {
            Map<String, Object> m = baseView(c);
            Issuer i = cache.computeIfAbsent(c.issuerId, id -> issuers.findById(id).orElse(null));
            m.put("issuer", i == null ? null : Map.of("name", i.name, "category", i.category, "trusted", i.trusted));
            return m;
        }).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(AuthUser actor, String id) {
        Credential c = owned(actor, id);
        UserAccount holder = userService.get(actor.id());
        Issuer i = issuers.findById(c.issuerId).orElseThrow();
        Map<String, Object> m = baseView(c);
        m.put("issuer", Views.issuer(i));
        List<Map<String, Object>> claims = new ArrayList<>();
        for (Disclosure d : disclosures(holder, c)) {
            Map<String, Object> cl = new LinkedHashMap<>();
            cl.put("name", d.name());
            cl.put("label", CredentialTemplates.label(d.name()));
            cl.put("value", d.value().isTextual() ? d.value().asText() : d.value());
            cl.put("derived", d.name().startsWith("age_over_"));
            cl.put("digest", d.digest());
            claims.add(cl);
        }
        m.put("claims", claims);
        m.put("payload", c.payload);
        m.put("signature", c.signature);
        m.put("signatureValid", EcKeys.verify(EcKeys.decodePublic(i.publicKey), c.payload, c.signature));
        m.put("shareCount", shares.findByCredentialId(c.id).size());
        return m;
    }

    @Transactional
    public void remove(AuthUser actor, String id, String ip) {
        Credential c = owned(actor, id);
        shares.findByCredentialId(c.id).forEach(s -> s.revoked = true);
        creds.delete(c);
        audit.log(actor.id(), actor.email(), "CREDENTIAL_REMOVED", c.title + " removed from wallet", ip);
    }

    Credential owned(AuthUser actor, String id) {
        Credential c = creds.findById(id).orElseThrow(() -> ApiException.notFound("Credential not found"));
        // same 404 for "not yours" so IDs of other people's credentials can't be probed
        if (!c.holderId.equals(actor.id())) throw ApiException.notFound("Credential not found");
        return c;
    }

    List<Disclosure> disclosures(UserAccount holder, Credential c) {
        try {
            List<String> enc = json.readValue(AesGcm.decryptString(userService.dek(holder), c.disclosuresEnc, "cred:" + c.id),
                    new TypeReference<>() {});
            return enc.stream().map(SelectiveDisclosure::decode).toList();
        } catch (Exception e) {
            throw new IllegalStateException("Could not decrypt credential " + c.id, e);
        }
    }

    static Map<String, Object> baseView(Credential c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id);
        m.put("type", c.type);
        m.put("title", c.title);
        m.put("issuedAt", c.issuedAt);
        m.put("expiresAt", c.expiresAt);
        m.put("status", effectiveStatus(c));
        m.put("revocationReason", c.revocationReason);
        m.put("claimNames", Arrays.asList(c.claimNames.split(",")));
        return m;
    }

    static String effectiveStatus(Credential c) {
        if (c.status == CredentialStatus.REVOKED) return "REVOKED";
        if (c.expiresAt != null && c.expiresAt.isBefore(Instant.now())) return "EXPIRED";
        return "ACTIVE";
    }
}
