package com.cecil.cloudmonitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CloudMonitorApplication {
    public static void main(String[] args) {
        SpringApplication.run(CloudMonitorApplication.class, args);
    }
}
