package com.cecil.attendance.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on the scheduled alert jobs; set attendance.alerts.enabled=false to switch them off. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "attendance.alerts.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
