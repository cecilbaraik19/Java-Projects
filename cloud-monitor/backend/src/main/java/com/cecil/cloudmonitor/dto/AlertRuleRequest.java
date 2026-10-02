package com.cecil.cloudmonitor.dto;

import com.cecil.cloudmonitor.model.MetricType;
import com.cecil.cloudmonitor.model.Severity;
import jakarta.validation.constraints.*;

public record AlertRuleRequest(
        @NotBlank @Size(max = 80) String name,
        @NotNull MetricType metric,
        @NotBlank @Pattern(regexp = "[<>]") String operator,
        @PositiveOrZero double threshold,
        Long resourceId,
        @NotNull Severity severity,
        Boolean enabled
) {
}
