package com.cecil.attendance.controller;

import com.cecil.attendance.dto.Dtos.HolidayRequest;
import com.cecil.attendance.dto.Dtos.ShiftRequest;
import com.cecil.attendance.dto.Dtos.ShiftView;
import com.cecil.attendance.model.Holiday;
import com.cecil.attendance.service.WorkRulesService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api")
public class WorkRulesController {

    private final WorkRulesService service;
    private final Clock clock;

    public WorkRulesController(WorkRulesService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    // ---------- holidays: everyone can read, admins edit ----------

    @GetMapping("/holidays")
    public List<Holiday> holidays(@RequestParam(required = false) Integer year) {
        return service.holidays(year != null ? year : LocalDate.now(clock).getYear());
    }

    @PostMapping("/holidays")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public Holiday addHoliday(@Valid @RequestBody HolidayRequest req) {
        return service.addHoliday(req);
    }

    @PutMapping("/holidays/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Holiday updateHoliday(@PathVariable Long id, @Valid @RequestBody HolidayRequest req) {
        return service.updateHoliday(id, req);
    }

    @DeleteMapping("/holidays/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void deleteHoliday(@PathVariable Long id) {
        service.deleteHoliday(id);
    }

    @PostMapping("/holidays/national")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Holiday> addNational(@RequestParam int year) {
        return service.addNationalHolidays(year);
    }

    // ---------- shifts: admins + managers read, admins edit ----------

    @GetMapping("/shifts")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public List<ShiftView> shifts() {
        return service.shifts();
    }

    @PostMapping("/shifts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ShiftView createShift(@Valid @RequestBody ShiftRequest req) {
        return service.createShift(req);
    }

    @PutMapping("/shifts/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ShiftView updateShift(@PathVariable Long id, @Valid @RequestBody ShiftRequest req) {
        return service.updateShift(id, req);
    }

    @DeleteMapping("/shifts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void deleteShift(@PathVariable Long id) {
        service.deleteShift(id);
    }
}
