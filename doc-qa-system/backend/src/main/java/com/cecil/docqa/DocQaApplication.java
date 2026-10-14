package com.cecil.docqa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DocQaApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocQaApplication.class, args);
    }
}
