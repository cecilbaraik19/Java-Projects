package com.cecil.idwallet.service;

import com.cecil.idwallet.auth.AuthUser;
import com.cecil.idwallet.crypto.*;
import com.cecil.idwallet.crypto.SelectiveDisclosure.Disclosure;
import com.cecil.idwallet.domain.*;
import com.cecil.idwallet.repo.*;
import com.cecil.idwallet.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Consent-based sharing. The holder picks claims, a recipient, a purpose, an expiry and a view
 * limit. The wallet builds a "verifiable presentation":
 *
 *   { credential (issuer-signed payload), issuerSignature,
 *     disclosures (only the chosen claims),
 *     holderProof (holder's own signature binding these exact disclosures to this recipient + time) }
 *
 * The holder proof stops replay: someone who intercepts one presentation can't re-use the
 * disclosures with a different audience or expiry, because they can't produce the holder's signature.
 */
@Service
public class ShareService {

    private final ShareRepository shares;
    private final AccessLogRepository logs;
    private final CredentialRepository creds;
    private final CredentialService credentialService;
    private final UserService userService;
    private final AuditService audit;
    private final String publicUrl;
    private final ObjectMapper json = new ObjectMapper();

    public ShareService(ShareRepository shares, AccessLogRepository logs, CredentialRepository creds,
                        CredentialService credentialService, UserService userService, AuditService audit,
                        @Value("${idwallet.public-url:http://localhost:5173}") String publicUrl) {
        this.shares = shares;
        this.logs = logs;
        this.creds = creds;
        this.credentialService = credentialService;
        this.userService = userService;
        this.audit = audit;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
    }

    public record ShareRequest(String credentialId, List<String> claims, String recipient, String purpose,
                               Integer expiresInMinutes, Integer maxViews) {}

    static byte[] shareKey(String token) {
        return CryptoUtil.sha256("idwallet-share-key|" + token);
    }

    static String proofMessage(String payload, List<String> disclosures, String audience, String nonce, String createdAt, String expiresAt) {
        return String.join("|", CryptoUtil.sha256Hex(payload), String.join("~", disclosures), audience, nonce, createdAt, expiresAt);
    }

    @Transactional
    public Map<String, Object> create(AuthUser actor, ShareRequest r, String ip) {
        UserAccount holder = userService.get(actor.id());
        Credential c = credentialService.owned(actor, r.credentialId());
        String status = CredentialService.effectiveStatus(c);
        if (!status.equals("ACTIVE")) throw ApiException.bad("This credential is " + status.toLowerCase() + " and can't be shared");

        Set<String> wanted = new LinkedHashSet<>(r.claims() == null ? List.of() : r.claims());
        if (wanted.isEmpty()) throw ApiException.bad("Choose at least one detail to share");
        String recipient = r.recipient() == null ? "" : r.recipient().trim();
        if (recipient.isEmpty() || recipient.length() > 120) throw ApiException.bad("Enter who you are sharing with (max 120 characters)");
        String purpose = r.purpose() == null ? null : r.purpose().trim();
        if (purpose != null && purpose.length() > 300) throw ApiException.bad("Purpose is too long");
        int minutes = r.expiresInMinutes() == null ? 1440 : r.expiresInMinutes();
        if (minutes < 5 || minutes > 60 * 24 * 90) throw ApiException.bad("Expiry must be between 5 minutes and 90 days");
        Integer maxViews = r.maxViews();
        if (maxViews != null && (maxViews < 1 || maxViews > 1000)) throw ApiException.bad("View limit must be 1-1000");

        List<Disclosure> chosen = credentialService.disclosures(holder, c).stream()
                .filter(d -> wanted.contains(d.name())).toList();
        if (chosen.size() != wanted.size()) throw ApiException.bad("Some selected details are not in this credential");

        String token = CryptoUtil.randomToken(24);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expires = now.plus(minutes, ChronoUnit.MINUTES);
        if (c.expiresAt != null && expires.isAfter(c.expiresAt)) expires = c.expiresAt;
        List<String> enc = chosen.stream().map(Disclosure::encoded).toList();
        String nonce = CryptoUtil.randomToken(16);
        String sig = EcKeys.sign(userService.signingKey(holder),
                proofMessage(c.payload, enc, recipient, nonce, now.toString(), expires.toString()));

        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("holder", holder.did);
        proof.put("audience", recipient);
        proof.put("nonce", nonce);
        proof.put("createdAt", now.toString());
        proof.put("expiresAt", expires.toString());
        proof.put("signature", sig);
        Map<String, Object> presentation = new LinkedHashMap<>();
        presentation.put("format", "idwallet-vp+sd");
        presentation.put("credential", c.payload);
        presentation.put("issuerSignature", c.signature);
        presentation.put("disclosures", enc);
        presentation.put("holderProof", proof);

        Share s = new Share();
        s.tokenHash = CryptoUtil.sha256Hex(token);
        s.tokenEnc = AesGcm.encryptString(userService.dek(holder), token, "share-token:" + s.tokenHash);
        s.credentialId = c.id;
        s.holderId = holder.id;
        s.claimNames = String.join(",", wanted);
        s.recipient = recipient;
        s.purpose = purpose;
        s.createdAt = now;
        s.expiresAt = expires;
        s.maxViews = maxViews;
        try {
            s.presentationEnc = AesGcm.encryptString(shareKey(token), json.writeValueAsString(presentation), "share");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        shares.save(s);
        audit.log(holder.id, holder.email, "SHARE_CREATED",
                "Shared " + s.claimNames + " of " + c.title + " with " + recipient, ip);
        Map<String, Object> view = view(s, c, token);
        view.put("logs", List.of());
        return view;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(AuthUser actor) {
        UserAccount holder = userService.get(actor.id());
        byte[] dek = userService.dek(holder);
        List<Share> mine = shares.findByHolderIdOrderByCreatedAtDesc(holder.id);
        Map<Long, List<AccessLog>> byShare = mine.isEmpty() ? Map.of()
                : logs.findTop50ByShareIdInOrderByAtDesc(mine.stream().map(s -> s.id).toList())
                  .stream().collect(Collectors.groupingBy(l -> l.shareId));
        Map<String, Credential> cache = new HashMap<>();
        return mine.stream().map(s -> {
            Credential c = cache.computeIfAbsent(s.credentialId, id -> creds.findById(id).orElse(null));
            Map<String, Object> m = view(s, c, AesGcm.decryptString(dek, s.tokenEnc, "share-token:" + s.tokenHash));
            m.put("logs", byShare.getOrDefault(s.id, List.of()).stream().map(l -> Map.of(
                    "at", l.at, "ip", l.ip == null ? "" : l.ip, "userAgent", l.userAgent == null ? "" : l.userAgent,
                    "outcome", l.outcome)).toList());
            return m;
        }).toList();
    }

    @Transactional
    public void revoke(AuthUser actor, Long id, String ip) {
        Share s = shares.findById(id).filter(x -> x.holderId.equals(actor.id()))
                .orElseThrow(() -> ApiException.notFound("Share not found"));
        s.revoked = true;
        audit.log(actor.id(), actor.email(), "SHARE_REVOKED", "Stopped sharing with " + s.recipient, ip);
    }

    private Map<String, Object> view(Share s, Credential c, String token) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id);
        m.put("credentialId", s.credentialId);
        m.put("credentialTitle", c == null ? "(removed credential)" : c.title);
        m.put("claims", Arrays.stream(s.claimNames.split(",")).map(n -> Map.of("name", n, "label", CredentialTemplates.label(n))).toList());
        m.put("recipient", s.recipient);
        m.put("purpose", s.purpose);
        m.put("createdAt", s.createdAt);
        m.put("expiresAt", s.expiresAt);
        m.put("maxViews", s.maxViews);
        m.put("views", s.views);
        m.put("status", status(s));
        m.put("link", publicUrl + "/v/" + token);
        return m;
    }

    static String status(Share s) {
        if (s.revoked) return "REVOKED";
        if (s.expiresAt.isBefore(Instant.now())) return "EXPIRED";
        if (s.maxViews != null && s.views >= s.maxViews) return "USED_UP";
        return "ACTIVE";
    }
}
