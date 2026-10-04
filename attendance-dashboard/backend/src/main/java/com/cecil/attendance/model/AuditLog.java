package com.cecil.attendance.model;

import jakarta.persistence.*;

import java.time.Instant;

/** Append-only record of who did what, and when. */
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(columnList = "timestamp"),
        @Index(columnList = "username")
})
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(nullable = false, length = 40)
    private String username;

    @Column(nullable = false, length = 40)
    private String action;

    @Column(length = 40)
    private String entityType;

    @Column(length = 40)
    private String entityId;

    @Column(length = 500)
    private String details;

    @Column(length = 64)
    private String ipAddress;

    protected AuditLog() {
    }

    public AuditLog(Instant timestamp, String username, String action, String entityType,
                    String entityId, String details, String ipAddress) {
        this.timestamp = timestamp;
        this.username = username;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.details = details;
        this.ipAddress = ipAddress;
    }

    public Long getId() { return id; }
    public Instant getTimestamp() { return timestamp; }
    public String getUsername() { return username; }
    public String getAction() { return action; }
    public String getEntityType() { return entityType; }
    public String getEntityId() { return entityId; }
    public String getDetails() { return details; }
    public String getIpAddress() { return ipAddress; }
}
