package com.cecil.attendance.service;

import com.cecil.attendance.dto.Dtos.HolidayRequest;
import com.cecil.attendance.dto.Dtos.ShiftRequest;
import com.cecil.attendance.dto.Dtos.ShiftView;
import com.cecil.attendance.exception.ApiException;
import com.cecil.attendance.model.Employee;
import com.cecil.attendance.model.Holiday;
import com.cecil.attendance.model.Shift;
import com.cecil.attendance.repository.EmployeeRepository;
import com.cecil.attendance.repository.HolidayRepository;
import com.cecil.attendance.repository.ShiftRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Admin management of holidays and shifts. */
@Service
public class WorkRulesService {

    /** India's fixed-date national holidays (festival dates change every year - add those yourself). */
    private static final Map<MonthDay, String> NATIONAL = new LinkedHashMap<>();

    static {
        NATIONAL.put(MonthDay.of(1, 26), "Republic Day");
        NATIONAL.put(MonthDay.of(8, 15), "Independence Day");
        NATIONAL.put(MonthDay.of(10, 2), "Gandhi Jayanti");
        NATIONAL.put(MonthDay.of(12, 25), "Christmas");
    }

    private final HolidayRepository holidays;
    private final ShiftRepository shifts;
    private final EmployeeRepository employees;
    private final WorkCalendar calendar;
    private final AuditService audit;

    public WorkRulesService(HolidayRepository holidays, ShiftRepository shifts, EmployeeRepository employees,
                            WorkCalendar calendar, AuditService audit) {
        this.holidays = holidays;
        this.shifts = shifts;
        this.employees = employees;
        this.calendar = calendar;
        this.audit = audit;
    }

    // ---------- holidays ----------
    // Not @Transactional: each save commits at once, so refreshing the calendar cache afterwards is safe.

    public List<Holiday> holidays(int year) {
        return holidays.findByDateBetweenOrderByDateAsc(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
    }

    public Holiday addHoliday(HolidayRequest req) {
        if (holidays.existsByDate(req.date())) {
            throw ApiException.conflict(req.date() + " is already a holiday");
        }
        Holiday h = holidays.save(new Holiday(req.date(), req.name().trim()));
        calendar.refresh();
        audit.log(AuditService.HOLIDAY_ADDED, "Holiday", h.getId(), h.getDate() + " " + h.getName());
        return h;
    }

    public Holiday updateHoliday(Long id, HolidayRequest req) {
        Holiday h = holidays.findById(id).orElseThrow(() -> ApiException.notFound("Holiday " + id + " not found"));
        holidays.findByDate(req.date()).filter(o -> !o.getId().equals(id)).ifPresent(o -> {
            throw ApiException.conflict(req.date() + " is already a holiday (" + o.getName() + ")");
        });
        h.setDate(req.date());
        h.setName(req.name().trim());
        Holiday saved = holidays.save(h);
        calendar.refresh();
        audit.log(AuditService.HOLIDAY_UPDATED, "Holiday", id, saved.getDate() + " " + saved.getName());
        return saved;
    }

    public void deleteHoliday(Long id) {
        Holiday h = holidays.findById(id).orElseThrow(() -> ApiException.notFound("Holiday " + id + " not found"));
        holidays.delete(h);
        calendar.refresh();
        audit.log(AuditService.HOLIDAY_DELETED, "Holiday", id, h.getDate() + " " + h.getName());
    }

    /** Adds the fixed national holidays for a year; skips dates that already exist. */
    public List<Holiday> addNationalHolidays(int year) {
        if (year < 2000 || year > 2100) throw ApiException.badRequest("Year must be between 2000 and 2100");
        List<Holiday> added = new ArrayList<>();
        NATIONAL.forEach((md, name) -> {
            LocalDate d = md.atYear(year);
            if (!holidays.existsByDate(d)) added.add(holidays.save(new Holiday(d, name)));
        });
        calendar.refresh();
        if (!added.isEmpty()) {
            audit.log(AuditService.HOLIDAY_ADDED, "Holiday", null,
                    added.size() + " national holidays for " + year);
        }
        return added;
    }

    // ---------- shifts ----------

    public List<ShiftView> shifts() {
        Map<Long, Long> counts = employees.findAll().stream()
                .filter(e -> e.getShift() != null)
                .collect(Collectors.groupingBy(e -> e.getShift().getId(), Collectors.counting()));
        return shifts.findAllByOrderByStartTimeAsc().stream()
                .map(s -> ShiftView.of(s, counts.getOrDefault(s.getId(), 0L))).toList();
    }

    @Transactional
    public ShiftView createShift(ShiftRequest req) {
        if (shifts.existsByNameIgnoreCase(req.name().trim())) {
            throw ApiException.conflict("A shift called '" + req.name().trim() + "' already exists");
        }
        validate(req);
        Shift s = shifts.save(new Shift(req.name().trim(), req.startTime(), req.endTime(),
                req.graceMinutes(), req.standardHours()));
        audit.log(AuditService.SHIFT_SAVED, "Shift", s.getId(), describe(s));
        return ShiftView.of(s, 0);
    }

    @Transactional
    public ShiftView updateShift(Long id, ShiftRequest req) {
        Shift s = shifts.findById(id).orElseThrow(() -> ApiException.notFound("Shift " + id + " not found"));
        if (!s.getName().equalsIgnoreCase(req.name().trim()) && shifts.existsByNameIgnoreCase(req.name().trim())) {
            throw ApiException.conflict("A shift called '" + req.name().trim() + "' already exists");
        }
        validate(req);
        s.setName(req.name().trim());
        s.setStartTime(req.startTime());
        s.setEndTime(req.endTime());
        s.setGraceMinutes(req.graceMinutes());
        s.setStandardHours(req.standardHours());
        Shift saved = shifts.save(s);
        audit.log(AuditService.SHIFT_SAVED, "Shift", id, describe(saved));
        long count = employees.findAll().stream().filter(e -> e.getShift() != null && e.getShift().getId().equals(id)).count();
        return ShiftView.of(saved, count);
    }

    @Transactional
    public void deleteShift(Long id) {
        Shift s = shifts.findById(id).orElseThrow(() -> ApiException.notFound("Shift " + id + " not found"));
        List<Employee> using = employees.findAll().stream()
                .filter(e -> e.getShift() != null && e.getShift().getId().equals(id)).toList();
        if (!using.isEmpty()) {
            throw ApiException.conflict(using.size() + " employee(s) are on this shift. Move them to another shift first.");
        }
        shifts.delete(s);
        audit.log(AuditService.SHIFT_DELETED, "Shift", id, s.getName());
    }

    private static void validate(ShiftRequest req) {
        if (req.startTime().equals(req.endTime())) throw ApiException.badRequest("Start and end time must differ");
        Shift probe = new Shift("x", req.startTime(), req.endTime(), req.graceMinutes(), req.standardHours());
        if (req.standardHours() > probe.scheduledHours()) {
            throw ApiException.badRequest("Standard hours (" + req.standardHours() + ") can't be longer than the shift ("
                    + probe.scheduledHours() + " h)");
        }
    }

    private static String describe(Shift s) {
        return s.getName() + " " + s.getStartTime() + "-" + s.getEndTime() + (s.isOvernight() ? " (overnight)" : "")
                + ", grace " + s.getGraceMinutes() + " min, " + s.getStandardHours() + " h";
    }
}
