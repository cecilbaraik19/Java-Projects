package com.cecil.attendance.model;

import jakarta.persistence.*;

import java.time.LocalDate;

/** A company holiday: not a working day, so it never counts as absence or uses up leave. */
@Entity
@Table(name = "holidays")
public class Holiday {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "holiday_date", nullable = false, unique = true)
    private LocalDate date;

    @Column(nullable = false, length = 80)
    private String name;

    protected Holiday() {
    }

    public Holiday(LocalDate date, String name) {
        this.date = date;
        this.name = name;
    }

    public Long getId() { return id; }
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
