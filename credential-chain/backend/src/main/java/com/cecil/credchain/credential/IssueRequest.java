package com.cecil.credchain.credential;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record IssueRequest(
        @NotBlank @Size(max = 60) String credentialType,
        @NotBlank @Size(max = 120) String studentName,
        @NotBlank @Size(max = 60) String studentId,
        @NotBlank @Size(max = 120) String program,
        @Size(max = 120) String major,
        @NotBlank @Size(max = 30) String grade,
        @Pattern(regexp = "^$|\\d{4}-\\d{2}-\\d{2}", message = "must be yyyy-MM-dd") String issueDate) {}
