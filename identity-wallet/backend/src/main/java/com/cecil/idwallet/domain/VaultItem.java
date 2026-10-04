package com.cecil.idwallet.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A personal identity document (passport scan, PAN card, etc.) in the encrypted vault.
 * The sensitive fields and the file itself are AES-256-GCM encrypted with the owner's data key.
 */
@Entity
@Table(name = "vault_items", indexes = @Index(columnList = "ownerId"))
public class VaultItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false)
    public Long ownerId;
    @Column(nullable = false, length = 120)
    public String title;
    @Column(nullable = false, length = 40)
    public String category;
    /** Encrypted JSON: { documentNumber, notes } */
    @Lob @Column(length = 1_000_000)
    public String fieldsEnc;
    /** Kept in plain text so expiry reminders work without decrypting. */
    public LocalDate expiryDate;

    @Column(length = 200)
    public String fileName;
    @Column(length = 100)
    public String contentType;
    public long fileSize;
    /** SHA-256 of the original file, to prove it hasn't changed. */
    @Column(length = 64)
    public String fileSha256;
    @Lob @Column(length = 10_485_760)
    public byte[] fileEnc;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();
    @Column(nullable = false)
    public Instant updatedAt = Instant.now();
}
