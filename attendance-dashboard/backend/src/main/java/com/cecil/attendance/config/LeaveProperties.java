package com.cecil.attendance.config;

import com.cecil.attendance.model.LeaveType;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Yearly leave allowance per type (days). Unpaid leave has no limit. */
@ConfigurationProperties(prefix = "attendance.leave")
public record LeaveProperties(Integer casual, Integer sick, Integer earned) {

    public LeaveProperties {
        if (casual == null) casual = 12;
        if (sick == null) sick = 10;
        if (earned == null) earned = 15;
    }

    /** Days allowed per year, or null when unlimited. */
    public Integer allowance(LeaveType type) {
        return switch (type) {
            case CASUAL -> casual;
            case SICK -> sick;
            case EARNED -> earned;
            case UNPAID -> null;
        };
    }
}
