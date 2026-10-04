package com.cecil.attendance;

import com.cecil.attendance.dto.Dtos.AttendanceView;
import com.cecil.attendance.model.AttendanceStatus;
import com.cecil.attendance.model.Employee;
import com.cecil.attendance.model.Role;
import com.cecil.attendance.model.Shift;
import com.cecil.attendance.model.UserAccount;
import com.cecil.attendance.repository.EmployeeRepository;
import com.cecil.attendance.repository.HolidayRepository;
import com.cecil.attendance.repository.ShiftRepository;
import com.cecil.attendance.repository.UserAccountRepository;
import com.cecil.attendance.service.AttendanceService;
import com.cecil.attendance.service.WorkCalendar;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Holidays, shifts and overtime. */
@SpringBootTest(properties = {
        "attendance.seed-demo-data=true",
        "spring.datasource.url=jdbc:h2:mem:workrules;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class WorkRulesApiTest {

    @Autowired MockMvc mvc;
    @Autowired EmployeeRepository employees;
    @Autowired UserAccountRepository users;
    @Autowired ShiftRepository shifts;
    @Autowired HolidayRepository holidays;
    @Autowired AttendanceService attendance;
    @Autowired WorkCalendar calendar;
    @Autowired PasswordEncoder encoder;

    String admin;

    @BeforeEach
    void login() throws Exception {
        admin = token("admin", "Admin@123");
    }

    private String token(String user, String pass) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.token");
    }

    private Employee newEmployee(Shift shift) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Employee e = new Employee("W" + tag, "Work " + tag, tag + "@w.com", "Engineering", "Dev", LocalDate.now().minusYears(1));
        e.setShift(shift);
        return employees.save(e);
    }

    private Shift shift(String name) {
        return shifts.findAllByOrderByStartTimeAsc().stream().filter(s -> s.getName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void defaultShiftsAreSeeded() throws Exception {
        mvc.perform(get("/api/shifts").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(org.hamcrest.Matchers.containsInAnyOrder("Morning", "General", "Night")))
                .andExpect(jsonPath("$[?(@.name=='Night')].overnight").value(org.hamcrest.Matchers.contains(true)));
    }

    @Test
    void lateIsJudgedAgainstTheEmployeesShift() {
        Employee morning = newEmployee(shift("Morning"));   // 06:00, 10 min grace
        LocalDate d = LocalDate.now().minusDays(2);
        assertThat(attendance.applyCorrection(morning, d, LocalTime.of(6, 8), LocalTime.of(14, 0), "t").status())
                .isEqualTo("PRESENT");
        assertThat(attendance.applyCorrection(morning, d, LocalTime.of(6, 30), LocalTime.of(14, 30), "t").status())
                .isEqualTo("LATE");
        // the same 09:40 arrival is fine on default hours but very late on the morning shift
        assertThat(attendance.statusForCheckIn(newEmployee(null), LocalTime.of(9, 40))).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(attendance.statusForCheckIn(morning, LocalTime.of(9, 40))).isEqualTo(AttendanceStatus.LATE);
    }

    @Test
    void nightShiftCrossesMidnightAndCountsOvertime() {
        Employee night = newEmployee(shift("Night"));       // 22:00-06:00, 7.5 h standard
        LocalDate weekday = LocalDate.now().with(TemporalAdjusters.previous(DayOfWeek.WEDNESDAY));
        AttendanceView v = attendance.applyCorrection(night, weekday, LocalTime.of(21, 55), LocalTime.of(7, 0), "t");
        assertThat(v.status()).isEqualTo("PRESENT");
        assertThat(v.hoursWorked()).isEqualTo(9.1);
        assertThat(v.overtimeHours()).isEqualTo(1.6);
        // arriving after midnight on a night shift is late
        assertThat(attendance.statusForCheckIn(night, LocalTime.of(0, 30))).isEqualTo(AttendanceStatus.LATE);
    }

    @Test
    void weekendWorkIsAllOvertime() {
        Employee e = newEmployee(null);
        LocalDate saturday = LocalDate.now().with(TemporalAdjusters.previous(DayOfWeek.SATURDAY));
        AttendanceView v = attendance.applyCorrection(e, saturday, LocalTime.of(10, 0), LocalTime.of(14, 0), "t");
        assertThat(v.overtimeHours()).isEqualTo(4.0);
    }

    @Test
    void holidayIsNotAWorkingDayAndDoesNotUseLeave() throws Exception {
        // pick a Tuesday + Wednesday three weeks ahead; make the Wednesday a holiday
        LocalDate tue = LocalDate.now().plusWeeks(3).with(TemporalAdjusters.nextOrSame(DayOfWeek.TUESDAY));
        LocalDate wed = tue.plusDays(1);
        mvc.perform(post("/api/holidays").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"" + wed + "\",\"name\":\"Test Festival\"}"))
                .andExpect(status().isCreated());
        assertThat(calendar.isWorkingDay(wed)).isFalse();
        assertThat(calendar.holidayName(wed)).isEqualTo("Test Festival");

        // duplicate date is refused
        mvc.perform(post("/api/holidays").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"" + wed + "\",\"name\":\"Again\"}"))
                .andExpect(status().isConflict());

        // a 2-day leave over Tue+Wed only costs 1 day
        Employee e = newEmployee(null);
        users.save(new UserAccount("w" + e.getEmployeeCode().toLowerCase(), encoder.encode("Passw0rd!"), Role.EMPLOYEE, e));
        String emp = token("w" + e.getEmployeeCode().toLowerCase(), "Passw0rd!");
        mvc.perform(post("/api/me/leave-requests").header("Authorization", emp).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CASUAL\",\"fromDate\":\"" + tue + "\",\"toDate\":\"" + wed + "\",\"reason\":\"Trip\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.days").value(1));

        // stats for the holiday say it's not a working day
        mvc.perform(get("/api/reports/stats?date=" + wed).header("Authorization", admin))
                .andExpect(jsonPath("$.workingDay").value(false))
                .andExpect(jsonPath("$.holidayName").value("Test Festival"));

        // employees can read holidays but not add them
        mvc.perform(get("/api/holidays?year=" + wed.getYear()).header("Authorization", emp)).andExpect(status().isOk());
        mvc.perform(post("/api/holidays").header("Authorization", emp).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"" + wed.plusDays(7) + "\",\"name\":\"Nope\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void nationalHolidaysAreAddedOnce() throws Exception {
        mvc.perform(post("/api/holidays/national?year=2031").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4));
        mvc.perform(post("/api/holidays/national?year=2031").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        assertThat(holidays.findByDate(LocalDate.of(2031, 8, 15))).isPresent();
    }

    @Test
    void shiftValidationAndDeleteProtection() throws Exception {
        mvc.perform(post("/api/shifts").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Short\",\"startTime\":\"09:00\",\"endTime\":\"12:00\",\"graceMinutes\":5,\"standardHours\":8}"))
                .andExpect(status().isBadRequest()); // 8 h standard in a 3 h shift

        String body = mvc.perform(post("/api/shifts").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Evening\",\"startTime\":\"14:00\",\"endTime\":\"22:00\",\"graceMinutes\":10,\"standardHours\":7.5}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        int id = JsonPath.read(body, "$.id");
        newEmployee(shifts.findById((long) id).orElseThrow());
        mvc.perform(delete("/api/shifts/" + id).header("Authorization", admin)).andExpect(status().isConflict());
    }
}
