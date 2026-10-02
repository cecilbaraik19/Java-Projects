package com.cecil.cloudmonitor.dto;

import jakarta.validation.constraints.NotBlank;

/** action = START, STOP or RESTART */
public record ResourceActionRequest(@NotBlank String action) {
}
