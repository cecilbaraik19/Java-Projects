package com.cecil.attendance;

import com.cecil.attendance.dto.Dtos.*;
import com.cecil.attendance.exception.ApiException;
import com.cecil.attendance.model.AttendanceStatus;
import com.cecil.attendance.model.Employee;
import com.cecil.attendance.repository.AttendanceRepository;
import com.cecil.attendance.repository.EmployeeRepository;
import com.cecil.attendance.service.AttendanceService;
import com.cecil.attendance.service.EmployeeService;
import com.cecil.attendance.service.ReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "attendance.seed-demo-data=false",
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1"
})
class AttendanceServiceTest {

    /** Monday 5 Oct 2026; tests move the clock by setting NOW. */
    static final LocalDate DAY = LocalDate.of(2026, 10, 5);
    static final AtomicReference<LocalTime> NOW = new AtomicReference<>(LocalTime.of(9, 0));

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock testClock() {
            return new Clock() {
                public ZoneId getZone() { return ZoneId.of("Asia/Kolkata"); }
                public Clock withZone(ZoneId zone) { return this; }
                public Instant instant() { return DAY.atTime(NOW.get()).atZone(getZone()).toInstant(); }
            };
        }
    }

    @Autowired AttendanceService attendance;
    @Autowired EmployeeService employeeService;
    @Autowired ReportService reports;
    @Autowired EmployeeRepository employeeRepo;
    @Autowired AttendanceRepository attendanceRepo;

    Employee alice;
    Employee bob;

    @BeforeEach
    void setUp() {
        attendanceRepo.deleteAll();
        employeeRepo.deleteAll();
        alice = employeeService.create(new EmployeeRequest("e1", "Alice", "alice@x.com", "Engineering", "Dev", null, true));
        bob = employeeService.create(new EmployeeRequest("e2", "Bob", "bob@x.com", "Sales", "Rep", null, true));
    }

    @Test
    void onTimeCheckInIsPresentAndLateIsLate() {
        NOW.set(LocalTime.of(9, 40)); // within 15-min grace of 09:30
        assertThat(attendance.checkIn(alice.getId()).status()).isEqualTo("PRESENT");
        NOW.set(LocalTime.of(9, 46));
        assertThat(attendance.checkIn(bob.getId()).status()).isEqualTo("LATE");
    }

    @Test
    void doubleCheckInAndCheckoutWithoutCheckInAreRejected() {
        NOW.set(LocalTime.of(9, 0));
        assertThatThrownBy(() -> attendance.checkOut(alice.getId())).isInstanceOf(ApiException.class);
        attendance.checkIn(alice.getId());
        assertThatThrownBy(() -> attendance.checkIn(alice.getId())).hasMessageContaining("already checked in");
    }

    @Test
    void shortDayBecomesHalfDay() {
        NOW.set(LocalTime.of(9, 0));
        attendance.checkIn(alice.getId());
        attendance.checkIn(bob.getId());
        NOW.set(LocalTime.of(12, 0));
        AttendanceView a = attendance.checkOut(alice.getId());
        assertThat(a.status()).isEqualTo("HALF_DAY");
        assertThat(a.hoursWorked()).isEqualTo(3.0);
        NOW.set(LocalTime.of(18, 0));
        assertThat(attendance.checkOut(bob.getId()).status()).isEqualTo("PRESENT");
    }

    @Test
    void statsCountAbsentAndExcludeLeaveFromRate() {
        Employee carol = employeeService.create(new EmployeeRequest("e3", "Carol", "c@x.com", "HR", null, null, true));
        NOW.set(LocalTime.of(9, 0));
        attendance.checkIn(alice.getId());
        attendance.saveManual(new ManualEntryRequest(carol.getId(), DAY, AttendanceStatus.ON_LEAVE, null, null, "Sick"));

        DashboardStats s = reports.stats(DAY);
        assertThat(s.totalEmployees()).isEqualTo(3);
        assertThat(s.present()).isEqualTo(1);
        assertThat(s.onLeave()).isEqualTo(1);
        assertThat(s.absent()).isEqualTo(1);          // Bob hasn't come
        assertThat(s.attendanceRate()).isEqualTo(50.0); // 1 of 2 expected
        assertThat(s.stillInOffice()).isEqualTo(1);
    }

    @Test
    void dailyBoardShowsNotMarkedRows() {
        NOW.set(LocalTime.of(9, 0));
        attendance.checkIn(bob.getId());
        List<AttendanceView> board = attendance.dailyBoard(DAY);
        assertThat(board).extracting(AttendanceView::status).containsExactly("NOT_MARKED", "PRESENT");
    }

    @Test
    void manualEntryValidatesTimesAndFutureDates() {
        assertThatThrownBy(() -> attendance.saveManual(new ManualEntryRequest(alice.getId(), DAY.plusDays(1),
                AttendanceStatus.PRESENT, LocalTime.of(9, 0), null, null))).hasMessageContaining("future");
        assertThatThrownBy(() -> attendance.saveManual(new ManualEntryRequest(alice.getId(), DAY,
                AttendanceStatus.PRESENT, LocalTime.of(9, 0), LocalTime.of(8, 0), null))).hasMessageContaining("after");
    }

    @Test
    void duplicateEmployeeCodeIsRejected() {
        assertThatThrownBy(() -> employeeService.create(
                new EmployeeRequest("E1", "Other", "o@x.com", "HR", null, null, true)))
                .hasMessageContaining("already exists");
    }

    @Test
    void summaryCountsWorkingDays() {
        // Mon 28 Sep .. Mon 5 Oct = 6 working days
        attendance.saveManual(new ManualEntryRequest(alice.getId(), DAY.minusDays(7), AttendanceStatus.PRESENT,
                LocalTime.of(9, 0), LocalTime.of(18, 0), null));
        EmployeeSummary s = reports.summary(DAY.minusDays(7), DAY).stream()
                .filter(x -> x.employeeId().equals(alice.getId())).findFirst().orElseThrow();
        assertThat(s.workingDays()).isEqualTo(6);
        assertThat(s.present()).isEqualTo(1);
        assertThat(s.absent()).isEqualTo(5);
        assertThat(s.totalHours()).isEqualTo(9.0);
    }

    @Test
    void csvEscapesFormulaInjection() {
        employeeService.create(new EmployeeRequest("e9", "=HYPERLINK(\"x\")", "h@x.com", "HR", null, null, true));
        assertThat(reports.summaryCsv(DAY, DAY)).contains("\"'=HYPERLINK(\"\"x\"\")\"");
    }
}
