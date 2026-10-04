package com.cecil.attendance.model;

import jakarta.persistence.*;

import java.time.Instant;

/** A login account. Optionally linked to an Employee (required for MANAGER and EMPLOYEE). */
@Entity
@Table(name = "user_accounts")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String username;

    /** BCrypt hash - the plain password is never stored. */
    @Column(nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @OneToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "employee_id", unique = true)
    private Employee employee;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private int failedAttempts;

    private Instant lockedUntil;

    private Instant lastLoginAt;

    /** Bumped on password change / reset / disable so that older JWTs stop working. */
    @Column(nullable = false)
    private int tokenVersion;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public UserAccount() {
    }

    public UserAccount(String username, String passwordHash, Role role, Employee employee) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.employee = employee;
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public void invalidateTokens() {
        tokenVersion++;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public Employee getEmployee() { return employee; }
    public void setEmployee(Employee employee) { this.employee = employee; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getFailedAttempts() { return failedAttempts; }
    public void setFailedAttempts(int failedAttempts) { this.failedAttempts = failedAttempts; }
    public Instant getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(Instant lockedUntil) { this.lockedUntil = lockedUntil; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public int getTokenVersion() { return tokenVersion; }
    public Instant getCreatedAt() { return createdAt; }
}
