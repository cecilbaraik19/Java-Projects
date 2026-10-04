package com.cecil.attendance.controller;

import com.cecil.attendance.dto.Dtos.AttendanceView;
import com.cecil.attendance.dto.Dtos.CheckInPolicy;
import com.cecil.attendance.dto.Dtos.SelfCheckRequest;
import com.cecil.attendance.dto.Dtos.CorrectionCreateRequest;
import com.cecil.attendance.dto.Dtos.CorrectionView;
import com.cecil.attendance.dto.Dtos.LeaveBalance;
import com.cecil.attendance.dto.Dtos.LeaveCreateRequest;
import com.cecil.attendance.dto.Dtos.LeaveView;
import com.cecil.attendance.dto.Dtos.MonthCalendar;
import com.cecil.attendance.security.AccessGuard;
import com.cecil.attendance.service.AttendanceService;
import com.cecil.attendance.service.CheckInPolicyService;
import com.cecil.attendance.service.CorrectionService;
import com.cecil.attendance.service.LeaveService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
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
    private final CheckInPolicyService policy;
    private final AccessGuard guard;
    private final Clock clock;

    public MeController(AttendanceService attendance, LeaveService leave, CorrectionService corrections,
                        CheckInPolicyService policy, AccessGuard guard, Clock clock) {
        this.attendance = attendance;
        this.leave = leave;
        this.corrections = corrections;
        this.policy = policy;
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

    @GetMapping("/calendar")
    public MonthCalendar myCalendar(@RequestParam String month) {
        return attendance.monthCalendar(guard.requireEmployeeId(), YearMonth.parse(month));
    }

    /** Which proofs (QR code, location) the client must collect before checking in. */
    @GetMapping("/checkin-policy")
    public CheckInPolicy checkInPolicy() {
        return policy.policy();
    }

    @PostMapping("/check-in")
    public AttendanceView checkIn(@Valid @RequestBody(required = false) SelfCheckRequest req) {
        Long empId = guard.requireEmployeeId();
        String proof = policy.verify(empId, req, false);
        return attendance.checkIn(empId, proof);
    }

    @PostMapping("/check-out")
    public AttendanceView checkOut(@Valid @RequestBody(required = false) SelfCheckRequest req) {
        Long empId = guard.requireEmployeeId();
        String proof = policy.verify(empId, req, true);
        return attendance.checkOut(empId, proof);
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
