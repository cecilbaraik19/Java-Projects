package com.cecil.attendance.model;

import jakarta.persistence.*;

import java.time.LocalDate;

@Entity
@Table(name = "employees")
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String employeeCode;

    @Column(nullable = false, length = 100)
    private String fullName;

    @Column(nullable = false, unique = true, length = 120)
    private String email;

    @Column(nullable = false, length = 60)
    private String department;

    @Column(length = 80)
    private String designation;

    private LocalDate joinDate;

    @Column(nullable = false)
    private boolean active = true;

    /** Null = the default office hours from application.properties. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "shift_id")
    private Shift shift;

    public Employee() {
    }

    public Employee(String employeeCode, String fullName, String email, String department,
                    String designation, LocalDate joinDate) {
        this.employeeCode = employeeCode;
        this.fullName = fullName;
        this.email = email;
        this.department = department;
        this.designation = designation;
        this.joinDate = joinDate;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEmployeeCode() { return employeeCode; }
    public void setEmployeeCode(String employeeCode) { this.employeeCode = employeeCode; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }
    public String getDesignation() { return designation; }
    public void setDesignation(String designation) { this.designation = designation; }
    public LocalDate getJoinDate() { return joinDate; }
    public void setJoinDate(LocalDate joinDate) { this.joinDate = joinDate; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Shift getShift() { return shift; }
    public void setShift(Shift shift) { this.shift = shift; }
}
