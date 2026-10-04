package com.cecil.idwallet.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * A verifiable credential held in a user's wallet.
 *
 * payload   – the issuer-signed JSON. It contains only salted SHA-256 digests of each claim
 *             (never the values), so it can be shown to anyone without leaking data.
 * signature – ECDSA P-256 signature of the payload by the issuer.
 * disclosuresEnc – the salts + values that open each digest, encrypted with the holder's
 *             data key. The holder picks which ones to reveal when sharing (selective disclosure).
 */
@Entity
@Table(name = "credentials")
public class Credential {
    @Id @Column(length = 40)
    public String id;

    @Column(nullable = false, length = 40)
    public String type;

    @Column(nullable = false, length = 150)
    public String title;

    @Column(nullable = false)
    public Long issuerId;

    @Column(nullable = false)
    public Long holderId;

    @Column(nullable = false)
    public Instant issuedAt;
    public Instant expiresAt;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    public CredentialStatus status = CredentialStatus.ACTIVE;
    public Instant revokedAt;
    @Column(length = 300)
    public String revocationReason;

    @Lob @Column(nullable = false, length = 1_000_000)
    public String payload;

    @Column(nullable = false, length = 200)
    public String signature;

    @Lob @Column(nullable = false, length = 1_000_000)
    public String disclosuresEnc;

    /** Claim names only (no values) so lists can be shown without decrypting. */
    @Column(nullable = false, length = 1000)
    public String claimNames;
}
