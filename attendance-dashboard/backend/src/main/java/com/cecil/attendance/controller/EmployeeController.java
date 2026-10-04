package com.cecil.attendance.controller;

import com.cecil.attendance.dto.Dtos.AttendanceView;
import com.cecil.attendance.dto.Dtos.EmployeeRequest;
import com.cecil.attendance.model.Employee;
import com.cecil.attendance.service.AttendanceService;
import com.cecil.attendance.service.EmployeeService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/employees")
public class EmployeeController {

    private final EmployeeService service;
    private final AttendanceService attendanceService;

    public EmployeeController(EmployeeService service, AttendanceService attendanceService) {
        this.service = service;
        this.attendanceService = attendanceService;
    }

    @GetMapping
    public List<Employee> list(@RequestParam(defaultValue = "false") boolean activeOnly) {
        return service.findAll(activeOnly);
    }

    @GetMapping("/departments")
    public List<String> departments() {
        return service.departments();
    }

    @GetMapping("/{id}")
    public Employee get(@PathVariable Long id) {
        return service.get(id);
    }

    @GetMapping("/{id}/attendance")
    public List<AttendanceView> history(@PathVariable Long id,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return attendanceService.history(id, from, to);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Employee create(@Valid @RequestBody EmployeeRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public Employee update(@PathVariable Long id, @Valid @RequestBody EmployeeRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
