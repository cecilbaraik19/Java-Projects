package com.cecil.docqa.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Lets the React dev server (port 5173) call the API. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final DocQaProperties props;

    public WebConfig(DocQaProperties props) {
        this.props = props;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(props.cors().allowedOrigins().split(","))
                .allowedMethods("GET", "POST", "DELETE");
    }
}
