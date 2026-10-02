package com.cecil.cloudmonitor.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record EmailSettingsRequest(
        boolean enabled,
        @NotNull @Size(max = 5, message = "Up to 5 recipients")
        List<@Pattern(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", message = "Invalid email address") String> recipients,
        @NotNull @Pattern(regexp = "INFO|WARNING|CRITICAL", message = "Severity must be INFO, WARNING or CRITICAL")
        String minSeverity
) {
}
