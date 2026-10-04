package com.cecil.idwallet.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * A trusted organisation that can issue credentials. This table is the wallet's
 * "trust registry": a verifier only accepts signatures from public keys listed here.
 */
@Entity
@Table(name = "issuers")
public class Issuer {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, unique = true, length = 150)
    public String name;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    public IssuerCategory category = IssuerCategory.OTHER;

    @Column(length = 200)
    public String website;

    @Column(nullable = false, unique = true, length = 64)
    public String did;

    @Column(nullable = false, length = 300)
    public String publicKey;

    @Column(nullable = false, length = 400)
    public String wrappedPrivateKey;

    /** When false, new credentials can't be issued and existing ones fail verification. */
    public boolean trusted = true;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();
}
