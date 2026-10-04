package com.cecil.attendance.model;

import jakarta.persistence.*;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(name = "attendance_records",
        uniqueConstraints = @UniqueConstraint(columnNames = {"employee_id", "work_date"}),
        indexes = @Index(columnList = "work_date"))
public class AttendanceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    @Column(name = "work_date", nullable = false)
    private LocalDate date;

    private LocalTime checkIn;
    private LocalTime checkOut;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AttendanceStatus status;

    @Column(length = 255)
    private String note;

    public AttendanceRecord() {
    }

    public AttendanceRecord(Employee employee, LocalDate date, AttendanceStatus status) {
        this.employee = employee;
        this.date = date;
        this.status = status;
    }

    /** Hours worked, or 0 if not checked out yet. */
    public double getHoursWorked() {
        if (checkIn == null || checkOut == null) return 0;
        long minutes = Duration.between(checkIn, checkOut).toMinutes();
        return Math.max(0, Math.round(minutes / 6.0) / 10.0);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Employee getEmployee() { return employee; }
    public void setEmployee(Employee employee) { this.employee = employee; }
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }
    public LocalTime getCheckIn() { return checkIn; }
    public void setCheckIn(LocalTime checkIn) { this.checkIn = checkIn; }
    public LocalTime getCheckOut() { return checkOut; }
    public void setCheckOut(LocalTime checkOut) { this.checkOut = checkOut; }
    public AttendanceStatus getStatus() { return status; }
    public void setStatus(AttendanceStatus status) { this.status = status; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
