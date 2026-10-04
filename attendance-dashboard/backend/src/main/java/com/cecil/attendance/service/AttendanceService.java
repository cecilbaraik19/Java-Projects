package com.cecil.attendance.service;

import com.cecil.attendance.config.AttendanceProperties;
import com.cecil.attendance.dto.Dtos.AttendanceView;
import com.cecil.attendance.dto.Dtos.ManualEntryRequest;
import com.cecil.attendance.exception.ApiException;
import com.cecil.attendance.model.AttendanceRecord;
import com.cecil.attendance.model.AttendanceStatus;
import com.cecil.attendance.model.Employee;
import com.cecil.attendance.repository.AttendanceRepository;
import com.cecil.attendance.repository.EmployeeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
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

    public AttendanceService(AttendanceRepository attendance, EmployeeRepository employees,
                             EmployeeService employeeService, AttendanceProperties props, Clock clock) {
        this.attendance = attendance;
        this.employees = employees;
        this.employeeService = employeeService;
        this.props = props;
        this.clock = clock;
    }

    /** Status decided purely from the check-in time. */
    public AttendanceStatus statusForCheckIn(LocalTime checkIn) {
        return checkIn.isAfter(props.lateAfter()) ? AttendanceStatus.LATE : AttendanceStatus.PRESENT;
    }

    @Transactional
    public AttendanceView checkIn(Long employeeId) {
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
        rec.setStatus(statusForCheckIn(now));
        return AttendanceView.of(attendance.save(rec));
    }

    @Transactional
    public AttendanceView checkOut(Long employeeId) {
        Employee emp = employeeService.get(employeeId);
        LocalDate today = LocalDate.now(clock);
        LocalTime now = LocalTime.now(clock).truncatedTo(ChronoUnit.MINUTES);

        AttendanceRecord rec = attendance.findByEmployeeIdAndDate(employeeId, today)
                .filter(r -> r.getCheckIn() != null)
                .orElseThrow(() -> ApiException.conflict(emp.getFullName() + " has not checked in today"));
        if (rec.getCheckOut() != null) {
            throw ApiException.conflict(emp.getFullName() + " already checked out at " + rec.getCheckOut());
        }
        rec.setCheckOut(now);
        if (rec.getHoursWorked() < props.halfDayHours()) {
            rec.setStatus(AttendanceStatus.HALF_DAY);
        }
        return AttendanceView.of(attendance.save(rec));
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
        if (req.checkIn() != null && req.checkOut() != null && !req.checkOut().isAfter(req.checkIn())) {
            throw ApiException.badRequest("Check-out must be after check-in");
        }
        AttendanceRecord rec = attendance.findByEmployeeIdAndDate(emp.getId(), req.date())
                .orElseGet(() -> new AttendanceRecord(emp, req.date(), req.status()));
        rec.setStatus(req.status());
        rec.setCheckIn(needsTimes ? req.checkIn() : null);
        rec.setCheckOut(needsTimes ? req.checkOut() : null);
        rec.setNote(req.note());
        return AttendanceView.of(attendance.save(rec));
    }

    @Transactional
    public void delete(Long recordId) {
        if (!attendance.existsById(recordId)) throw ApiException.notFound("Record " + recordId + " not found");
        attendance.deleteById(recordId);
    }

    /** Every active employee for the date, with NOT_MARKED rows for anyone without a record. */
    @Transactional(readOnly = true)
    public List<AttendanceView> dailyBoard(LocalDate date) {
        Map<Long, AttendanceRecord> byEmp = attendance.findByDateOrderByCheckInAsc(date).stream()
                .collect(Collectors.toMap(r -> r.getEmployee().getId(), Function.identity()));
        List<AttendanceView> rows = new ArrayList<>();
        for (Employee e : employees.findByActiveTrueOrderByFullNameAsc()) {
            AttendanceRecord r = byEmp.remove(e.getId());
            rows.add(r != null ? AttendanceView.of(r) : AttendanceView.notMarked(e, date));
        }
        // records of employees that were deactivated later still show up
        byEmp.values().forEach(r -> rows.add(AttendanceView.of(r)));
        rows.sort(Comparator.comparing(AttendanceView::employeeName));
        return rows;
    }

    @Transactional(readOnly = true)
    public List<AttendanceView> history(Long employeeId, LocalDate from, LocalDate to) {
        employeeService.get(employeeId);
        return attendance.findByEmployeeIdAndDateBetweenOrderByDateDesc(employeeId, from, to)
                .stream().map(AttendanceView::of).toList();
    }
}
