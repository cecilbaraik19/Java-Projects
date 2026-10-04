package com.cecil.idwallet.auth;

import com.cecil.idwallet.crypto.CryptoUtil;
import com.cecil.idwallet.domain.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal JWT (HS256) implementation: header.payload.signature, each Base64url.
 * Two token kinds: "access" (normal session) and "mfa" (5-minute ticket proving the password
 * was correct, which must be exchanged together with a TOTP code for an access token).
 */
@Service
public class TokenService {

    private final byte[] secret;
    private final long ttlMinutes;
    private final ObjectMapper json = new ObjectMapper();

    public TokenService(@Value("${idwallet.jwt-secret:}") String secret,
                        @Value("${idwallet.jwt-ttl-minutes:480}") long ttlMinutes) {
        this.secret = (secret == null || secret.isBlank()) ? CryptoUtil.randomBytes(32) : secret.getBytes(StandardCharsets.UTF_8);
        this.ttlMinutes = ttlMinutes;
    }

    public String issueAccess(Long userId, String email, Role role) {
        return issue(userId, email, role, "access", ttlMinutes * 60);
    }

    public String issueMfaTicket(Long userId, String email, Role role) {
        return issue(userId, email, role, "mfa", 300);
    }

    private String issue(Long userId, String email, Role role, String kind, long ttlSeconds) {
        try {
            long now = Instant.now().getEpochSecond();
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("sub", String.valueOf(userId));
            claims.put("email", email);
            claims.put("role", role.name());
            claims.put("kind", kind);
            claims.put("iat", now);
            claims.put("exp", now + ttlSeconds);
            claims.put("jti", CryptoUtil.randomToken(9));
            String header = CryptoUtil.b64url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String body = CryptoUtil.b64url(json.writeValueAsBytes(claims));
            return header + "." + body + "." + CryptoUtil.b64url(hmac(header + "." + body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Returns the principal, or null if the token is malformed, forged, expired, or the wrong kind. */
    public AuthUser parse(String token, String expectedKind) {
        try {
            String[] p = token.split("\\.");
            if (p.length != 3) return null;
            if (!CryptoUtil.constantTimeEquals(hmac(p[0] + "." + p[1]), CryptoUtil.fromB64url(p[2]))) return null;
            JsonNode c = json.readTree(CryptoUtil.fromB64url(p[1]));
            if (c.path("exp").asLong() < Instant.now().getEpochSecond()) return null;
            if (!expectedKind.equals(c.path("kind").asText())) return null;
            return new AuthUser(Long.valueOf(c.get("sub").asText()), c.get("email").asText(), Role.valueOf(c.get("role").asText()));
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] hmac(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.US_ASCII));
    }
}
