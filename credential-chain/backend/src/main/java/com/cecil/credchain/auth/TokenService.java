package com.cecil.credchain.auth;

import com.cecil.credchain.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Issues and verifies JSON Web Tokens (JWT, HS256) using Java's built-in HMAC-SHA256.
 * A token proves who the user is for a limited time without sending the password again.
 */
@Service
public class TokenService {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final byte[] secret;
    private final long ttlSeconds;
    private final ObjectMapper mapper;

    public TokenService(ObjectMapper mapper,
                        @Value("${credchain.jwt-secret:}") String configuredSecret,
                        @Value("${credchain.jwt-ttl-hours:8}") long ttlHours) {
        this.mapper = mapper;
        this.ttlSeconds = ttlHours * 3600;
        if (configuredSecret == null || configuredSecret.isBlank()) {
            // No secret configured: generate a random one (everyone is logged out on restart).
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.secret = random;
        } else {
            this.secret = configuredSecret.getBytes(StandardCharsets.UTF_8);
        }
    }

    public String issue(UserAccount user) {
        long now = System.currentTimeMillis() / 1000;
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", user.getId());
        claims.put("email", user.getEmail());
        claims.put("name", user.getName());
        claims.put("role", user.getRole().name());
        if (user.getInstitutionId() != null) claims.put("inst", user.getInstitutionId());
        claims.put("iat", now);
        claims.put("exp", now + ttlSeconds);

        String header = B64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = B64.encodeToString(toJson(claims).getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + payload;
        return signingInput + "." + B64.encodeToString(hmac(signingInput));
    }

    /** Verifies signature and expiry, then returns the user inside the token. */
    public AuthUser verify(String token) {
        String[] parts = token == null ? new String[0] : token.split("\\.");
        if (parts.length != 3) throw unauthorized("Invalid token");
        byte[] expected = hmac(parts[0] + "." + parts[1]);
        byte[] given;
        try {
            given = B64D.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw unauthorized("Invalid token");
        }
        if (!MessageDigest.isEqual(expected, given)) throw unauthorized("Invalid token signature");

        Map<?, ?> claims;
        try {
            claims = mapper.readValue(new String(B64D.decode(parts[1]), StandardCharsets.UTF_8), Map.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw unauthorized("Invalid token");
        }
        long exp = ((Number) claims.get("exp")).longValue();
        if (System.currentTimeMillis() / 1000 > exp) throw unauthorized("Session expired, please sign in again");

        return new AuthUser((String) claims.get("sub"), (String) claims.get("email"), (String) claims.get("name"),
                Role.valueOf((String) claims.get("role")), (String) claims.get("inst"));
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 not available", e);
        }
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException unauthorized(String msg) {
        return new ApiException(HttpStatus.UNAUTHORIZED, msg);
    }
}
