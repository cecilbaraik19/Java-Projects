package com.cecil.attendance.service;

import com.cecil.attendance.dto.Dtos.EmployeeRequest;
import com.cecil.attendance.exception.ApiException;
import com.cecil.attendance.model.Employee;
import com.cecil.attendance.repository.AttendanceRepository;
import com.cecil.attendance.repository.EmployeeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class EmployeeService {

    private final EmployeeRepository employees;
    private final AttendanceRepository attendance;

    public EmployeeService(EmployeeRepository employees, AttendanceRepository attendance) {
        this.employees = employees;
        this.attendance = attendance;
    }

    public List<Employee> findAll(boolean activeOnly) {
        return activeOnly ? employees.findByActiveTrueOrderByFullNameAsc() : employees.findAllByOrderByFullNameAsc();
    }

    public Employee get(Long id) {
        return employees.findById(id).orElseThrow(() -> ApiException.notFound("Employee " + id + " not found"));
    }

    public List<String> departments() {
        return employees.findDistinctDepartments();
    }

    @Transactional
    public Employee create(EmployeeRequest req) {
        if (employees.existsByEmployeeCodeIgnoreCase(req.employeeCode().trim())) {
            throw ApiException.conflict("Employee code " + req.employeeCode() + " already exists");
        }
        if (employees.existsByEmailIgnoreCase(req.email().trim())) {
            throw ApiException.conflict("Email " + req.email() + " already exists");
        }
        Employee e = new Employee();
        apply(e, req);
        return employees.save(e);
    }

    @Transactional
    public Employee update(Long id, EmployeeRequest req) {
        Employee e = get(id);
        if (!e.getEmployeeCode().equalsIgnoreCase(req.employeeCode().trim())
                && employees.existsByEmployeeCodeIgnoreCase(req.employeeCode().trim())) {
            throw ApiException.conflict("Employee code " + req.employeeCode() + " already exists");
        }
        if (!e.getEmail().equalsIgnoreCase(req.email().trim())
                && employees.existsByEmailIgnoreCase(req.email().trim())) {
            throw ApiException.conflict("Email " + req.email() + " already exists");
        }
        apply(e, req);
        return employees.save(e);
    }

    @Transactional
    public void delete(Long id) {
        Employee e = get(id);
        attendance.deleteByEmployeeId(e.getId());
        employees.delete(e);
    }

    private void apply(Employee e, EmployeeRequest req) {
        e.setEmployeeCode(req.employeeCode().trim().toUpperCase());
        e.setFullName(req.fullName().trim());
        e.setEmail(req.email().trim().toLowerCase());
        e.setDepartment(req.department().trim());
        e.setDesignation(req.designation() == null ? null : req.designation().trim());
        e.setJoinDate(req.joinDate());
        if (req.active() != null) e.setActive(req.active());
    }
}
