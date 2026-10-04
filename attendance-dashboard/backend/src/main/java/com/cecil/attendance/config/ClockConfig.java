package com.cecil.attendance.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /** Injected everywhere "now" is needed, so tests can use a fixed clock. */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
