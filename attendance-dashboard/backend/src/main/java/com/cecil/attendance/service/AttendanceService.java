package com.cecil.attendance.service;

import com.cecil.attendance.config.AttendanceProperties;
import com.cecil.attendance.dto.Dtos.AttendanceView;
import com.cecil.attendance.dto.Dtos.CalendarDay;
import com.cecil.attendance.dto.Dtos.MonthCalendar;
import com.cecil.attendance.dto.Dtos.ManualEntryRequest;
import com.cecil.attendance.exception.ApiException;
import com.cecil.attendance.model.AttendanceRecord;
import com.cecil.attendance.model.AttendanceStatus;
import com.cecil.attendance.model.Employee;
import com.cecil.attendance.repository.AttendanceRepository;
import com.cecil.attendance.repository.EmployeeRepository;
import com.cecil.attendance.security.AccessGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AttendanceService {

    private final AttendanceRepository attendance;
    private final EmployeeRepository employees;
    private final EmployeeService employeeService;
    private final AttendanceProperties props;
    private final Clock clock;
    private final AuditService audit;
    private final WorkCalendar calendar;

    public AttendanceService(AttendanceRepository attendance, EmployeeRepository employees,
                             EmployeeService employeeService, AttendanceProperties props, Clock clock,
                             AuditService audit, WorkCalendar calendar) {
        this.attendance = attendance;
        this.employees = employees;
        this.employeeService = employeeService;
        this.props = props;
        this.clock = clock;
        this.audit = audit;
        this.calendar = calendar;
    }

    /** PRESENT or LATE, judged against the employee's own shift start + grace period. */
    public AttendanceStatus statusForCheckIn(Employee emp, LocalTime checkIn) {
        WorkCalendar.ShiftRules rules = calendar.rulesFor(emp);
        boolean late = checkIn.isAfter(rules.lateAfter());
        if (rules.overnight() && checkIn.isBefore(rules.end())) {
            late = true; // night shift: arriving after midnight is late
        }
        return late ? AttendanceStatus.LATE : AttendanceStatus.PRESENT;
    }

    /** View with overtime calculated from the employee's shift and the holiday calendar. */
    public AttendanceView view(AttendanceRecord r) {
        return AttendanceView.of(r, calendar.overtimeHours(r));
    }

    private boolean checkOutMayBeBeforeCheckIn(Employee emp) {
        return calendar.rulesFor(emp).overnight();
    }

    @Transactional
    public AttendanceView checkIn(Long employeeId) {
        return checkIn(employeeId, null);
    }

    /** @param verification how a self check-in was verified (null when marked by admin/manager) */
    @Transactional
    public AttendanceView checkIn(Long employeeId, String verification) {
        Employee emp = employeeService.get(employeeId);
        if (!emp.isActive()) throw ApiException.badRequest(emp.getFullName() + " is inactive");

        LocalDate today = LocalDate.now(clock);
        LocalTime now = LocalTime.now(clock).truncatedTo(ChronoUnit.MINUTES);

        AttendanceRecord rec = attendance.findByEmployeeIdAndDate(employeeId, today)
                .orElseGet(() -> new AttendanceRecord(emp, today, AttendanceStatus.PRESENT));
        if (rec.getCheckIn() != null) {
            throw ApiException.conflict(emp.getFullName() + " already checked in at " + rec.getCheckIn());
        }
        if (rec.getStatus() == AttendanceStatus.ON_LEAVE) {
            throw ApiException.conflict(emp.getFullName() + " is on leave today");
        }
        rec.setCheckIn(now);
        rec.setStatus(statusForCheckIn(emp, now));
        rec.setCheckInVerification(verification != null ? verification : markedBy());
        AttendanceRecord saved = attendance.save(rec);
        audit.log(AuditService.CHECK_IN, "Attendance", saved.getId(),
                emp.getEmployeeCode() + " at " + now + " (" + saved.getStatus() + ")");
        return view(saved);
    }

    @Transactional
    public AttendanceView checkOut(Long employeeId) {
        return checkOut(employeeId, null);
    }

    @Transactional
    public AttendanceView checkOut(Long employeeId, String verification) {
        Employee emp = employeeService.get(employeeId);
        LocalDate today = LocalDate.now(clock);
        LocalTime now = LocalTime.now(clock).truncatedTo(ChronoUnit.MINUTES);

        AttendanceRecord rec = attendance.findByEmployeeIdAndDate(employeeId, today)
                .filter(r -> r.getCheckIn() != null)
                .orElse(null);
        // Night shift: checking out after midnight closes yesterday's record
        if ((rec == null || rec.getCheckOut() != null) && calendar.rulesFor(emp).overnight()) {
            AttendanceRecord y = attendance.findByEmployeeIdAndDate(employeeId, today.minusDays(1))
                    .filter(r -> r.getCheckIn() != null && r.getCheckOut() == null).orElse(null);
            if (y != null) rec = y;
        }
        if (rec == null) throw ApiException.conflict(emp.getFullName() + " has not checked in today");
        if (rec.getCheckOut() != null) {
            throw ApiException.conflict(emp.getFullName() + " already checked out at " + rec.getCheckOut());
        }
        rec.setCheckOut(now);
        if (rec.getHoursWorked() < props.halfDayHours()) {
            rec.setStatus(AttendanceStatus.HALF_DAY);
        }
        rec.setCheckOutVerification(verification != null ? verification : markedBy());
        AttendanceRecord saved = attendance.save(rec);
        audit.log(AuditService.CHECK_OUT, "Attendance", saved.getId(),
                emp.getEmployeeCode() + " at " + now + " (" + saved.getHoursWorked() + " h)");
        return view(saved);
    }

    /** "Marked by manager" style label for attendance entered on someone's behalf. */
    private static String markedBy() {
        return AccessGuard.currentUser().map(u -> "Marked by " + u.username()).orElse(null);
    }

    /** Admin create-or-update for any date (corrections, leave, marking absent). */
    @Transactional
    public AttendanceView saveManual(ManualEntryRequest req) {
        Employee emp = employeeService.get(req.employeeId());
        if (req.date().isAfter(LocalDate.now(clock))) {
            throw ApiException.badRequest("Cannot record attendance for a future date");
        }
        boolean needsTimes = req.status().attended();
        if (needsTimes && req.checkIn() == null) {
            throw ApiException.badRequest("Check-in time is required for " + req.status());
        }
        if (req.checkIn() != null && req.checkOut() != null && !req.checkOut().isAfter(req.checkIn())
                && !checkOutMayBeBeforeCheckIn(emp)) {
            throw ApiException.badRequest("Check-out must be after check-in");
        }
        AttendanceRecord rec = attendance.findByEmployeeIdAndDate(emp.getId(), req.date())
                .orElseGet(() -> new AttendanceRecord(emp, req.date(), req.status()));
        rec.setStatus(req.status());
        rec.setCheckIn(needsTimes ? req.checkIn() : null);
        rec.setCheckOut(needsTimes ? req.checkOut() : null);
        rec.setNote(req.note());
        AttendanceRecord saved = attendance.save(rec);
        audit.log(AuditService.ATTENDANCE_EDITED, "Attendance", saved.getId(),
                emp.getEmployeeCode() + " " + req.date() + " -> " + req.status()
                        + (needsTimes ? " " + req.checkIn() + "-" + (req.checkOut() == null ? "?" : req.checkOut()) : ""));
        return view(saved);
    }

    public AttendanceRecord getRecord(Long recordId) {
        return attendance.findById(recordId)
                .orElseThrow(() -> ApiException.notFound("Record " + recordId + " not found"));
    }

    @Transactional
    public void delete(Long recordId) {
        AttendanceRecord rec = getRecord(recordId);
        attendance.delete(rec);
        audit.log(AuditService.ATTENDANCE_DELETED, "Attendance", recordId,
                rec.getEmployee().getEmployeeCode() + " " + rec.getDate() + " (" + rec.getStatus() + ")");
    }

    /**
     * Every active employee for the date, with NOT_MARKED rows for anyone without a record.
     * @param department null = all departments
     */
    @Transactional(readOnly = true)
    public List<AttendanceView> dailyBoard(LocalDate date, String department) {
        Map<Long, AttendanceRecord> byEmp = attendance.findByDateOrderByCheckInAsc(date).stream()
                .filter(r -> AccessGuard.inScope(r.getEmployee(), department))
                .collect(Collectors.toMap(r -> r.getEmployee().getId(), Function.identity()));
        List<AttendanceView> rows = new ArrayList<>();
        for (Employee e : employees.findByActiveTrueOrderByFullNameAsc()) {
            if (!AccessGuard.inScope(e, department)) continue;
            AttendanceRecord r = byEmp.remove(e.getId());
            rows.add(r != null ? view(r) : AttendanceView.notMarked(e, date));
        }
        // records of employees that were deactivated later still show up
        byEmp.values().forEach(r -> rows.add(view(r)));
        rows.sort(Comparator.comparing(AttendanceView::employeeName));
        return rows;
    }

    /**
     * Marks the given days as ON_LEAVE (used when a leave request is approved).
     * Fails if the employee already checked in on any of those days.
     */
    @Transactional
    public void markLeave(Employee emp, List<LocalDate> days, String note) {
        List<AttendanceRecord> toSave = new ArrayList<>();
        for (LocalDate d : days) {
            AttendanceRecord rec = attendance.findByEmployeeIdAndDate(emp.getId(), d)
                    .orElseGet(() -> new AttendanceRecord(emp, d, AttendanceStatus.ON_LEAVE));
            if (rec.getCheckIn() != null) {
                throw ApiException.conflict(emp.getFullName() + " already attended on " + d
                        + " - edit that day first or change the leave dates");
            }
            rec.setStatus(AttendanceStatus.ON_LEAVE);
            rec.setCheckIn(null);
            rec.setCheckOut(null);
            rec.setNote(note);
            toSave.add(rec);
        }
        attendance.saveAll(toSave);
    }

    /** Removes ON_LEAVE records in a date range (used when an approved leave is cancelled). */
    @Transactional
    public void clearLeave(Long employeeId, LocalDate from, LocalDate to) {
        List<AttendanceRecord> leave = attendance.findByEmployeeIdAndDateBetweenOrderByDateDesc(employeeId, from, to)
                .stream().filter(r -> r.getStatus() == AttendanceStatus.ON_LEAVE).toList();
        attendance.deleteAll(leave);
    }

    /** Sets the times for a day from an approved correction request; status follows the normal rules. */
    @Transactional
    public AttendanceView applyCorrection(Employee emp, LocalDate date, LocalTime checkIn, LocalTime checkOut, String note) {
        AttendanceRecord rec = attendance.findByEmployeeIdAndDate(emp.getId(), date)
                .orElseGet(() -> new AttendanceRecord(emp, date, AttendanceStatus.PRESENT));
        rec.setCheckIn(checkIn);
        rec.setCheckOut(checkOut);
        rec.setStatus(statusForCheckIn(emp, checkIn));
        if (checkOut != null && rec.getHoursWorked() < props.halfDayHours()) {
            rec.setStatus(AttendanceStatus.HALF_DAY);
        }
        rec.setNote(note);
        return view(attendance.save(rec));
    }

    public java.util.Optional<AttendanceRecord> findRecord(Long employeeId, LocalDate date) {
        return attendance.findByEmployeeIdAndDate(employeeId, date);
    }

    /** One employee's month, day by day, including holidays, weekends and days with no record. */
    @Transactional(readOnly = true)
    public MonthCalendar monthCalendar(Long employeeId, YearMonth month) {
        Employee emp = employeeService.get(employeeId);
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();
        LocalDate today = LocalDate.now(clock);
        Map<LocalDate, AttendanceRecord> recs = attendance
                .findByEmployeeIdAndDateBetweenOrderByDateDesc(employeeId, first, last).stream()
                .collect(Collectors.toMap(AttendanceRecord::getDate, Function.identity(), (a, b) -> a));
        List<CalendarDay> days = new ArrayList<>();
        Map<String, Long> totals = new java.util.LinkedHashMap<>();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            AttendanceRecord r = recs.get(d);
            String holiday = calendar.holidayName(d);
            String status;
            if (r != null) status = r.getStatus().name();
            else if (holiday != null) status = "HOLIDAY";
            else if (!props.isWorkingDay(d)) status = "WEEKEND";
            else if (d.isAfter(today)) status = "FUTURE";
            else if (emp.getJoinDate() != null && d.isBefore(emp.getJoinDate())) status = "NONE";
            else if (d.equals(today)) status = "NOT_MARKED";
            else status = "ABSENT";
            totals.merge(status, 1L, Long::sum);
            days.add(new CalendarDay(d, status,
                    r != null ? r.getCheckIn() : null, r != null ? r.getCheckOut() : null,
                    r != null ? r.getHoursWorked() : 0, r != null ? calendar.overtimeHours(r) : 0,
                    holiday, r != null ? r.getNote() : null));
        }
        return new MonthCalendar(emp.getId(), emp.getFullName(), month.toString(), days, totals);
    }

    @Transactional(readOnly = true)
    public List<AttendanceView> history(Long employeeId, LocalDate from, LocalDate to) {
        employeeService.get(employeeId);
        return attendance.findByEmployeeIdAndDateBetweenOrderByDateDesc(employeeId, from, to)
                .stream().map(this::view).toList();
    }
}
