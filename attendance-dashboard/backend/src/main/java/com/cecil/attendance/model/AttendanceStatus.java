package com.cecil.attendance.model;

public enum AttendanceStatus {
    PRESENT,
    LATE,
    HALF_DAY,
    ABSENT,
    ON_LEAVE;

    /** True if the employee physically showed up that day. */
    public boolean attended() {
        return this == PRESENT || this == LATE || this == HALF_DAY;
    }
}
