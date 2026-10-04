package com.cecil.attendance.controller;

import com.cecil.attendance.dto.Dtos.AttendanceView;
import com.cecil.attendance.exception.ApiException;
import com.cecil.attendance.security.AccessGuard;
import com.cecil.attendance.security.CurrentUser;
import com.cecil.attendance.service.AttendanceService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** Endpoints about the logged-in user's own data (any role). */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final AttendanceService attendance;
    private final AccessGuard guard;

    public MeController(AttendanceService attendance, AccessGuard guard) {
        this.attendance = attendance;
        this.guard = guard;
    }

    @GetMapping("/attendance")
    public List<AttendanceView> myAttendance(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        CurrentUser u = guard.requireUser();
        if (u.employeeId() == null) {
            throw ApiException.badRequest("Your account is not linked to an employee record");
        }
        return attendance.history(u.employeeId(), from, to);
    }
}
