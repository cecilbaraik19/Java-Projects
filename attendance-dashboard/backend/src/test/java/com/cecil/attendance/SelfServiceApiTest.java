package com.cecil.attendance;

import com.cecil.attendance.config.AttendanceProperties;
import com.cecil.attendance.model.AttendanceStatus;
import com.cecil.attendance.model.Employee;
import com.cecil.attendance.model.Role;
import com.cecil.attendance.model.UserAccount;
import com.cecil.attendance.repository.AttendanceRepository;
import com.cecil.attendance.repository.EmployeeRepository;
import com.cecil.attendance.repository.UserAccountRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Self check-in, leave and correction workflows, end to end over HTTP. */
@SpringBootTest(properties = {
        "attendance.seed-demo-data=true",
        "spring.datasource.url=jdbc:h2:mem:selfservice;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class SelfServiceApiTest {

    @Autowired MockMvc mvc;
    @Autowired EmployeeRepository employees;
    @Autowired UserAccountRepository users;
    @Autowired AttendanceRepository attendance;
    @Autowired PasswordEncoder encoder;
    @Autowired AttendanceProperties props;

    record TestUser(String auth, Long employeeId) {
    }

    /** Creates a fresh Engineering employee with a login, so tests don't clash with seeded data. */
    private TestUser newEngineer() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Employee e = employees.save(new Employee("T" + tag, "Test " + tag, tag + "@test.com", "Engineering", "Dev",
                LocalDate.now().minusYears(1)));
        users.save(new UserAccount("u" + tag, encoder.encode("Passw0rd!"), Role.EMPLOYEE, e));
        return new TestUser(token("u" + tag, "Passw0rd!"), e.getId());
    }

    private String token(String user, String pass) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.token");
    }

    private LocalDate workingDayAfter(LocalDate d) {
        LocalDate x = d.plusDays(1);
        while (!props.isWorkingDay(x)) x = x.plusDays(1);
        return x;
    }

    @Test
    void employeeCanCheckInAndOutOnlyOnce() throws Exception {
        String t = newEngineer().auth();
        mvc.perform(post("/api/me/check-in").header("Authorization", t)).andExpect(status().isOk())
                .andExpect(jsonPath("$.checkIn").isNotEmpty());
        mvc.perform(post("/api/me/check-in").header("Authorization", t)).andExpect(status().isConflict());
        mvc.perform(post("/api/me/check-out").header("Authorization", t)).andExpect(status().isOk());
    }

    @Test
    void leaveFlowApplyApproveMarksAttendance() throws Exception {
        TestUser u = newEngineer();
        String emp = u.auth();
        Long empId = u.employeeId();
        LocalDate day = workingDayAfter(LocalDate.now().plusDays(20));
        String body = mvc.perform(post("/api/me/leave-requests").header("Authorization", emp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CASUAL\",\"fromDate\":\"" + day + "\",\"toDate\":\"" + day + "\",\"reason\":\"Wedding\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.days").value(1))
                .andReturn().getResponse().getContentAsString();
        int id = JsonPath.read(body, "$.id");

        // overlapping request is refused
        mvc.perform(post("/api/me/leave-requests").header("Authorization", emp).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SICK\",\"fromDate\":\"" + day + "\",\"toDate\":\"" + day + "\",\"reason\":\"x\"}"))
                .andExpect(status().isConflict());

        // balance shows it as pending
        mvc.perform(get("/api/me/leave-balance").header("Authorization", emp))
                .andExpect(jsonPath("$[?(@.type=='CASUAL')].pending").value(org.hamcrest.Matchers.contains(1)));

        // Engineering manager approves
        String mgr = token("manager", "Manager@123");
        mvc.perform(post("/api/approvals/leave/" + id).header("Authorization", mgr).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approve\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));

        assertThat(attendance.findByEmployeeIdAndDate(empId, day).orElseThrow().getStatus()).isEqualTo(AttendanceStatus.ON_LEAVE);

        // employee cancels upcoming approved leave -> attendance cleared
        mvc.perform(post("/api/me/leave-requests/" + id + "/cancel").header("Authorization", emp))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(attendance.findByEmployeeIdAndDate(empId, day)).isEmpty();
    }

    @Test
    void leaveOverBalanceIsRefused() throws Exception {
        String emp = newEngineer().auth();
        LocalDate from = workingDayAfter(LocalDate.now().plusDays(2));
        LocalDate to = from.plusDays(27); // ~20 working days > 15 earned
        mvc.perform(post("/api/me/leave-requests").header("Authorization", emp).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"EARNED\",\"fromDate\":\"" + from + "\",\"toDate\":\"" + to + "\",\"reason\":\"Trip\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Not enough")));
    }

    @Test
    void managerCannotApproveOwnOrOtherDepartmentRequests() throws Exception {
        String mgr = token("manager", "Manager@123");
        LocalDate day = workingDayAfter(LocalDate.now().plusDays(30));
        String own = mvc.perform(post("/api/me/leave-requests").header("Authorization", mgr).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CASUAL\",\"fromDate\":\"" + day + "\",\"toDate\":\"" + day + "\",\"reason\":\"Own\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        int ownId = JsonPath.read(own, "$.id");
        mvc.perform(post("/api/approvals/leave/" + ownId).header("Authorization", mgr).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approve\":true}"))
                .andExpect(status().isForbidden());

        // own request is not in the manager's queue; seeded Sales request is not either
        String queue = mvc.perform(get("/api/approvals/leave").header("Authorization", mgr))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> depts = JsonPath.read(queue, "$[*].department");
        List<Integer> ids = JsonPath.read(queue, "$[*].id");
        assertThat(depts).containsOnly("Engineering");
        assertThat(ids).doesNotContain(ownId);

        // admin can approve the manager's request
        mvc.perform(post("/api/approvals/leave/" + ownId).header("Authorization", token("admin", "Admin@123"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approve\":false,\"comment\":\"Release week\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void employeeCannotUseApprovalEndpoints() throws Exception {
        String emp = newEngineer().auth();
        mvc.perform(get("/api/approvals/leave").header("Authorization", emp)).andExpect(status().isForbidden());
    }

    @Test
    void correctionFlowUpdatesTimes() throws Exception {
        String emp = newEngineer().auth();
        LocalDate day = LocalDate.now().minusDays(3);
        String body = mvc.perform(post("/api/me/corrections").header("Authorization", emp).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"" + day + "\",\"checkIn\":\"09:05\",\"checkOut\":\"18:00\",\"reason\":\"Card reader down\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        int id = JsonPath.read(body, "$.id");

        mvc.perform(post("/api/approvals/corrections/" + id).header("Authorization", token("manager", "Manager@123"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approve\":false}"))
                .andExpect(status().isBadRequest()); // reject needs a reason
        mvc.perform(post("/api/approvals/corrections/" + id).header("Authorization", token("manager", "Manager@123"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approve\":true}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/me/attendance?from=" + day + "&to=" + day).header("Authorization", emp))
                .andExpect(jsonPath("$[0].checkIn").value(org.hamcrest.Matchers.startsWith("09:05")))
                .andExpect(jsonPath("$[0].status").value("PRESENT"));
    }
}
