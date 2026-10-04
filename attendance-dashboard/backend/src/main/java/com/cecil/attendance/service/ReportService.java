package com.cecil.attendance.service;

import com.cecil.attendance.config.AttendanceProperties;
import com.cecil.attendance.dto.Dtos.DashboardStats;
import com.cecil.attendance.dto.Dtos.DepartmentStat;
import com.cecil.attendance.dto.Dtos.EmployeeSummary;
import com.cecil.attendance.dto.Dtos.TrendPoint;
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
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class ReportService {

    private final AttendanceRepository attendance;
    private final EmployeeRepository employees;
    private final AttendanceProperties props;
    private final Clock clock;

    public ReportService(AttendanceRepository attendance, EmployeeRepository employees,
                         AttendanceProperties props, Clock clock) {
        this.attendance = attendance;
        this.employees = employees;
        this.props = props;
        this.clock = clock;
    }

    /** Active employees, optionally limited to one department (null = all). */
    private List<Employee> activeEmployees(String department) {
        return employees.findByActiveTrueOrderByFullNameAsc().stream()
                .filter(e -> AccessGuard.inScope(e, department)).toList();
    }

    private List<AttendanceRecord> recordsOn(LocalDate date, String department) {
        return attendance.findByDateOrderByCheckInAsc(date).stream()
                .filter(r -> AccessGuard.inScope(r.getEmployee(), department)).toList();
    }

    /** @param department null = whole company, otherwise one department */
    public DashboardStats stats(LocalDate date, String department) {
        List<AttendanceRecord> records = recordsOn(date, department);
        Counts c = Counts.of(records, activeEmployees(department).size());

        OptionalDouble avgMinutes = records.stream()
                .filter(r -> r.getCheckIn() != null)
                .mapToInt(r -> r.getCheckIn().toSecondOfDay() / 60)
                .average();
        LocalTime avgCheckIn = avgMinutes.isPresent()
                ? LocalTime.ofSecondOfDay(Math.round(avgMinutes.getAsDouble()) * 60) : null;

        long stillIn = records.stream().filter(r -> r.getCheckIn() != null && r.getCheckOut() == null).count();

        return new DashboardStats(date, props.isWorkingDay(date), c.total, c.present, c.late, c.halfDay,
                c.onLeave, c.absent, stillIn, c.rate(), avgCheckIn);
    }

    /** Last {@code days} working days ending today (today is always included). */
    public List<TrendPoint> trend(int days, String department) {
        if (days < 1 || days > 90) throw ApiException.badRequest("days must be between 1 and 90");
        LocalDate today = LocalDate.now(clock);
        List<LocalDate> dates = new ArrayList<>();
        dates.add(today);
        LocalDate d = today.minusDays(1);
        while (dates.size() < days) {
            if (props.isWorkingDay(d)) dates.add(d);
            d = d.minusDays(1);
        }
        Collections.reverse(dates);

        Map<LocalDate, List<AttendanceRecord>> byDate = attendance.findByDateBetween(dates.get(0), today)
                .stream().filter(r -> AccessGuard.inScope(r.getEmployee(), department))
                .collect(Collectors.groupingBy(AttendanceRecord::getDate));
        long total = activeEmployees(department).size();

        return dates.stream().map(date -> {
            Counts c = Counts.of(byDate.getOrDefault(date, List.of()), total);
            return new TrendPoint(date, c.present, c.late, c.halfDay, c.onLeave, c.absent, c.rate());
        }).toList();
    }

    public List<DepartmentStat> departments(LocalDate date, String department) {
        Map<String, List<Employee>> byDept = activeEmployees(department).stream()
                .collect(Collectors.groupingBy(Employee::getDepartment, TreeMap::new, Collectors.toList()));
        Map<Long, AttendanceRecord> recs = recordsOn(date, department).stream()
                .collect(Collectors.toMap(r -> r.getEmployee().getId(), r -> r));

        return byDept.entrySet().stream().map(en -> {
            List<AttendanceRecord> deptRecs = en.getValue().stream()
                    .map(e -> recs.get(e.getId())).filter(Objects::nonNull).toList();
            Counts c = Counts.of(deptRecs, en.getValue().size());
            return new DepartmentStat(en.getKey(), c.total, c.attended(), c.onLeave, c.rate());
        }).toList();
    }

    public List<EmployeeSummary> summary(LocalDate from, LocalDate to, String department) {
        if (to.isBefore(from)) throw ApiException.badRequest("'to' must not be before 'from'");
        LocalDate today = LocalDate.now(clock);
        LocalDate end = to.isAfter(today) ? today : to;

        Map<Long, List<AttendanceRecord>> byEmp = attendance.findByDateBetween(from, end).stream()
                .collect(Collectors.groupingBy(r -> r.getEmployee().getId()));

        List<EmployeeSummary> out = new ArrayList<>();
        for (Employee e : employees.findAllByOrderByFullNameAsc()) {
            if (!AccessGuard.inScope(e, department)) continue;
            List<AttendanceRecord> recs = byEmp.getOrDefault(e.getId(), List.of());
            if (!e.isActive() && recs.isEmpty()) continue;

            LocalDate start = (e.getJoinDate() != null && e.getJoinDate().isAfter(from)) ? e.getJoinDate() : from;
            long workingDays = start.isAfter(end) ? 0
                    : start.datesUntil(end.plusDays(1)).filter(props::isWorkingDay).count();
            // weekend days that do have records (e.g. today on a Sunday) still count
            long extra = recs.stream().filter(r -> !props.isWorkingDay(r.getDate())).count();
            Counts c = Counts.of(recs, workingDays + extra);
            double hours = recs.stream().mapToDouble(AttendanceRecord::getHoursWorked).sum();

            out.add(new EmployeeSummary(e.getId(), e.getEmployeeCode(), e.getFullName(), e.getDepartment(),
                    c.total, c.present, c.late, c.halfDay, c.onLeave, c.absent,
                    Math.round(hours * 10) / 10.0, c.rate()));
        }
        return out;
    }

    public String summaryCsv(LocalDate from, LocalDate to, String department) {
        StringBuilder sb = new StringBuilder(
                "Code,Name,Department,Working Days,Present,Late,Half Day,On Leave,Absent,Total Hours,Attendance %\n");
        for (EmployeeSummary s : summary(from, to, department)) {
            sb.append(csv(s.employeeCode())).append(',').append(csv(s.employeeName())).append(',')
                    .append(csv(s.department())).append(',').append(s.workingDays()).append(',')
                    .append(s.present()).append(',').append(s.late()).append(',').append(s.halfDay()).append(',')
                    .append(s.onLeave()).append(',').append(s.absent()).append(',').append(s.totalHours()).append(',')
                    .append(s.attendanceRate()).append('\n');
        }
        return sb.toString();
    }

    private static String csv(String v) {
        if (v == null) return "";
        // neutralise spreadsheet formula injection and escape quotes
        String safe = v.matches("^[=+\\-@].*") ? "'" + v : v;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    /** Status tally for a group of records against an expected headcount. */
    private record Counts(long total, long present, long late, long halfDay, long onLeave, long absent) {

        static Counts of(Collection<AttendanceRecord> recs, long expected) {
            Map<AttendanceStatus, Long> m = recs.stream()
                    .collect(Collectors.groupingBy(AttendanceRecord::getStatus, Collectors.counting()));
            long p = m.getOrDefault(AttendanceStatus.PRESENT, 0L);
            long l = m.getOrDefault(AttendanceStatus.LATE, 0L);
            long h = m.getOrDefault(AttendanceStatus.HALF_DAY, 0L);
            long lv = m.getOrDefault(AttendanceStatus.ON_LEAVE, 0L);
            long total = Math.max(expected, recs.size());
            long absent = Math.max(0, total - p - l - h - lv);
            return new Counts(total, p, l, h, lv, absent);
        }

        long attended() {
            return present + late + halfDay;
        }

        /** Attended / (headcount - on leave), as a percentage with one decimal. */
        double rate() {
            long base = total - onLeave;
            if (base <= 0) return 0;
            return Math.round(attended() * 1000.0 / base) / 10.0;
        }
    }
}
