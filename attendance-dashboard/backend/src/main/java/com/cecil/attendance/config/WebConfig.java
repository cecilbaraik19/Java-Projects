package com.cecil.attendance.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AttendanceProperties props;

    public WebConfig(AttendanceProperties props) {
        this.props = props;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(props.corsOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS");
    }
}
