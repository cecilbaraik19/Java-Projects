package com.cecil.cloudmonitor.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

public record BudgetRequest(
        @DecimalMin(value = "1", message = "Budget must be at least $1")
        @DecimalMax(value = "10000000", message = "Budget is too large")
        double monthlyBudget
) {
}
