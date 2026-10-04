package com.cecil.attendance.controller;

import com.cecil.attendance.dto.Dtos.AttendanceView;
import com.cecil.attendance.dto.Dtos.EmployeeRequest;
import com.cecil.attendance.dto.Dtos.MonthCalendar;
import com.cecil.attendance.model.Employee;
import com.cecil.attendance.security.AccessGuard;
import com.cecil.attendance.service.AttendanceService;
import com.cecil.attendance.service.EmployeeService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

@RestController
@RequestMapping("/api/employees")
public class EmployeeController {

    private final EmployeeService service;
    private final AttendanceService attendanceService;
    private final AccessGuard guard;

    public EmployeeController(EmployeeService service, AttendanceService attendanceService, AccessGuard guard) {
        this.service = service;
        this.attendanceService = attendanceService;
        this.guard = guard;
    }

    /** Admin: everyone. Manager: own department only. */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public List<Employee> list(@RequestParam(defaultValue = "false") boolean activeOnly) {
        return service.findAll(activeOnly, guard.departmentScope());
    }

    @GetMapping("/departments")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public List<String> departments() {
        return service.departments(guard.departmentScope());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public Employee get(@PathVariable Long id) {
        Employee e = service.get(id);
        guard.checkCanManage(e);
        return e;
    }

    @GetMapping("/{id}/attendance")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public List<AttendanceView> history(@PathVariable Long id,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        guard.checkCanManage(service.get(id));
        return attendanceService.history(id, from, to);
    }

    @GetMapping("/{id}/calendar")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public MonthCalendar calendar(@PathVariable Long id, @RequestParam String month) {
        guard.checkCanManage(service.get(id));
        return attendanceService.monthCalendar(id, YearMonth.parse(month));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public Employee create(@Valid @RequestBody EmployeeRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Employee update(@PathVariable Long id, @Valid @RequestBody EmployeeRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
