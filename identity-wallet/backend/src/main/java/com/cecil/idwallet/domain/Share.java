package com.cecil.idwallet.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * A consent-based share: the holder reveals selected claims of one credential to a verifier
 * through a link / QR code. Only the SHA-256 hash of the link token is stored, and the
 * presentation is encrypted with a key derived from the token, so a database leak reveals
 * neither the shared data nor working links.
 */
@Entity
@Table(name = "shares", indexes = @Index(columnList = "tokenHash", unique = true))
public class Share {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, unique = true, length = 64)
    public String tokenHash;

    /** The raw token, encrypted with the holder's data key so the holder can show the QR again. */
    @Column(nullable = false, length = 200)
    public String tokenEnc;

    @Column(nullable = false, length = 40)
    public String credentialId;

    @Column(nullable = false)
    public Long holderId;

    @Column(nullable = false, length = 1000)
    public String claimNames;

    @Column(nullable = false, length = 120)
    public String recipient;

    @Column(length = 300)
    public String purpose;

    @Lob @Column(nullable = false, length = 1_000_000)
    public String presentationEnc;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    @Column(nullable = false)
    public Instant expiresAt;

    /** null = unlimited */
    public Integer maxViews;
    public int views;
    public boolean revoked;
}
