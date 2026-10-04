package com.cecil.idwallet.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** Every time someone opens a share link, the holder can see who, when and the outcome. */
@Entity
@Table(name = "access_logs", indexes = @Index(columnList = "shareId"))
public class AccessLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false)
    public Long shareId;
    @Column(name = "event_at", nullable = false)
    public Instant at = Instant.now();
    @Column(length = 64)
    public String ip;
    @Column(length = 300)
    public String userAgent;
    @Column(nullable = false, length = 30)
    public String outcome;
}
