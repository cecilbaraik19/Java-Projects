package com.cecil.attendance.model;

import jakarta.persistence.*;

import java.time.Instant;

/** Outbox of every alert email: sent via SMTP, or only logged when SMTP isn't configured. */
@Entity
@Table(name = "notifications", indexes = {
        @Index(columnList = "createdAt"),
        @Index(columnList = "recipient")
})
public class Notification {

    public enum Kind { MISSING_CHECKIN, WEEKLY_SUMMARY, LEAVE_REQUEST, LEAVE_DECISION, CORRECTION_DECISION }

    public enum Status { SENT, LOGGED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false, length = 120)
    private String recipient;

    @Column(length = 100)
    private String recipientName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Kind kind;

    @Column(nullable = false, length = 200)
    private String subject;

    @Column(nullable = false, length = 4000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(length = 300)
    private String error;

    protected Notification() {
    }

    public Notification(String recipient, String recipientName, Kind kind, String subject, String body) {
        this.recipient = recipient;
        this.recipientName = recipientName;
        this.kind = kind;
        this.subject = subject;
        this.body = body;
    }

    public Long getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
    public String getRecipient() { return recipient; }
    public String getRecipientName() { return recipientName; }
    public Kind getKind() { return kind; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
