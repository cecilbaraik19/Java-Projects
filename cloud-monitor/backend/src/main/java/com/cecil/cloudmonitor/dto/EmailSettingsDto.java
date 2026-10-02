package com.cecil.cloudmonitor.dto;

import java.util.List;

/** Email alert settings plus live status, shown on the Alerts → Email tab. */
public record EmailSettingsDto(
        boolean enabled,
        List<String> recipients,
        String minSeverity,
        boolean configured,      // MAIL_USERNAME and MAIL_PASSWORD are set
        String sender,
        int maxPerHour,
        int sentLastHour,
        Long lastSentAt,
        String lastError
) {
}
