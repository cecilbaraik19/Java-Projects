package com.cecil.attendance.config;

import com.cecil.attendance.model.AttendanceRecord;
import com.cecil.attendance.model.AttendanceStatus;
import com.cecil.attendance.model.Employee;
import com.cecil.attendance.repository.AttendanceRepository;
import com.cecil.attendance.repository.EmployeeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Fills an empty database with demo employees and ~30 days of realistic attendance. */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private static final String[][] PEOPLE = {
            {"Aarav Sharma", "Engineering", "Software Engineer"},
            {"Priya Verma", "Engineering", "Senior Developer"},
            {"Rohan Gupta", "Engineering", "DevOps Engineer"},
            {"Ananya Singh", "Engineering", "QA Engineer"},
            {"Vikram Rao", "Engineering", "Tech Lead"},
            {"Sneha Iyer", "Engineering", "Frontend Developer"},
            {"Arjun Mehta", "Sales", "Sales Executive"},
            {"Kavya Nair", "Sales", "Account Manager"},
            {"Rahul Das", "Sales", "Sales Manager"},
            {"Isha Kapoor", "Sales", "Business Analyst"},
            {"Aditya Kumar", "Marketing", "Marketing Lead"},
            {"Meera Joshi", "Marketing", "Content Strategist"},
            {"Karan Malhotra", "Marketing", "SEO Specialist"},
            {"Pooja Reddy", "HR", "HR Manager"},
            {"Nikhil Bose", "HR", "Recruiter"},
            {"Divya Pillai", "Finance", "Accountant"},
            {"Siddharth Jain", "Finance", "Finance Manager"},
            {"Riya Chatterjee", "Finance", "Payroll Specialist"},
            {"Manish Tiwari", "Support", "Support Engineer"},
            {"Neha Mishra", "Support", "Support Lead"},
            {"Amit Soren", "Support", "Customer Success"},
            {"Tanvi Kulkarni", "Operations", "Operations Manager"},
            {"Harsh Pandey", "Operations", "Office Admin"},
            {"Simran Kaur", "Operations", "Facilities Coordinator"},
    };

    private final EmployeeRepository employees;
    private final AttendanceRepository attendance;
    private final AttendanceProperties props;
    private final Clock clock;

    public DataSeeder(EmployeeRepository employees, AttendanceRepository attendance,
                      AttendanceProperties props, Clock clock) {
        this.employees = employees;
        this.attendance = attendance;
        this.props = props;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!props.seedDemoData() || employees.count() > 0) return;

        Random rnd = new Random(42);
        LocalDate today = LocalDate.now(clock);
        List<Employee> saved = new ArrayList<>();
        for (int i = 0; i < PEOPLE.length; i++) {
            String[] p = PEOPLE[i];
            String email = p[0].toLowerCase().replace(' ', '.') + "@company.com";
            Employee e = new Employee(String.format("EMP%03d", i + 1), p[0], email, p[1], p[2],
                    today.minusDays(200 + rnd.nextInt(900)));
            saved.add(employees.save(e));
        }

        List<AttendanceRecord> records = new ArrayList<>();
        for (int back = 42; back >= 1; back--) {
            LocalDate d = today.minusDays(back);
            if (!props.isWorkingDay(d)) continue;
            for (Employee e : saved) records.add(randomDay(e, d, rnd, true));
        }
        // Today: a "live" day in progress — some people in, some not yet
        LocalTime now = LocalTime.now(clock);
        for (Employee e : saved) {
            AttendanceRecord r = randomDay(e, today, rnd, false);
            if (r.getCheckIn() != null && r.getCheckIn().isAfter(now)) continue; // hasn't arrived yet
            if (r.getStatus() == AttendanceStatus.ABSENT) continue;             // unknown until day ends
            if (r.getCheckIn() != null && now.isAfter(r.getCheckIn().plusMinutes(510))) {
                r.setCheckOut(r.getCheckIn().plusMinutes(510));                // evening: already left
            }
            records.add(r);
        }
        attendance.saveAll(records);
        log.info("Seeded {} employees and {} attendance records", saved.size(), records.size());
    }

    private AttendanceRecord randomDay(Employee e, LocalDate d, Random rnd, boolean dayFinished) {
        int roll = rnd.nextInt(100);
        AttendanceRecord r = new AttendanceRecord(e, d, AttendanceStatus.PRESENT);
        if (roll < 5) {
            r.setStatus(AttendanceStatus.ABSENT);
            return r;
        }
        if (roll < 10) {
            r.setStatus(AttendanceStatus.ON_LEAVE);
            r.setNote(rnd.nextBoolean() ? "Casual leave" : "Sick leave");
            return r;
        }
        LocalTime start = props.officeStartTime();
        LocalTime in = roll < 25
                ? props.lateAfter().plusMinutes(1 + rnd.nextInt(70))        // late
                : start.minusMinutes(25).plusMinutes(rnd.nextInt(38));     // on time
        r.setCheckIn(in);
        r.setStatus(in.isAfter(props.lateAfter()) ? AttendanceStatus.LATE : AttendanceStatus.PRESENT);

        if (dayFinished) {
            if (roll >= 25 && roll < 30) {
                r.setCheckOut(in.plusMinutes(180 + rnd.nextInt(80)));      // left early
                r.setStatus(AttendanceStatus.HALF_DAY);
            } else {
                r.setCheckOut(in.plusMinutes(480 + rnd.nextInt(90)));
            }
        }
        return r;
    }
}
