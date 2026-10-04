package com.cecil.attendance.controller;

import com.cecil.attendance.dto.Dtos.AttendanceView;
import com.cecil.attendance.dto.Dtos.CorrectionCreateRequest;
import com.cecil.attendance.dto.Dtos.CorrectionView;
import com.cecil.attendance.dto.Dtos.LeaveBalance;
import com.cecil.attendance.dto.Dtos.LeaveCreateRequest;
import com.cecil.attendance.dto.Dtos.LeaveView;
import com.cecil.attendance.security.AccessGuard;
import com.cecil.attendance.service.AttendanceService;
import com.cecil.attendance.service.CorrectionService;
import com.cecil.attendance.service.LeaveService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Self-service endpoints: everything here acts on the logged-in user's own employee record,
 * so the employee id is never taken from the request (no IDOR).
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final AttendanceService attendance;
    private final LeaveService leave;
    private final CorrectionService corrections;
    private final AccessGuard guard;
    private final Clock clock;

    public MeController(AttendanceService attendance, LeaveService leave, CorrectionService corrections,
                        AccessGuard guard, Clock clock) {
        this.attendance = attendance;
        this.leave = leave;
        this.corrections = corrections;
        this.guard = guard;
        this.clock = clock;
    }

    // ---------- attendance ----------

    @GetMapping("/attendance")
    public List<AttendanceView> myAttendance(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return attendance.history(guard.requireEmployeeId(), from, to);
    }

    @PostMapping("/check-in")
    public AttendanceView checkIn() {
        return attendance.checkIn(guard.requireEmployeeId());
    }

    @PostMapping("/check-out")
    public AttendanceView checkOut() {
        return attendance.checkOut(guard.requireEmployeeId());
    }

    // ---------- leave ----------

    @GetMapping("/leave-balance")
    public List<LeaveBalance> leaveBalance(@RequestParam(required = false) Integer year) {
        return leave.balance(guard.requireEmployeeId(), year != null ? year : LocalDate.now(clock).getYear());
    }

    @GetMapping("/leave-requests")
    public List<LeaveView> myLeave() {
        return leave.mine(guard.requireEmployeeId());
    }

    @PostMapping("/leave-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public LeaveView applyLeave(@Valid @RequestBody LeaveCreateRequest req) {
        return leave.apply(guard.requireEmployeeId(), req);
    }

    @PostMapping("/leave-requests/{id}/cancel")
    public LeaveView cancelLeave(@PathVariable Long id) {
        return leave.cancel(guard.requireEmployeeId(), id);
    }

    // ---------- corrections ----------

    @GetMapping("/corrections")
    public List<CorrectionView> myCorrections() {
        return corrections.mine(guard.requireEmployeeId());
    }

    @PostMapping("/corrections")
    @ResponseStatus(HttpStatus.CREATED)
    public CorrectionView requestCorrection(@Valid @RequestBody CorrectionCreateRequest req) {
        return corrections.request(guard.requireEmployeeId(), req);
    }

    @PostMapping("/corrections/{id}/cancel")
    public CorrectionView cancelCorrection(@PathVariable Long id) {
        return corrections.cancel(guard.requireEmployeeId(), id);
    }
}
