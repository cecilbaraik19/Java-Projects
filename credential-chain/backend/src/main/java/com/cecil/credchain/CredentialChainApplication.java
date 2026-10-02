package com.cecil.credchain;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CredentialChainApplication {
    public static void main(String[] args) {
        SpringApplication.run(CredentialChainApplication.class, args);
    }
}
