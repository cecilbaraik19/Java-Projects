package com.cecil.attendance.controller;

import com.cecil.attendance.dto.Dtos.AlertSettingsView;
import com.cecil.attendance.dto.Dtos.NotificationView;
import com.cecil.attendance.dto.Dtos.RunResult;
import com.cecil.attendance.model.Notification;
import com.cecil.attendance.repository.NotificationRepository;
import com.cecil.attendance.service.AlertService;
import com.cecil.attendance.service.MailService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Admin view of the email outbox, plus buttons to run the scheduled alerts on demand. */
@RestController
@RequestMapping("/api/notifications")
@PreAuthorize("hasRole('ADMIN')")
public class NotificationController {

    private final NotificationRepository outbox;
    private final AlertService alerts;
    private final MailService mail;
    private final Clock clock;
    private final boolean alertsEnabled;
    private final String missingCron;
    private final String weeklyCron;
    private final int lateThreshold;

    public NotificationController(NotificationRepository outbox, AlertService alerts, MailService mail, Clock clock,
                                  @Value("${attendance.alerts.enabled:true}") boolean alertsEnabled,
                                  @Value("${attendance.alerts.missing-checkin-cron:0 30 10 * * MON-FRI}") String missingCron,
                                  @Value("${attendance.alerts.weekly-summary-cron:0 0 9 * * MON}") String weeklyCron,
                                  @Value("${attendance.alerts.late-threshold:3}") int lateThreshold) {
        this.outbox = outbox;
        this.alerts = alerts;
        this.mail = mail;
        this.clock = clock;
        this.alertsEnabled = alertsEnabled;
        this.missingCron = missingCron;
        this.weeklyCron = weeklyCron;
        this.lateThreshold = lateThreshold;
    }

    @GetMapping
    public List<NotificationView> list(@RequestParam(required = false) String kind) {
        List<Notification> list = kind == null || kind.isBlank()
                ? outbox.findTop200ByOrderByCreatedAtDesc()
                : outbox.findTop200ByKindOrderByCreatedAtDesc(Notification.Kind.valueOf(kind.toUpperCase()));
        return list.stream().map(NotificationView::of).toList();
    }

    @GetMapping("/settings")
    public AlertSettingsView settings() {
        return new AlertSettingsView(mail.isSmtpConfigured(), mail.from(), alertsEnabled, missingCron, weeklyCron,
                alerts.adminEmail(), lateThreshold);
    }

    @PostMapping("/run/missing-checkin")
    public RunResult runMissingCheckIn() {
        int n = alerts.sendMissingCheckInReminders(LocalDate.now(clock), LocalTime.now(clock));
        return new RunResult(n, n == 0
                ? "No reminders needed right now (weekend/holiday, everyone checked in, or already reminded today)"
                : n + " reminder(s) " + (mail.isSmtpConfigured() ? "sent" : "logged"));
    }

    @PostMapping("/run/weekly-summary")
    public RunResult runWeeklySummary() {
        int n = alerts.sendWeeklySummaries(LocalDate.now(clock).minusDays(1));
        return new RunResult(n, n == 0 ? "No managers with an email address to send to"
                : n + " summary email(s) " + (mail.isSmtpConfigured() ? "sent" : "logged"));
    }
}
