package com.cecil.attendance.model;

import jakarta.persistence.*;

import java.time.Duration;
import java.time.LocalTime;

/** A working shift, e.g. General 09:30-18:00 or Night 22:00-06:00 (crosses midnight). */
@Entity
@Table(name = "shifts")
public class Shift {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String name;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    /** Minutes after the start before a check-in counts as late. */
    @Column(nullable = false)
    private int graceMinutes = 15;

    /** Hours of a normal day; anything above counts as overtime. */
    @Column(nullable = false)
    private double standardHours = 8;

    protected Shift() {
    }

    public Shift(String name, LocalTime startTime, LocalTime endTime, int graceMinutes, double standardHours) {
        this.name = name;
        this.startTime = startTime;
        this.endTime = endTime;
        this.graceMinutes = graceMinutes;
        this.standardHours = standardHours;
    }

    /** True for shifts that end the next day (night shifts). */
    public boolean isOvernight() {
        return !endTime.isAfter(startTime);
    }

    /** Scheduled length of the shift in hours (handles overnight shifts). */
    public double scheduledHours() {
        long m = Duration.between(startTime, endTime).toMinutes();
        if (m <= 0) m += 24 * 60;
        return m / 60.0;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public LocalTime getStartTime() { return startTime; }
    public void setStartTime(LocalTime startTime) { this.startTime = startTime; }
    public LocalTime getEndTime() { return endTime; }
    public void setEndTime(LocalTime endTime) { this.endTime = endTime; }
    public int getGraceMinutes() { return graceMinutes; }
    public void setGraceMinutes(int graceMinutes) { this.graceMinutes = graceMinutes; }
    public double getStandardHours() { return standardHours; }
    public void setStandardHours(double standardHours) { this.standardHours = standardHours; }
}
