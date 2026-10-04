package com.cecil.attendance.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/** "Please fix my check-in/out times for this day" - approved by a manager or admin. */
@Entity
@Table(name = "correction_requests", indexes = {
        @Index(columnList = "status"),
        @Index(columnList = "employee_id")
})
public class CorrectionRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    @Column(name = "work_date", nullable = false)
    private LocalDate date;

    /** What the record said when the request was made (kept for the audit trail). */
    private LocalTime previousCheckIn;
    private LocalTime previousCheckOut;
    @Column(length = 20)
    private String previousStatus;

    @Column(nullable = false)
    private LocalTime requestedCheckIn;
    private LocalTime requestedCheckOut;

    @Column(nullable = false, length = 300)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RequestStatus status = RequestStatus.PENDING;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(length = 40)
    private String reviewedBy;

    private Instant reviewedAt;

    @Column(length = 300)
    private String reviewComment;

    protected CorrectionRequest() {
    }

    public CorrectionRequest(Employee employee, LocalDate date, AttendanceRecord current,
                             LocalTime requestedCheckIn, LocalTime requestedCheckOut, String reason) {
        this.employee = employee;
        this.date = date;
        if (current != null) {
            this.previousCheckIn = current.getCheckIn();
            this.previousCheckOut = current.getCheckOut();
            this.previousStatus = current.getStatus().name();
        }
        this.requestedCheckIn = requestedCheckIn;
        this.requestedCheckOut = requestedCheckOut;
        this.reason = reason;
    }

    public void review(RequestStatus status, String reviewer, String comment) {
        this.status = status;
        this.reviewedBy = reviewer;
        this.reviewedAt = Instant.now();
        this.reviewComment = comment;
    }

    public Long getId() { return id; }
    public Employee getEmployee() { return employee; }
    public LocalDate getDate() { return date; }
    public LocalTime getPreviousCheckIn() { return previousCheckIn; }
    public LocalTime getPreviousCheckOut() { return previousCheckOut; }
    public String getPreviousStatus() { return previousStatus; }
    public LocalTime getRequestedCheckIn() { return requestedCheckIn; }
    public LocalTime getRequestedCheckOut() { return requestedCheckOut; }
    public String getReason() { return reason; }
    public RequestStatus getStatus() { return status; }
    public void setStatus(RequestStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public String getReviewedBy() { return reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public String getReviewComment() { return reviewComment; }
}
