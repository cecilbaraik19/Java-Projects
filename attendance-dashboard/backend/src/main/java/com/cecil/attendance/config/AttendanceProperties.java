package com.cecil.attendance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@ConfigurationProperties(prefix = "attendance")
public record AttendanceProperties(
        String officeStart,
        int lateGraceMinutes,
        double halfDayHours,
        boolean seedDemoData,
        List<String> corsOrigins,
        List<DayOfWeek> weekendDays
) {
    public AttendanceProperties {
        if (weekendDays == null) weekendDays = List.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
        if (officeStart == null || officeStart.isBlank()) officeStart = "09:30";
        if (lateGraceMinutes <= 0) lateGraceMinutes = 15;
        if (halfDayHours <= 0) halfDayHours = 4.5;
        if (corsOrigins == null || corsOrigins.isEmpty()) corsOrigins = List.of("http://localhost:5173");
    }

    public boolean isWorkingDay(LocalDate date) {
        return !weekendDays.contains(date.getDayOfWeek());
    }

    /** Official start of the working day, e.g. 09:30. */
    public LocalTime officeStartTime() {
        return LocalTime.parse(officeStart.trim());
    }

    /** Check-ins after this time are marked LATE. */
    public LocalTime lateAfter() {
        return officeStartTime().plusMinutes(lateGraceMinutes);
    }
}
