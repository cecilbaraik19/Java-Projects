package com.cecil.attendance.service;

import com.cecil.attendance.config.AttendanceProperties;
import com.cecil.attendance.model.AttendanceRecord;
import com.cecil.attendance.model.Employee;
import com.cecil.attendance.model.Holiday;
import com.cecil.attendance.model.Shift;
import com.cecil.attendance.repository.HolidayRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * One place for "is this a working day?" (weekends + holidays) and per-employee shift rules
 * (late threshold, standard hours, overtime). Holidays are cached in memory - there are only a few
 * dozen a year - and refreshed whenever an admin changes them.
 */
@Service
public class WorkCalendar {

    /** Effective rules for one employee: their shift, or the default office hours. */
    public record ShiftRules(String name, LocalTime start, LocalTime end, int graceMinutes, double standardHours) {

        public LocalTime lateAfter() {
            return start.plusMinutes(graceMinutes);
        }

        public boolean overnight() {
            return !end.isAfter(start);
        }
    }

    private final HolidayRepository holidays;
    private final AttendanceProperties props;
    private volatile Map<LocalDate, String> cache;

    public WorkCalendar(HolidayRepository holidays, AttendanceProperties props) {
        this.holidays = holidays;
        this.props = props;
    }

    // ---------- holidays ----------

    private Map<LocalDate, String> holidayMap() {
        Map<LocalDate, String> c = cache;
        if (c == null) {
            c = holidays.findAll().stream().collect(Collectors.toMap(Holiday::getDate, Holiday::getName, (a, b) -> a));
            cache = c;
        }
        return c;
    }

    /** Call after adding, changing or deleting holidays. */
    public void refresh() {
        cache = null;
    }

    public String holidayName(LocalDate date) {
        return holidayMap().get(date);
    }

    public boolean isHoliday(LocalDate date) {
        return holidayMap().containsKey(date);
    }

    /** Not a weekend and not a holiday. */
    public boolean isWorkingDay(LocalDate date) {
        return props.isWorkingDay(date) && !isHoliday(date);
    }

    public List<LocalDate> workingDays(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) return List.of();
        return from.datesUntil(to.plusDays(1)).filter(this::isWorkingDay).toList();
    }

    // ---------- shifts ----------

    public ShiftRules rulesFor(Employee e) {
        Shift s = e == null ? null : e.getShift();
        if (s != null) {
            return new ShiftRules(s.getName(), s.getStartTime(), s.getEndTime(), s.getGraceMinutes(), s.getStandardHours());
        }
        LocalTime start = props.officeStartTime();
        long minutes = Math.round(props.standardHours() * 60) + 30; // + lunch break
        return new ShiftRules("Default", start, start.plusMinutes(minutes), props.lateGraceMinutes(), props.standardHours());
    }

    /**
     * Overtime for a finished day: hours beyond the shift's standard hours,
     * or every hour worked on a weekend/holiday.
     */
    public double overtimeHours(AttendanceRecord r) {
        double worked = r.getHoursWorked();
        if (worked <= 0) return 0;
        double ot = isWorkingDay(r.getDate()) ? worked - rulesFor(r.getEmployee()).standardHours() : worked;
        return Math.max(0, Math.round(ot * 10) / 10.0);
    }
}
