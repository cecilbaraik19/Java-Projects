package com.cecil.attendance.config;

import com.cecil.attendance.model.AttendanceRecord;
import com.cecil.attendance.model.CorrectionRequest;
import com.cecil.attendance.model.LeaveRequest;
import com.cecil.attendance.model.LeaveType;
import com.cecil.attendance.repository.AttendanceRepository;
import com.cecil.attendance.repository.CorrectionRequestRepository;
import com.cecil.attendance.repository.EmployeeRepository;
import com.cecil.attendance.repository.LeaveRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;

/** A few pending demo requests so the Approvals page has something to show. */
@Component
@Order(3)
public class RequestSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(RequestSeeder.class);

    private final LeaveRequestRepository leaves;
    private final CorrectionRequestRepository corrections;
    private final EmployeeRepository employees;
    private final AttendanceRepository attendance;
    private final AttendanceProperties props;
    private final Clock clock;

    public RequestSeeder(LeaveRequestRepository leaves, CorrectionRequestRepository corrections,
                         EmployeeRepository employees, AttendanceRepository attendance,
                         AttendanceProperties props, Clock clock) {
        this.leaves = leaves;
        this.corrections = corrections;
        this.employees = employees;
        this.attendance = attendance;
        this.props = props;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!props.seedDemoData() || leaves.count() > 0 || corrections.count() > 0) return;
        LocalDate today = LocalDate.now(clock);
        LocalDate next = nextWorkingDay(today.plusDays(7));
        LocalDate nextPlus1 = nextWorkingDay(next.plusDays(1));

        employees.findByEmployeeCodeIgnoreCase("EMP003").ifPresent(e ->           // Rohan, Engineering
                leaves.save(new LeaveRequest(e, LeaveType.CASUAL, next, nextPlus1, 2, "Family function in Ranchi")));
        employees.findByEmployeeCodeIgnoreCase("EMP004").ifPresent(e ->           // Ananya, Engineering
                leaves.save(new LeaveRequest(e, LeaveType.EARNED, nextPlus1, nextPlus1, 1, "Personal work")));
        employees.findByEmployeeCodeIgnoreCase("EMP008").ifPresent(e ->           // Kavya, Sales
                leaves.save(new LeaveRequest(e, LeaveType.SICK, next, next, 1, "Doctor's appointment")));

        // Aarav forgot to check out on the last working day
        employees.findByEmployeeCodeIgnoreCase("EMP001").ifPresent(e -> {
            LocalDate last = previousWorkingDay(today.minusDays(1));
            AttendanceRecord rec = attendance.findByEmployeeIdAndDate(e.getId(), last).orElse(null);
            corrections.save(new CorrectionRequest(e, last, rec, LocalTime.of(9, 20), LocalTime.of(18, 10),
                    "Forgot to check out - left at 6:10 pm"));
        });
        log.info("Seeded demo leave and correction requests");
    }

    private LocalDate nextWorkingDay(LocalDate d) {
        while (!props.isWorkingDay(d)) d = d.plusDays(1);
        return d;
    }

    private LocalDate previousWorkingDay(LocalDate d) {
        while (!props.isWorkingDay(d)) d = d.minusDays(1);
        return d;
    }
}
