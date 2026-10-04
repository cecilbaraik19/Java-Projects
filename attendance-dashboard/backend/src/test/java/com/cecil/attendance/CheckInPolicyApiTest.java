package com.cecil.attendance;

import com.cecil.attendance.model.CheckInSettings;
import com.cecil.attendance.model.Employee;
import com.cecil.attendance.model.Role;
import com.cecil.attendance.model.UserAccount;
import com.cecil.attendance.repository.CheckInSettingsRepository;
import com.cecil.attendance.repository.EmployeeRepository;
import com.cecil.attendance.repository.UserAccountRepository;
import com.cecil.attendance.security.IpUtils;
import com.cecil.attendance.service.CheckInPolicyService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** QR code, geofence and office-network rules for self check-in. */
@SpringBootTest(properties = {
        "attendance.seed-demo-data=true",
        "spring.datasource.url=jdbc:h2:mem:policytest;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class CheckInPolicyApiTest {

    // Amity University Jharkhand, Ranchi (approx.)
    static final double OFFICE_LAT = 23.3441;
    static final double OFFICE_LNG = 85.3096;

    @Autowired MockMvc mvc;
    @Autowired EmployeeRepository employees;
    @Autowired UserAccountRepository users;
    @Autowired CheckInSettingsRepository settingsRepo;
    @Autowired PasswordEncoder encoder;

    String admin;

    @BeforeEach
    void resetRules() throws Exception {
        admin = token("admin", "Admin@123");
        saveSettings(false, false, false, "");
    }

    private void saveSettings(boolean qr, boolean location, boolean network, String nets) throws Exception {
        mvc.perform(put("/api/settings/checkin").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requireQr\":" + qr + ",\"qrRotationSeconds\":30,\"requireLocation\":" + location
                                + ",\"officeLatitude\":" + OFFICE_LAT + ",\"officeLongitude\":" + OFFICE_LNG
                                + ",\"radiusMeters\":200,\"requireNetwork\":" + network
                                + ",\"allowedNetworks\":\"" + nets + "\"}"))
                .andExpect(status().isOk());
    }

    private String token(String user, String pass) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.token");
    }

    private String newEmployee() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Employee e = employees.save(new Employee("P" + tag, "Policy " + tag, tag + "@p.com", "Engineering", "Dev",
                LocalDate.now().minusYears(1)));
        users.save(new UserAccount("p" + tag, encoder.encode("Passw0rd!"), Role.EMPLOYEE, e));
        return token("p" + tag, "Passw0rd!");
    }

    private static MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder b, String remoteAddr) {
        return b.with(r -> { r.setRemoteAddr(remoteAddr); return r; });
    }

    @Test
    void noRulesMeansPlainCheckInWorks() throws Exception {
        mvc.perform(post("/api/me/check-in").header("Authorization", newEmployee()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkInVerification").value(org.hamcrest.Matchers.startsWith("Self")));
    }

    @Test
    void networkRuleUsesRealIpAndIgnoresSpoofedHeader() throws Exception {
        saveSettings(false, false, true, "10.0.0.0/8");

        // direct connection from outside the office
        mvc.perform(from(post("/api/me/check-in"), "203.0.113.9").header("Authorization", newEmployee()))
                .andExpect(status().isForbidden());

        // attacker on the internet fakes X-Forwarded-For - ignored because they are not a trusted proxy
        mvc.perform(from(post("/api/me/check-in"), "203.0.113.9").header("X-Forwarded-For", "10.1.2.3")
                        .header("Authorization", newEmployee()))
                .andExpect(status().isForbidden());

        // request relayed by the trusted local proxy (Vite) for an office PC
        mvc.perform(from(post("/api/me/check-in"), "127.0.0.1").header("X-Forwarded-For", "10.1.2.3")
                        .header("Authorization", newEmployee()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkInVerification").value(org.hamcrest.Matchers.containsString("IP 10.1.2.3")));

        // direct office connection
        mvc.perform(from(post("/api/me/check-in"), "10.20.30.40").header("Authorization", newEmployee()))
                .andExpect(status().isOk());
    }

    @Test
    void locationRuleChecksDistance() throws Exception {
        saveSettings(false, true, false, "");
        String far = "{\"latitude\":23.3700,\"longitude\":85.3250,\"accuracy\":15}"; // ~3 km away
        mvc.perform(post("/api/me/check-in").header("Authorization", newEmployee())
                        .contentType(MediaType.APPLICATION_JSON).content(far))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("km from the office")));

        mvc.perform(post("/api/me/check-in").header("Authorization", newEmployee()))
                .andExpect(status().isForbidden()); // no location at all

        String near = "{\"latitude\":23.3445,\"longitude\":85.3099,\"accuracy\":20}"; // ~50 m away
        mvc.perform(post("/api/me/check-in").header("Authorization", newEmployee())
                        .contentType(MediaType.APPLICATION_JSON).content(near))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkInVerification").value(org.hamcrest.Matchers.containsString("GPS")));
    }

    @Test
    void qrRuleNeedsCurrentCodeAndRateLimitsGuessing() throws Exception {
        saveSettings(true, false, false, "");
        String code = JsonPath.read(mvc.perform(get("/api/kiosk/code").header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.code");

        String emp = newEmployee();
        mvc.perform(post("/api/me/check-in").header("Authorization", emp).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qrCode\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkInVerification").value(org.hamcrest.Matchers.containsString("QR")));

        String guesser = newEmployee();
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/me/check-in").header("Authorization", guesser).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"qrCode\":\"" + wrong + "\"}"))
                    .andExpect(status().isForbidden());
        }
        // 6th try is blocked even with the right code
        mvc.perform(post("/api/me/check-in").header("Authorization", guesser).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qrCode\":\"" + code + "\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void employeesCannotReadKioskCodeOrSettings() throws Exception {
        String emp = newEmployee();
        mvc.perform(get("/api/kiosk/code").header("Authorization", emp)).andExpect(status().isForbidden());
        mvc.perform(get("/api/settings/checkin").header("Authorization", emp)).andExpect(status().isForbidden());
        // and the secret never appears in the settings response
        String body = mvc.perform(get("/api/settings/checkin").header("Authorization", admin))
                .andReturn().getResponse().getContentAsString();
        CheckInSettings s = settingsRepo.findById(CheckInSettings.SINGLETON_ID).orElseThrow();
        assertThat(body).doesNotContain(s.getQrSecret()).doesNotContain("qrSecret");
    }

    @Test
    void invalidSettingsAreRejected() throws Exception {
        mvc.perform(put("/api/settings/checkin").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requireQr\":false,\"qrRotationSeconds\":30,\"requireLocation\":false,\"radiusMeters\":200,"
                                + "\"requireNetwork\":true,\"allowedNetworks\":\"not-an-ip\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/settings/checkin").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requireQr\":false,\"qrRotationSeconds\":30,\"requireLocation\":true,\"radiusMeters\":200,"
                                + "\"requireNetwork\":false}"))
                .andExpect(status().isBadRequest()); // location rule without office coordinates
    }

    @Test
    void cidrMatching() {
        var ranges = IpUtils.parseList("192.168.1.0/24, 10.0.0.5, 2001:db8::/32");
        assertThat(IpUtils.matchesAny("192.168.1.77", ranges)).isTrue();
        assertThat(IpUtils.matchesAny("192.168.2.1", ranges)).isFalse();
        assertThat(IpUtils.matchesAny("10.0.0.5", ranges)).isTrue();
        assertThat(IpUtils.matchesAny("::ffff:192.168.1.9", ranges)).isTrue();
        assertThat(IpUtils.matchesAny("2001:db8:1::1", ranges)).isTrue();
        assertThat(IpUtils.matchesAny("example.com", ranges)).isFalse();
        assertThatThrownBy(() -> IpUtils.parseCidr("10.0.0.0/33")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void haversineDistance() {
        double d = CheckInPolicyService.distanceMeters(OFFICE_LAT, OFFICE_LNG, 23.3445, 85.3099);
        assertThat(d).isBetween(40.0, 70.0);
    }
}
