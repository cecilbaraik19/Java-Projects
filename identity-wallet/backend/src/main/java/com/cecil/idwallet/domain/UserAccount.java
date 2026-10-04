package com.cecil.idwallet.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * A wallet account. Fields are public for brevity (Hibernate uses field access).
 * Nothing secret is stored in plain text: the password is a PBKDF2 hash, and the private key,
 * data key and TOTP secret are all encrypted under the master key.
 */
@Entity
@Table(name = "users", indexes = @Index(columnList = "email", unique = true))
public class UserAccount {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, unique = true, length = 190)
    public String email;

    @Column(nullable = false, length = 120)
    public String fullName;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    public Role role = Role.USER;

    @Column(nullable = false, length = 200)
    public String passwordHash;

    /** Decentralized-style identifier derived from the user's public key. */
    @Column(nullable = false, unique = true, length = 64)
    public String did;

    @Column(nullable = false, length = 300)
    public String publicKey;

    @Column(nullable = false, length = 400)
    public String wrappedPrivateKey;

    /** The user's AES-256 data-encryption key, wrapped by the master key. */
    @Column(nullable = false, length = 200)
    public String wrappedDek;

    @Column(length = 200)
    public String totpSecretEnc;
    public boolean mfaEnabled;

    /** Pending secret during MFA setup (until the user confirms a code). */
    @Column(length = 200)
    public String pendingTotpSecretEnc;

    public int failedLogins;
    public Instant lockedUntil;
    public Instant lastLoginAt;
    public boolean active = true;

    /** Set for ISSUER accounts: the organisation they act for. */
    public Long issuerId;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();
}
