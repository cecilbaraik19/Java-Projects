package com.cecil.credchain.institution;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.time.Instant;

/** A university / college allowed to issue credentials. */
@Entity
@Table(name = "institutions")
public class Institution {

    /** Large enough that MySQL uses LONGTEXT (up to 4 GB) instead of TINYTEXT (255 chars). */
    private static final int LONG_TEXT = 16_777_216;

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    private String email;
    private String website;

    @Lob
    @Column(nullable = false, length = LONG_TEXT)
    private String publicKey;

    /**
     * DEMO ONLY: the issuer's private key is kept server-side so the app can sign on the
     * institution's behalf. In production this would live in the institution's own wallet / HSM.
     */
    @JsonIgnore
    @Lob
    @Column(nullable = false, length = LONG_TEXT)
    private String privateKey;

    @JsonIgnore
    @Column(nullable = false, unique = true)
    private String apiKey;

    private String registrationTxId;
    private Instant createdAt;

    protected Institution() {}

    public Institution(String id, String name, String email, String website,
                       String publicKey, String privateKey, String apiKey) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.website = website;
        this.publicKey = publicKey;
        this.privateKey = privateKey;
        this.apiKey = apiKey;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getWebsite() { return website; }
    public String getPublicKey() { return publicKey; }
    public String getPrivateKey() { return privateKey; }
    public String getApiKey() { return apiKey; }
    public String getRegistrationTxId() { return registrationTxId; }
    public void setRegistrationTxId(String registrationTxId) { this.registrationTxId = registrationTxId; }
    public Instant getCreatedAt() { return createdAt; }
}
