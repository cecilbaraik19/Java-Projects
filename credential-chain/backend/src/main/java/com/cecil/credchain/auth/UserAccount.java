package com.cecil.credchain.auth;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.time.Instant;

/** A person who can sign in: an ADMIN, or an ISSUER belonging to one institution. */
@Entity
@Table(name = "user_accounts")
public class UserAccount {

    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String name;

    /** PBKDF2 hash ("pbkdf2$iterations$salt$hash") — the plain password is never stored. */
    @JsonIgnore
    @Column(nullable = false, length = 200)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    /** Only for ISSUER accounts. */
    private String institutionId;

    private Instant createdAt;

    protected UserAccount() {}

    public UserAccount(String id, String email, String name, String passwordHash, Role role, String institutionId) {
        this.id = id;
        this.email = email;
        this.name = name;
        this.passwordHash = passwordHash;
        this.role = role;
        this.institutionId = institutionId;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getEmail() { return email; }
    public String getName() { return name; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public Role getRole() { return role; }
    public String getInstitutionId() { return institutionId; }
    public Instant getCreatedAt() { return createdAt; }
}
