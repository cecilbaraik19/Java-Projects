package com.cecil.attendance;

import com.cecil.attendance.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Health check is public (for Docker) but nothing else under /actuator is; secrets are enforced in prod. */
@SpringBootTest(properties = {
        "attendance.seed-demo-data=false",
        "spring.datasource.url=jdbc:h2:mem:prodready;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class ProductionReadinessTest {

    @Autowired MockMvc mvc;

    @Test
    void healthIsPublicAndDetailsHidden() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void otherActuatorEndpointsAreBlocked() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().is4xxClientError());
        mvc.perform(get("/actuator/beans")).andExpect(status().is4xxClientError());
    }

    @Test
    void jwtSecretIsRequiredWhenConfigured() {
        assertThatThrownBy(() -> new JwtService("", 60, true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtService("too-short", 60, false)).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new JwtService("a-long-enough-secret-of-32-chars-or-more!", 60, true)).doesNotThrowAnyException();
    }
}
