package com.cecil.attendance.service;

import com.cecil.attendance.dto.Dtos.CorrectionCreateRequest;
import com.cecil.attendance.dto.Dtos.CorrectionView;
import com.cecil.attendance.exception.ApiException;
import com.cecil.attendance.model.AttendanceRecord;
import com.cecil.attendance.model.AttendanceStatus;
import com.cecil.attendance.model.CorrectionRequest;
import com.cecil.attendance.model.Employee;
import com.cecil.attendance.model.RequestStatus;
import com.cecil.attendance.repository.CorrectionRequestRepository;
import com.cecil.attendance.security.AccessGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@Service
public class CorrectionService {

    private static final int MAX_DAYS_IN_PAST = 30;

    private final CorrectionRequestRepository repo;
    private final EmployeeService employees;
    private final AttendanceService attendance;
    private final AuditService audit;
    private final AccessGuard guard;
    private final Clock clock;
    private final WorkCalendar calendar;

    public CorrectionService(CorrectionRequestRepository repo, EmployeeService employees, AttendanceService attendance,
                             AuditService audit, AccessGuard guard, Clock clock, WorkCalendar calendar) {
        this.calendar = calendar;
        this.repo = repo;
        this.employees = employees;
        this.attendance = attendance;
        this.audit = audit;
        this.guard = guard;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<CorrectionView> mine(Long employeeId) {
        return repo.findByEmployeeIdOrderByCreatedAtDesc(employeeId).stream().map(CorrectionView::of).toList();
    }

    @Transactional
    public CorrectionView request(Long employeeId, CorrectionCreateRequest req) {
        Employee emp = employees.get(employeeId);
        LocalDate today = LocalDate.now(clock);
        if (req.date().isAfter(today)) throw ApiException.badRequest("Cannot correct a future date");
        if (req.date().isBefore(today.minusDays(MAX_DAYS_IN_PAST))) {
            throw ApiException.badRequest("Corrections can only be requested for the last " + MAX_DAYS_IN_PAST + " days");
        }
        if (req.checkOut() != null && !req.checkOut().isAfter(req.checkIn()) && !calendar.rulesFor(emp).overnight()) {
            throw ApiException.badRequest("Check-out must be after check-in");
        }
        if (repo.existsByEmployeeIdAndDateAndStatus(employeeId, req.date(), RequestStatus.PENDING)) {
            throw ApiException.conflict("You already have a pending correction for " + req.date());
        }
        AttendanceRecord current = attendance.findRecord(employeeId, req.date()).orElse(null);
        if (current != null && current.getStatus() == AttendanceStatus.ON_LEAVE) {
            throw ApiException.badRequest("You are on approved leave that day - cancel the leave instead");
        }
        CorrectionRequest saved = repo.save(new CorrectionRequest(emp, req.date(), current,
                req.checkIn(), req.checkOut(), req.reason().trim()));
        audit.log(AuditService.CORRECTION_REQUESTED, "Correction", saved.getId(),
                emp.getEmployeeCode() + " " + req.date() + " -> " + req.checkIn() + "-" + (req.checkOut() == null ? "?" : req.checkOut()));
        return CorrectionView.of(saved);
    }

    @Transactional
    public CorrectionView cancel(Long employeeId, Long id) {
        CorrectionRequest c = get(id);
        if (!c.getEmployee().getId().equals(employeeId)) throw ApiException.notFound("Correction " + id + " not found");
        if (c.getStatus() != RequestStatus.PENDING) throw ApiException.badRequest("Only pending corrections can be cancelled");
        c.setStatus(RequestStatus.CANCELLED);
        audit.log(AuditService.CORRECTION_CANCELLED, "Correction", c.getId(), c.getEmployee().getEmployeeCode() + " " + c.getDate());
        return CorrectionView.of(repo.save(c));
    }

    @Transactional(readOnly = true)
    public List<CorrectionView> forReview(RequestStatus status) {
        String scope = guard.departmentScope();
        List<CorrectionRequest> list = status != null ? repo.findByStatusOrderByCreatedAtAsc(status) : repo.findTop200ByOrderByCreatedAtDesc();
        return list.stream().filter(c -> guard.isReviewable(c.getEmployee(), scope)).map(CorrectionView::of).toList();
    }

    @Transactional
    public CorrectionView review(Long id, boolean approve, String comment) {
        CorrectionRequest c = get(id);
        guard.checkCanReview(c.getEmployee());
        if (c.getStatus() != RequestStatus.PENDING) {
            throw ApiException.conflict("This request is already " + c.getStatus().name().toLowerCase());
        }
        String reviewer = guard.requireUser().username();
        String note = comment == null || comment.isBlank() ? null : comment.trim();
        if (approve) {
            attendance.applyCorrection(c.getEmployee(), c.getDate(), c.getRequestedCheckIn(), c.getRequestedCheckOut(),
                    "Corrected (#" + c.getId() + "): " + c.getReason());
            c.review(RequestStatus.APPROVED, reviewer, note);
        } else {
            if (note == null) throw ApiException.badRequest("Please give a reason when rejecting");
            c.review(RequestStatus.REJECTED, reviewer, note);
        }
        audit.log(approve ? AuditService.CORRECTION_APPROVED : AuditService.CORRECTION_REJECTED, "Correction", c.getId(),
                c.getEmployee().getEmployeeCode() + " " + c.getDate() + " " + c.getRequestedCheckIn() + "-"
                        + (c.getRequestedCheckOut() == null ? "?" : c.getRequestedCheckOut()) + (note != null ? " - " + note : ""));
        return CorrectionView.of(repo.save(c));
    }

    public long pendingCount() {
        return forReview(RequestStatus.PENDING).size();
    }

    private CorrectionRequest get(Long id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("Correction " + id + " not found"));
    }
}
