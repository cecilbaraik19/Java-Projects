package com.cecil.attendance;

import com.cecil.attendance.model.Role;
import com.cecil.attendance.model.UserAccount;
import com.cecil.attendance.repository.UserAccountRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end checks of login, JWT handling, roles and department scoping (uses the demo seed data). */
@SpringBootTest(properties = {
        "attendance.seed-demo-data=true",
        "spring.datasource.url=jdbc:h2:mem:sectest;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class SecurityApiTest {

    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired PasswordEncoder encoder;

    private String login(String user, String pass) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(r.getResponse().getContentAsString(), "$.token");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    void apiRequiresLogin() throws Exception {
        mvc.perform(get("/api/employees")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/employees").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminLoginReturnsTokenAndProfile() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ADMIN\",\"password\":\"Admin@123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.role").value("ADMIN"));
    }

    @Test
    void wrongPasswordIsRejectedWithGenericMessage() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"whatever1A\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid username or password"));
    }

    @Test
    void accountLocksAfterFiveFailures() throws Exception {
        users.save(new UserAccount("locktest", encoder.encode("Correct1pass"), Role.ADMIN, null));
        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"locktest\",\"password\":\"wrong\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"locktest\",\"password\":\"wrong\"}"))
                .andExpect(status().isLocked());
        // even the right password is refused while locked
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"locktest\",\"password\":\"Correct1pass\"}"))
                .andExpect(status().isLocked());
    }

    @Test
    void employeeCannotSeeOtherPeopleOrAdminPages() throws Exception {
        String token = login("employee", "Employee@123");
        mvc.perform(get("/api/employees").header("Authorization", bearer(token))).andExpect(status().isForbidden());
        mvc.perform(get("/api/reports/stats").header("Authorization", bearer(token))).andExpect(status().isForbidden());
        mvc.perform(get("/api/users").header("Authorization", bearer(token))).andExpect(status().isForbidden());
        // but can see their own attendance
        mvc.perform(get("/api/me/attendance?from=2020-01-01&to=2030-01-01").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    @Test
    void managerOnlySeesOwnDepartment() throws Exception {
        String token = login("manager", "Manager@123");
        MvcResult r = mvc.perform(get("/api/employees").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn();
        List<String> depts = JsonPath.read(r.getResponse().getContentAsString(), "$[*].department");
        assertThat(depts).isNotEmpty().containsOnly("Engineering");

        // Arjun Mehta (EMP007) is in Sales -> manager may not check him in
        String all = mvc.perform(get("/api/employees").header("Authorization", bearer(login("admin", "Admin@123"))))
                .andReturn().getResponse().getContentAsString();
        List<Integer> salesIds = JsonPath.read(all, "$[?(@.employeeCode=='EMP007')].id");
        long salesId = salesIds.get(0);
        mvc.perform(post("/api/attendance/check-in").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"employeeId\":" + salesId + "}"))
                .andExpect(status().isForbidden());
        // and cannot create employees
        mvc.perform(post("/api/employees").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeCode\":\"X1\",\"fullName\":\"X\",\"email\":\"x@x.com\",\"department\":\"Engineering\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminUserManagementEnforcesPasswordPolicyAndAudits() throws Exception {
        String token = login("admin", "Admin@123");
        mvc.perform(post("/api/users").header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"weakuser\",\"password\":\"password\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/users").header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newadmin\",\"password\":\"Str0ngPass\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/audit?action=USER_CREATED").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].details").value(org.hamcrest.Matchers.containsString("newadmin")));
    }

    @Test
    void changingPasswordInvalidatesOldToken() throws Exception {
        users.save(new UserAccount("pwtest", encoder.encode("OldPass1x"), Role.ADMIN, null));
        String oldToken = login("pwtest", "OldPass1x");
        mvc.perform(post("/api/auth/change-password").header("Authorization", bearer(oldToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"OldPass1x\",\"newPassword\":\"NewPass2y\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(oldToken))).andExpect(status().isUnauthorized());
        login("pwtest", "NewPass2y");
    }
}
