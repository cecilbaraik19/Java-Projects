package com.cecil.attendance.service;

import com.cecil.attendance.dto.Dtos.EmployeeRequest;
import com.cecil.attendance.exception.ApiException;
import com.cecil.attendance.model.Employee;
import com.cecil.attendance.repository.AttendanceRepository;
import com.cecil.attendance.repository.EmployeeRepository;
import com.cecil.attendance.repository.UserAccountRepository;
import com.cecil.attendance.security.AccessGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class EmployeeService {

    private final EmployeeRepository employees;
    private final AttendanceRepository attendance;
    private final UserAccountRepository users;
    private final AuditService audit;

    public EmployeeService(EmployeeRepository employees, AttendanceRepository attendance,
                           UserAccountRepository users, AuditService audit) {
        this.employees = employees;
        this.attendance = attendance;
        this.users = users;
        this.audit = audit;
    }

    /** @param department null = all departments, otherwise only that department */
    public List<Employee> findAll(boolean activeOnly, String department) {
        List<Employee> list = activeOnly ? employees.findByActiveTrueOrderByFullNameAsc()
                : employees.findAllByOrderByFullNameAsc();
        return list.stream().filter(e -> AccessGuard.inScope(e, department)).toList();
    }

    public Employee get(Long id) {
        return employees.findById(id).orElseThrow(() -> ApiException.notFound("Employee " + id + " not found"));
    }

    public List<String> departments(String department) {
        if (department != null) return AccessGuard.NO_DEPARTMENT.equals(department) ? List.of() : List.of(department);
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
        Employee saved = employees.save(e);
        audit.log(AuditService.EMPLOYEE_CREATED, "Employee", saved.getId(),
                saved.getEmployeeCode() + " " + saved.getFullName());
        return saved;
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
        Employee saved = employees.save(e);
        audit.log(AuditService.EMPLOYEE_UPDATED, "Employee", saved.getId(),
                saved.getEmployeeCode() + " " + saved.getFullName() + (saved.isActive() ? "" : " (inactive)"));
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        Employee e = get(id);
        // keep the login account but unlink it, so the admin can re-link or delete it
        users.findByEmployeeId(e.getId()).ifPresent(u -> {
            u.setEmployee(null);
            u.setEnabled(false);
            u.invalidateTokens();
            users.save(u);
        });
        attendance.deleteByEmployeeId(e.getId());
        employees.delete(e);
        audit.log(AuditService.EMPLOYEE_DELETED, "Employee", id, e.getEmployeeCode() + " " + e.getFullName());
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
