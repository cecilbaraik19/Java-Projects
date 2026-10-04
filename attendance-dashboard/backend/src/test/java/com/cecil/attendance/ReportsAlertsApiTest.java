package com.cecil.attendance;

import com.cecil.attendance.model.*;
import com.cecil.attendance.repository.AttendanceRepository;
import com.cecil.attendance.repository.EmployeeRepository;
import com.cecil.attendance.repository.NotificationRepository;
import com.cecil.attendance.repository.UserAccountRepository;
import com.cecil.attendance.service.AlertService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Calendar, Excel/PDF export, late patterns and email alerts (SMTP not configured -> outbox only). */
@SpringBootTest(properties = {
        "attendance.seed-demo-data=true",
        "spring.datasource.url=jdbc:h2:mem:reportsalerts;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class ReportsAlertsApiTest {

    @Autowired MockMvc mvc;
    @Autowired EmployeeRepository employees;
    @Autowired UserAccountRepository users;
    @Autowired AttendanceRepository attendance;
    @Autowired NotificationRepository outbox;
    @Autowired AlertService alerts;
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

    private Employee newEmployee() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        return employees.save(new Employee("R" + tag, "Report " + tag, tag + "@r.com", "Engineering", "Dev",
                LocalDate.now().minusYears(1)));
    }

    private String loginAs(Employee e) throws Exception {
        String u = "r" + e.getEmployeeCode().toLowerCase().substring(1);
        users.save(new UserAccount(u, encoder.encode("Passw0rd!"), Role.EMPLOYEE, e));
        return token(u, "Passw0rd!");
    }

    @Test
    void excelAndPdfExportsAreRealFiles() throws Exception {
        String q = "?from=" + LocalDate.now().minusDays(30) + "&to=" + LocalDate.now();
        byte[] xlsx = mvc.perform(get("/api/reports/summary.xlsx" + q).header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(Arrays.copyOf(xlsx, 2), StandardCharsets.US_ASCII)).isEqualTo("PK"); // zip container
        byte[] pdf = mvc.perform(get("/api/reports/summary.pdf" + q).header("Authorization", token("manager", "Manager@123")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(Arrays.copyOf(pdf, 4), StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        // employees cannot export
        mvc.perform(get("/api/reports/summary.pdf" + q).header("Authorization", loginAs(newEmployee())))
                .andExpect(status().isForbidden());
    }

    @Test
    void monthCalendarCoversEveryDay() throws Exception {
        Employee e = newEmployee();
        String emp = loginAs(e);
        YearMonth last = YearMonth.now().minusMonths(1);
        LocalDate saturday = last.atDay(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
        LocalDate monday = last.atDay(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        attendance.save(withTimes(new AttendanceRecord(e, monday, AttendanceStatus.PRESENT), "09:10", "18:00"));

        String body = mvc.perform(get("/api/me/calendar?month=" + last).header("Authorization", emp))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days.length()").value(last.lengthOfMonth()))
                .andReturn().getResponse().getContentAsString();
        List<String> sat = JsonPath.read(body, "$.days[?(@.date=='" + saturday + "')].status");
        List<String> mon = JsonPath.read(body, "$.days[?(@.date=='" + monday + "')].status");
        List<String> tue = JsonPath.read(body, "$.days[?(@.date=='" + monday.plusDays(1) + "')].status");
        assertThat(sat).containsExactly("WEEKEND");
        assertThat(mon).containsExactly("PRESENT");
        assertThat(tue).containsExactly("ABSENT");

        mvc.perform(get("/api/me/calendar?month=bad").header("Authorization", emp)).andExpect(status().isBadRequest());
    }

    @Test
    void frequentLatenessIsFlagged() throws Exception {
        Employee e = newEmployee();
        LocalDate monday = LocalDate.now().with(TemporalAdjusters.previous(DayOfWeek.MONDAY));
        for (int w = 0; w < 4; w++) {
            attendance.save(withTimes(new AttendanceRecord(e, monday.minusWeeks(w), AttendanceStatus.LATE), "10:20", "18:30"));
        }
        String body = mvc.perform(get("/api/reports/patterns?from=" + LocalDate.now().minusDays(35) + "&to=" + LocalDate.now()
                        + "&threshold=3").header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Integer> late = JsonPath.read(body, "$[?(@.employeeId==" + e.getId() + ")].lateCount");
        List<String> day = JsonPath.read(body, "$[?(@.employeeId==" + e.getId() + ")].usualLateDay");
        List<String> msg = JsonPath.read(body, "$[?(@.employeeId==" + e.getId() + ")].message");
        assertThat(late).containsExactly(4);
        assertThat(day).containsExactly("Monday");
        assertThat(msg.get(0)).contains("late 4 times").contains("Mondays");
    }

    @Test
    void missingCheckInReminderIsSentOncePerDay() {
        Employee e = newEmployee();
        LocalDate day = LocalDate.now().minusDays(1);
        while (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) day = day.minusDays(1);

        int first = alerts.sendMissingCheckInReminders(day, LocalTime.of(23, 0));
        assertThat(first).isGreaterThanOrEqualTo(1);
        List<Notification> mine = outbox.findByRecipientIgnoreCaseOrderByCreatedAtDesc(e.getEmail());
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).getKind()).isEqualTo(Notification.Kind.MISSING_CHECKIN);
        assertThat(mine.get(0).getStatus()).isEqualTo(Notification.Status.LOGGED); // no SMTP in tests

        alerts.sendMissingCheckInReminders(day, LocalTime.of(23, 30));
        assertThat(outbox.findByRecipientIgnoreCaseOrderByCreatedAtDesc(e.getEmail())).hasSize(1);

        // not before the shift has started
        Employee early = newEmployee();
        alerts.sendMissingCheckInReminders(day, LocalTime.of(8, 0));
        assertThat(outbox.findByRecipientIgnoreCaseOrderByCreatedAtDesc(early.getEmail())).isEmpty();
    }

    @Test
    void leaveRequestNotifiesManagerAndDecisionNotifiesEmployee() throws Exception {
        Employee e = newEmployee();
        String emp = loginAs(e);
        LocalDate day = LocalDate.now().plusWeeks(5).with(TemporalAdjusters.nextOrSame(DayOfWeek.THURSDAY));
        String body = mvc.perform(post("/api/me/leave-requests").header("Authorization", emp).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CASUAL\",\"fromDate\":\"" + day + "\",\"toDate\":\"" + day + "\",\"reason\":\"Exam\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        int id = JsonPath.read(body, "$.id");

        String managerEmail = users.findByUsernameIgnoreCase("manager").orElseThrow().getEmployee().getEmail();
        assertThat(outbox.findByRecipientIgnoreCaseOrderByCreatedAtDesc(managerEmail))
                .anyMatch(n -> n.getKind() == Notification.Kind.LEAVE_REQUEST && n.getSubject().contains(e.getFullName()));

        mvc.perform(post("/api/approvals/leave/" + id).header("Authorization", token("manager", "Manager@123"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approve\":true}"))
                .andExpect(status().isOk());
        assertThat(outbox.findByRecipientIgnoreCaseOrderByCreatedAtDesc(e.getEmail()))
                .anyMatch(n -> n.getKind() == Notification.Kind.LEAVE_DECISION && n.getSubject().contains("approved"));
    }

    @Test
    void notificationAdminEndpoints() throws Exception {
        mvc.perform(get("/api/notifications/settings").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.smtpConfigured").value(false));
        mvc.perform(post("/api/notifications/run/weekly-summary").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sent").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
        mvc.perform(get("/api/notifications?kind=WEEKLY_SUMMARY").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].subject").value(org.hamcrest.Matchers.containsString("Engineering")));
        mvc.perform(get("/api/notifications").header("Authorization", token("manager", "Manager@123")))
                .andExpect(status().isForbidden());
    }

    private static AttendanceRecord withTimes(AttendanceRecord r, String in, String out) {
        r.setCheckIn(LocalTime.parse(in));
        r.setCheckOut(LocalTime.parse(out));
        return r;
    }
}
