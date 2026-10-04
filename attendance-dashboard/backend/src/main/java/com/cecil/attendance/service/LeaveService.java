package com.cecil.attendance.service;

import com.cecil.attendance.config.AttendanceProperties;
import com.cecil.attendance.config.LeaveProperties;
import com.cecil.attendance.dto.Dtos.LeaveBalance;
import com.cecil.attendance.dto.Dtos.LeaveCreateRequest;
import com.cecil.attendance.dto.Dtos.LeaveView;
import com.cecil.attendance.exception.ApiException;
import com.cecil.attendance.model.Employee;
import com.cecil.attendance.model.LeaveRequest;
import com.cecil.attendance.model.LeaveType;
import com.cecil.attendance.model.RequestStatus;
import com.cecil.attendance.repository.LeaveRequestRepository;
import com.cecil.attendance.security.AccessGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

@Service
public class LeaveService {

    /** How far back an employee may still apply for leave (e.g. sick leave applied after returning). */
    private static final int MAX_DAYS_IN_PAST = 30;
    private static final int MAX_SPAN_DAYS = 60;

    private final LeaveRequestRepository repo;
    private final EmployeeService employees;
    private final AttendanceService attendance;
    private final AttendanceProperties props;
    private final LeaveProperties leaveProps;
    private final AuditService audit;
    private final AccessGuard guard;
    private final Clock clock;

    public LeaveService(LeaveRequestRepository repo, EmployeeService employees, AttendanceService attendance,
                        AttendanceProperties props, LeaveProperties leaveProps, AuditService audit,
                        AccessGuard guard, Clock clock) {
        this.repo = repo;
        this.employees = employees;
        this.attendance = attendance;
        this.props = props;
        this.leaveProps = leaveProps;
        this.audit = audit;
        this.guard = guard;
        this.clock = clock;
    }

    public List<LocalDate> workingDays(LocalDate from, LocalDate to) {
        return from.datesUntil(to.plusDays(1)).filter(props::isWorkingDay).toList();
    }

    // ---------- employee side ----------

    @Transactional(readOnly = true)
    public List<LeaveBalance> balance(Long employeeId, int year) {
        List<LeaveRequest> mine = repo.findByEmployeeIdAndStatusIn(employeeId,
                EnumSet.of(RequestStatus.PENDING, RequestStatus.APPROVED));
        List<LeaveBalance> out = new ArrayList<>();
        for (LeaveType t : LeaveType.values()) {
            int used = sumDays(mine, t, RequestStatus.APPROVED, year);
            int pending = sumDays(mine, t, RequestStatus.PENDING, year);
            Integer allowance = leaveProps.allowance(t);
            out.add(new LeaveBalance(t.name(), allowance, used, pending,
                    allowance == null ? null : Math.max(0, allowance - used)));
        }
        return out;
    }

    private static int sumDays(List<LeaveRequest> list, LeaveType t, RequestStatus s, int year) {
        return list.stream()
                .filter(l -> l.getType() == t && l.getStatus() == s && l.getFromDate().getYear() == year)
                .mapToInt(LeaveRequest::getDays).sum();
    }

    @Transactional(readOnly = true)
    public List<LeaveView> mine(Long employeeId) {
        return repo.findByEmployeeIdOrderByCreatedAtDesc(employeeId).stream().map(LeaveView::of).toList();
    }

    @Transactional
    public LeaveView apply(Long employeeId, LeaveCreateRequest req) {
        Employee emp = employees.get(employeeId);
        LocalDate today = LocalDate.now(clock);
        if (req.toDate().isBefore(req.fromDate())) throw ApiException.badRequest("'To' date must not be before 'From' date");
        if (req.fromDate().isBefore(today.minusDays(MAX_DAYS_IN_PAST))) {
            throw ApiException.badRequest("Leave can only be applied up to " + MAX_DAYS_IN_PAST + " days in the past");
        }
        if (req.fromDate().plusDays(MAX_SPAN_DAYS).isBefore(req.toDate())) {
            throw ApiException.badRequest("A single leave request can cover at most " + MAX_SPAN_DAYS + " days");
        }
        if (req.fromDate().getYear() != req.toDate().getYear()) {
            throw ApiException.badRequest("Please split leave that crosses into a new year into two requests");
        }
        int days = workingDays(req.fromDate(), req.toDate()).size();
        if (days == 0) throw ApiException.badRequest("The selected dates contain no working days");

        List<LeaveRequest> active = repo.findByEmployeeIdAndStatusIn(employeeId,
                EnumSet.of(RequestStatus.PENDING, RequestStatus.APPROVED));
        for (LeaveRequest l : active) {
            if (!l.getToDate().isBefore(req.fromDate()) && !l.getFromDate().isAfter(req.toDate())) {
                throw ApiException.conflict("Overlaps with your " + l.getStatus().name().toLowerCase()
                        + " leave from " + l.getFromDate() + " to " + l.getToDate());
            }
        }
        checkBalance(employeeId, req.type(), days, req.fromDate().getYear(), true);

        LeaveRequest saved = repo.save(new LeaveRequest(emp, req.type(), req.fromDate(), req.toDate(), days,
                req.reason().trim()));
        audit.log(AuditService.LEAVE_REQUESTED, "Leave", saved.getId(),
                emp.getEmployeeCode() + " " + req.type() + " " + req.fromDate() + " to " + req.toDate() + " (" + days + " d)");
        return LeaveView.of(saved);
    }

    /** @param includePending true when applying (pending requests also reserve days) */
    private void checkBalance(Long employeeId, LeaveType type, int days, int year, boolean includePending) {
        Integer allowance = leaveProps.allowance(type);
        if (allowance == null) return;
        LeaveBalance b = balance(employeeId, year).stream().filter(x -> x.type().equals(type.name())).findFirst().orElseThrow();
        int committed = b.used() + (includePending ? b.pending() : 0);
        if (committed + days > allowance) {
            throw ApiException.badRequest("Not enough " + type.name().toLowerCase() + " leave: " + (allowance - committed)
                    + " day(s) left" + (includePending && b.pending() > 0 ? " after pending requests" : "") + ", requested " + days);
        }
    }

    @Transactional
    public LeaveView cancel(Long employeeId, Long id) {
        LeaveRequest l = get(id);
        if (!l.getEmployee().getId().equals(employeeId)) throw ApiException.notFound("Leave request " + id + " not found");
        LocalDate today = LocalDate.now(clock);
        if (l.getStatus() == RequestStatus.APPROVED) {
            if (!l.getFromDate().isAfter(today)) {
                throw ApiException.badRequest("Leave that has already started cannot be cancelled - ask your manager");
            }
            attendance.clearLeave(employeeId, l.getFromDate(), l.getToDate());
        } else if (l.getStatus() != RequestStatus.PENDING) {
            throw ApiException.badRequest("Only pending or upcoming approved leave can be cancelled");
        }
        l.setStatus(RequestStatus.CANCELLED);
        audit.log(AuditService.LEAVE_CANCELLED, "Leave", l.getId(),
                l.getEmployee().getEmployeeCode() + " " + l.getFromDate() + " to " + l.getToDate());
        return LeaveView.of(repo.save(l));
    }

    // ---------- reviewer side ----------

    /** @param status null = recent requests of any status */
    @Transactional(readOnly = true)
    public List<LeaveView> forReview(RequestStatus status) {
        String scope = guard.departmentScope();
        List<LeaveRequest> list = status != null ? repo.findByStatusOrderByCreatedAtAsc(status) : repo.findTop200ByOrderByCreatedAtDesc();
        return list.stream().filter(l -> guard.isReviewable(l.getEmployee(), scope)).map(LeaveView::of).toList();
    }

    @Transactional
    public LeaveView review(Long id, boolean approve, String comment) {
        LeaveRequest l = get(id);
        guard.checkCanReview(l.getEmployee());
        if (l.getStatus() != RequestStatus.PENDING) {
            throw ApiException.conflict("This request is already " + l.getStatus().name().toLowerCase());
        }
        String reviewer = guard.requireUser().username();
        String note = comment == null || comment.isBlank() ? null : comment.trim();
        if (approve) {
            checkBalance(l.getEmployee().getId(), l.getType(), l.getDays(), l.getFromDate().getYear(), false);
            attendance.markLeave(l.getEmployee(), workingDays(l.getFromDate(), l.getToDate()),
                    label(l.getType()) + " leave (#" + l.getId() + ")");
            l.review(RequestStatus.APPROVED, reviewer, note);
        } else {
            if (note == null) throw ApiException.badRequest("Please give a reason when rejecting");
            l.review(RequestStatus.REJECTED, reviewer, note);
        }
        audit.log(approve ? AuditService.LEAVE_APPROVED : AuditService.LEAVE_REJECTED, "Leave", l.getId(),
                l.getEmployee().getEmployeeCode() + " " + l.getType() + " " + l.getFromDate() + " to " + l.getToDate()
                        + (note != null ? " - " + note : ""));
        return LeaveView.of(repo.save(l));
    }

    public long pendingCount() {
        return forReview(RequestStatus.PENDING).size();
    }

    private LeaveRequest get(Long id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("Leave request " + id + " not found"));
    }

    private static String label(LeaveType t) {
        String n = t.name().toLowerCase();
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
