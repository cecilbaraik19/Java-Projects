package com.cecil.idwallet.service;

import com.cecil.idwallet.crypto.*;
import com.cecil.idwallet.crypto.SelectiveDisclosure.Disclosure;
import com.cecil.idwallet.domain.*;
import com.cecil.idwallet.repo.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Public verification. Runs every check independently and reports each one, so the verifier
 * can see exactly why something passed or failed:
 *   1. issuer is in the trust registry and not suspended
 *   2. issuer's ECDSA signature over the credential is valid (data not altered)
 *   3. every revealed claim hashes to a digest inside the signed credential
 *   4. holder's signature binds the disclosures to this audience/time (no replay, not stolen)
 *   5. credential not revoked and not expired
 */
@Service
public class VerifyService {

    private final ShareRepository shares;
    private final AccessLogRepository logs;
    private final IssuerRepository issuers;
    private final UserRepository users;
    private final CredentialRepository creds;
    private final AuditService audit;
    private final ObjectMapper json = new ObjectMapper();

    public VerifyService(ShareRepository shares, AccessLogRepository logs, IssuerRepository issuers, UserRepository users,
                         CredentialRepository creds, AuditService audit) {
        this.shares = shares;
        this.logs = logs;
        this.issuers = issuers;
        this.users = users;
        this.creds = creds;
        this.audit = audit;
    }

    /** Opening a share link. Counts as a view and is logged for the holder. */
    @Transactional
    public Map<String, Object> verifyToken(String token, String ip, String userAgent) {
        Share s = token == null || token.length() > 100 ? null : shares.findByTokenHash(CryptoUtil.sha256Hex(token)).orElse(null);
        if (s == null) return failure("This link is not valid. Ask the person to share again");
        String st = ShareService.status(s);
        if (!st.equals("ACTIVE")) {
            log(s, ip, userAgent, st);
            return failure(switch (st) {
                case "REVOKED" -> "The holder has stopped sharing this information";
                case "EXPIRED" -> "This link expired on " + s.expiresAt;
                default -> "This link has reached its view limit";
            });
        }
        String presentation = AesGcm.decryptString(ShareService.shareKey(token), s.presentationEnc, "share");
        Map<String, Object> result = verifyPresentation(presentation);
        s.views++;
        log(s, ip, userAgent, Boolean.TRUE.equals(result.get("valid")) ? "VERIFIED" : "FAILED_CHECKS");
        audit.log(s.holderId, "verifier@" + ip, "SHARE_VIEWED", s.recipient + " opened your " + s.claimNames + " share", ip);

        Map<String, Object> share = new LinkedHashMap<>();
        share.put("recipient", s.recipient);
        share.put("purpose", s.purpose);
        share.put("expiresAt", s.expiresAt);
        share.put("viewsLeft", s.maxViews == null ? null : s.maxViews - s.views);
        result.put("share", share);
        result.put("presentation", presentation);
        return result;
    }

    private void log(Share s, String ip, String ua, String outcome) {
        AccessLog l = new AccessLog();
        l.shareId = s.id;
        l.ip = ip;
        l.userAgent = ua;
        l.outcome = outcome;
        logs.save(l);
    }

    private static Map<String, Object> failure(String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("valid", false);
        m.put("reason", reason);
        m.put("checks", List.of());
        return m;
    }

    /** Verifies a presentation JSON (from a link, or pasted / uploaded by a verifier). */
    @Transactional(readOnly = true)
    public Map<String, Object> verifyPresentation(String presentationJson) {
        List<Map<String, Object>> checks = new ArrayList<>();
        Map<String, Object> out = new LinkedHashMap<>();
        JsonNode vp, cred;
        String payload;
        try {
            vp = json.readTree(presentationJson);
            payload = vp.path("credential").asText();
            cred = json.readTree(payload);
        } catch (Exception e) {
            return failure("This isn't a valid IdentityWallet presentation file");
        }

        // 1. trust registry
        String issuerDid = cred.path("issuer").path("did").asText();
        Issuer issuer = issuers.findByDid(issuerDid).orElse(null);
        check(checks, "Trusted issuer", issuer != null && issuer.trusted,
                issuer == null ? "Issuer " + issuerDid + " is not in the trust registry"
                        : issuer.trusted ? issuer.name + " is a registered issuer" : issuer.name + " has been suspended");

        // 2. issuer signature
        boolean sigOk = issuer != null && EcKeys.verify(EcKeys.decodePublic(issuer.publicKey), payload, vp.path("issuerSignature").asText());
        check(checks, "Issuer signature", sigOk, sigOk ? "ECDSA P-256 signature is valid; the credential hasn't been altered"
                : "Signature does not match. The credential was forged or modified");

        // 3. disclosures
        Set<String> digests = new HashSet<>();
        cred.path("_sd").forEach(d -> digests.add(d.asText()));
        List<Map<String, Object>> claims = new ArrayList<>();
        List<String> encList = new ArrayList<>();
        boolean allMatch = vp.path("disclosures").isArray() && vp.path("disclosures").size() > 0;
        for (JsonNode n : vp.path("disclosures")) {
            encList.add(n.asText());
            try {
                Disclosure d = SelectiveDisclosure.decode(n.asText());
                boolean ok = digests.contains(d.digest());
                allMatch &= ok;
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("name", d.name());
                c.put("label", CredentialTemplates.label(d.name()));
                c.put("value", d.value().isTextual() ? d.value().asText() : d.value());
                c.put("matches", ok);
                claims.add(c);
            } catch (Exception e) {
                allMatch = false;
            }
        }
        check(checks, "Revealed details", allMatch, allMatch
                ? claims.size() + " detail(s) match the issuer-signed fingerprints; " + (digests.size() - claims.size()) + " kept private"
                : "At least one revealed detail does not match what the issuer signed");

        // 4. holder binding
        JsonNode p = vp.path("holderProof");
        String holderDid = cred.path("holder").asText();
        UserAccount holder = users.findByDid(holderDid).orElse(null);
        boolean proofOk = holder != null && holderDid.equals(p.path("holder").asText()) && EcKeys.verify(
                EcKeys.decodePublic(holder.publicKey),
                ShareService.proofMessage(payload, encList, p.path("audience").asText(), p.path("nonce").asText(),
                        p.path("createdAt").asText(), p.path("expiresAt").asText()),
                p.path("signature").asText());
        check(checks, "Holder consent", proofOk, proofOk
                ? "Signed by the credential owner for \"" + p.path("audience").asText() + "\""
                : "The holder's signature is missing or invalid (possible replay or stolen data)");

        boolean presentationLive = false;
        try {
            presentationLive = Instant.parse(p.path("expiresAt").asText()).isAfter(Instant.now());
        } catch (Exception ignored) { }
        check(checks, "Presentation time window", presentationLive,
                presentationLive ? "Valid until " + p.path("expiresAt").asText() : "This presentation has expired");

        // 5. status
        Credential c = creds.findById(cred.path("id").asText()).orElse(null);
        String status = c == null ? "UNKNOWN" : CredentialService.effectiveStatus(c);
        check(checks, "Credential status", "ACTIVE".equals(status), switch (status) {
            case "ACTIVE" -> "Not revoked, not expired";
            case "REVOKED" -> "Revoked by issuer: " + c.revocationReason;
            case "EXPIRED" -> "Expired on " + c.expiresAt;
            default -> "Credential no longer exists in the wallet registry";
        });

        boolean valid = checks.stream().allMatch(x -> Boolean.TRUE.equals(x.get("ok")));
        out.put("valid", valid);
        out.put("reason", valid ? "All checks passed" : "One or more checks failed");
        out.put("checks", checks);
        out.put("claims", claims);
        Map<String, Object> credInfo = new LinkedHashMap<>();
        credInfo.put("id", cred.path("id").asText());
        credInfo.put("type", cred.path("type").asText());
        credInfo.put("title", cred.path("title").asText());
        credInfo.put("issuedAt", cred.path("issuedAt").asText());
        credInfo.put("expiresAt", cred.path("expiresAt").isNull() ? null : cred.path("expiresAt").asText());
        credInfo.put("hiddenClaims", Math.max(0, digests.size() - claims.size()));
        out.put("credential", credInfo);
        out.put("issuer", issuer == null ? Map.of("did", issuerDid, "name", cred.path("issuer").path("name").asText())
                : Map.of("did", issuer.did, "name", issuer.name, "category", issuer.category,
                         "keyFingerprint", EcKeys.fingerprint(issuer.publicKey)));
        out.put("holder", Map.of("did", holderDid, "audience", p.path("audience").asText()));
        return out;
    }

    private static void check(List<Map<String, Object>> list, String name, boolean ok, String detail) {
        list.add(Map.of("name", name, "ok", ok, "detail", detail));
    }
}
