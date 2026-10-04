package com.cecil.attendance.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "leave_requests", indexes = {
        @Index(columnList = "status"),
        @Index(columnList = "employee_id")
})
public class LeaveRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LeaveType type;

    @Column(nullable = false)
    private LocalDate fromDate;

    @Column(nullable = false)
    private LocalDate toDate;

    /** Working days covered, calculated when the request is made. */
    @Column(nullable = false)
    private int days;

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

    protected LeaveRequest() {
    }

    public LeaveRequest(Employee employee, LeaveType type, LocalDate fromDate, LocalDate toDate, int days, String reason) {
        this.employee = employee;
        this.type = type;
        this.fromDate = fromDate;
        this.toDate = toDate;
        this.days = days;
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
    public LeaveType getType() { return type; }
    public LocalDate getFromDate() { return fromDate; }
    public LocalDate getToDate() { return toDate; }
    public int getDays() { return days; }
    public String getReason() { return reason; }
    public RequestStatus getStatus() { return status; }
    public void setStatus(RequestStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public String getReviewedBy() { return reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public String getReviewComment() { return reviewComment; }
}
