package com.cecil.attendance.security;

import com.cecil.attendance.model.UserAccount;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/** Issues and verifies HS256-signed JWTs. */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final String ISSUER = "attendance-dashboard";

    private final SecretKey key;
    private final Duration lifetime;

    public JwtService(@Value("${attendance.jwt.secret:}") String secret,
                      @Value("${attendance.jwt.expiration-minutes:480}") long expirationMinutes) {
        this.key = Keys.hmacShaKeyFor(keyBytes(secret));
        this.lifetime = Duration.ofMinutes(expirationMinutes);
    }

    private static byte[] keyBytes(String secret) {
        if (secret == null || secret.isBlank()) {
            byte[] random = new byte[64];
            new SecureRandom().nextBytes(random);
            log.warn("attendance.jwt.secret / JWT_SECRET is not set - using a random key. "
                    + "All users will be logged out when the server restarts.");
            return random;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(secret.trim());
        } catch (IllegalArgumentException notBase64) {
            bytes = secret.getBytes(StandardCharsets.UTF_8);
        }
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT secret must be at least 32 bytes (256 bits) long");
        }
        return bytes;
    }

    public String generate(UserAccount user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(user.getUsername())
                .claim("role", user.getRole().name())
                .claim("ver", user.getTokenVersion())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(lifetime)))
                .signWith(key)
                .compact();
    }

    /** Returns the verified claims, or null if the token is invalid, tampered with or expired. */
    public Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(ISSUER)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public long lifetimeSeconds() {
        return lifetime.toSeconds();
    }
}
