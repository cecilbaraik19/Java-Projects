package com.cecil.attendance.controller;

import com.cecil.attendance.dto.Dtos.AttendanceView;
import com.cecil.attendance.dto.Dtos.CheckRequest;
import com.cecil.attendance.dto.Dtos.ManualEntryRequest;
import com.cecil.attendance.service.AttendanceService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/attendance")
public class AttendanceController {

    private final AttendanceService service;
    private final Clock clock;

    public AttendanceController(AttendanceService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    /** Daily board: all active employees for the date (defaults to today). */
    @GetMapping
    public List<AttendanceView> daily(@RequestParam(required = false)
                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.dailyBoard(date != null ? date : LocalDate.now(clock));
    }

    @PostMapping("/check-in")
    public AttendanceView checkIn(@Valid @RequestBody CheckRequest req) {
        return service.checkIn(req.employeeId());
    }

    @PostMapping("/check-out")
    public AttendanceView checkOut(@Valid @RequestBody CheckRequest req) {
        return service.checkOut(req.employeeId());
    }

    /** Create or correct a record for any past/today date. */
    @PutMapping
    public AttendanceView save(@Valid @RequestBody ManualEntryRequest req) {
        return service.saveManual(req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
