package com.cecil.docqa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** All "docqa.*" settings from application.yml in one place. */
@ConfigurationProperties(prefix = "docqa")
public record DocQaProperties(Chunk chunk, Retrieval retrieval, Ai ai, Cors cors) {

    public record Chunk(int size, int overlap) {}

    public record Retrieval(int topK) {}

    public record Ai(String apiKey, String model, String baseUrl, int maxTokens, int timeoutSeconds) {
        public boolean enabled() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    public record Cors(String allowedOrigins) {}
}
