package com.cecil.cloudmonitor.dto;

/**
 * One log line. level is DEBUG, INFO, WARN or ERROR.
 * source is the program that wrote it (nginx, postgres, cloudpulse ...).
 */
public record LogEntry(
        long id,
        long timestamp,
        String level,
        Long resourceId,
        String resourceName,
        String source,
        String message
) {
}
