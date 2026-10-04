package com.cecil.idwallet.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Tamper-evident audit log. Each event stores the hash of the previous event, like a
 * blockchain: editing or deleting any row breaks every hash after it, which
 * AuditService.verifyChain() detects.
 */
@Entity
@Table(name = "audit_events", indexes = @Index(columnList = "userId"))
public class AuditEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long userId;
    @Column(length = 190)
    public String actor;
    @Column(nullable = false, length = 50)
    public String action;
    @Column(length = 500)
    public String detail;
    @Column(length = 64)
    public String ip;
    @Column(name = "event_at", nullable = false)
    public Instant at;
    @Column(nullable = false, length = 64)
    public String prevHash;
    @Column(nullable = false, length = 64)
    public String hash;
}
