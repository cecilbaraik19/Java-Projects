package com.cecil.attendance.service;

import com.cecil.attendance.dto.Dtos.EmployeeSummary;
import com.cecil.attendance.dto.Dtos.LatePattern;
import com.cecil.attendance.model.*;
import com.cecil.attendance.repository.AttendanceRepository;
import com.cecil.attendance.repository.EmployeeRepository;
import com.cecil.attendance.repository.NotificationRepository;
import com.cecil.attendance.repository.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Email alerts: daily missing check-in reminders, weekly manager summaries,
 * and notices when leave/corrections are requested or decided.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, d MMM yyyy");

    private final MailService mail;
    private final EmployeeRepository employees;
    private final AttendanceRepository attendance;
    private final UserAccountRepository users;
    private final NotificationRepository outbox;
    private final WorkCalendar calendar;
    private final ReportService reports;
    private final Clock clock;
    private final String adminEmail;
    private final int lateThreshold;

    public AlertService(MailService mail, EmployeeRepository employees, AttendanceRepository attendance,
                        UserAccountRepository users, NotificationRepository outbox, WorkCalendar calendar,
                        ReportService reports, Clock clock,
                        @Value("${attendance.alerts.admin-email:}") String adminEmail,
                        @Value("${attendance.alerts.late-threshold:3}") int lateThreshold) {
        this.mail = mail;
        this.employees = employees;
        this.attendance = attendance;
        this.users = users;
        this.outbox = outbox;
        this.calendar = calendar;
        this.reports = reports;
        this.clock = clock;
        this.adminEmail = adminEmail == null ? "" : adminEmail.trim();
        this.lateThreshold = lateThreshold;
    }

    public String adminEmail() {
        return adminEmail;
    }

    // ---------- scheduled jobs ----------

    @Scheduled(cron = "${attendance.alerts.missing-checkin-cron:0 30 10 * * MON-FRI}")
    public void scheduledMissingCheckIns() {
        int n = sendMissingCheckInReminders(LocalDate.now(clock), LocalTime.now(clock));
        log.info("Missing check-in reminders: {} sent", n);
    }

    @Scheduled(cron = "${attendance.alerts.weekly-summary-cron:0 0 9 * * MON}")
    public void scheduledWeeklySummary() {
        int n = sendWeeklySummaries(LocalDate.now(clock).minusDays(1));
        log.info("Weekly summaries: {} sent", n);
    }

    // ---------- missing check-in ----------

    /**
     * Reminds active employees who have no attendance record for {@code date} and whose shift
     * should already have started by {@code asOf}. Each person gets at most one reminder per day.
     */
    @Transactional
    public int sendMissingCheckInReminders(LocalDate date, LocalTime asOf) {
        if (!calendar.isWorkingDay(date)) return 0;
        Set<Long> withRecord = attendance.findByDateOrderByCheckInAsc(date).stream()
                .map(r -> r.getEmployee().getId()).collect(Collectors.toSet());
        Instant startOfDay = date.atStartOfDay(clock.getZone()).toInstant();
        int sent = 0;
        for (Employee e : employees.findByActiveTrueOrderByFullNameAsc()) {
            if (withRecord.contains(e.getId()) || isBlank(e.getEmail())) continue;
            if (e.getJoinDate() != null && e.getJoinDate().isAfter(date)) continue;
            WorkCalendar.ShiftRules rules = calendar.rulesFor(e);
            if (rules.overnight() || asOf.isBefore(rules.lateAfter())) continue; // shift hasn't started yet
            if (outbox.existsByKindAndRecipientIgnoreCaseAndCreatedAtAfter(Notification.Kind.MISSING_CHECKIN,
                    e.getEmail(), startOfDay)) continue;
            mail.send(e.getEmail(), e.getFullName(), Notification.Kind.MISSING_CHECKIN,
                    "Reminder: you haven't checked in today",
                    "Hi " + firstName(e) + ",\n\n"
                            + "We don't have a check-in for you today (" + date.format(DAY) + ").\n"
                            + "Your shift (" + rules.name() + ") starts at " + rules.start() + ".\n\n"
                            + "If you are at work, please check in on the My Attendance page.\n"
                            + "If you are on leave or forgot, you can apply for leave or request a correction there.\n\n"
                            + "— AttendTrack");
            sent++;
        }
        return sent;
    }

    // ---------- weekly summary ----------

    /** Sends each manager a summary of their department for the 7 days ending {@code end}; plus a company one to the admin email. */
    @Transactional
    public int sendWeeklySummaries(LocalDate end) {
        LocalDate from = end.minusDays(6);
        int sent = 0;
        for (UserAccount u : users.findAllByOrderByUsernameAsc()) {
            if (u.getRole() != Role.MANAGER || !u.isEnabled() || u.getEmployee() == null) continue;
            Employee m = u.getEmployee();
            if (isBlank(m.getEmail())) continue;
            String dept = m.getDepartment();
            mail.send(m.getEmail(), m.getFullName(), Notification.Kind.WEEKLY_SUMMARY,
                    "Weekly attendance: " + dept + " (" + from.format(DAY) + " – " + end.format(DAY) + ")",
                    "Hi " + firstName(m) + ",\n\n" + summaryText(from, end, dept) + "\n— AttendTrack");
            sent++;
        }
        if (!adminEmail.isEmpty()) {
            mail.send(adminEmail, "Admin", Notification.Kind.WEEKLY_SUMMARY,
                    "Weekly attendance: whole company (" + from.format(DAY) + " – " + end.format(DAY) + ")",
                    summaryText(from, end, null) + "\n— AttendTrack");
            sent++;
        }
        return sent;
    }

    private String summaryText(LocalDate from, LocalDate to, String dept) {
        List<EmployeeSummary> rows = reports.summary(from, to, dept);
        if (rows.isEmpty()) return "No attendance data for this period.\n";
        double avg = rows.stream().mapToDouble(EmployeeSummary::attendanceRate).average().orElse(0);
        long late = rows.stream().mapToLong(EmployeeSummary::late).sum();
        long absent = rows.stream().mapToLong(EmployeeSummary::absent).sum();
        double ot = rows.stream().mapToDouble(EmployeeSummary::overtimeHours).sum();
        StringBuilder sb = new StringBuilder();
        sb.append("Attendance rate: ").append(String.format("%.1f", avg)).append("%\n")
                .append("Late arrivals:   ").append(late).append('\n')
                .append("Absences:        ").append(absent).append('\n')
                .append("Overtime:        ").append(String.format("%.1f", ot)).append(" h\n\n");

        List<LatePattern> patterns = reports.latePatterns(from, to, dept, Math.max(2, lateThreshold - 1));
        if (!patterns.isEmpty()) {
            sb.append("Needs attention:\n");
            patterns.stream().limit(5).forEach(p -> sb.append("  • ").append(p.message()).append('\n'));
            sb.append('\n');
        }
        List<EmployeeSummary> lowest = rows.stream()
                .sorted(Comparator.comparingDouble(EmployeeSummary::attendanceRate)).limit(3).toList();
        sb.append("Lowest attendance:\n");
        lowest.forEach(r -> sb.append("  • ").append(r.employeeName()).append(" – ")
                .append(String.format("%.1f", r.attendanceRate())).append("% (")
                .append(r.absent()).append(" absent, ").append(r.late()).append(" late)\n"));
        return sb.toString();
    }

    // ---------- request notifications ----------

    /** Tells the reviewers (department managers, or the admin for a manager's own leave) about a new request. */
    public void leaveRequested(LeaveRequest l) {
        Employee e = l.getEmployee();
        String text = e.getFullName() + " (" + e.getEmployeeCode() + ") has requested " + l.getDays() + " day(s) of "
                + l.getType().name().toLowerCase() + " leave from " + l.getFromDate().format(DAY) + " to "
                + l.getToDate().format(DAY) + ".\n\nReason: " + l.getReason()
                + "\n\nOpen the Approvals page to approve or reject it.\n\n— AttendTrack";
        List<String[]> reviewers = new ArrayList<>();
        for (UserAccount u : users.findAllByOrderByUsernameAsc()) {
            Employee m = u.getEmployee();
            if (u.getRole() == Role.MANAGER && u.isEnabled() && m != null && !m.getId().equals(e.getId())
                    && Objects.equals(m.getDepartment(), e.getDepartment()) && !isBlank(m.getEmail())) {
                reviewers.add(new String[]{m.getEmail(), m.getFullName()});
            }
        }
        if (reviewers.isEmpty() && !adminEmail.isEmpty()) reviewers.add(new String[]{adminEmail, "Admin"});
        for (String[] r : reviewers) {
            mail.send(r[0], r[1], Notification.Kind.LEAVE_REQUEST, "Leave request from " + e.getFullName(), text);
        }
    }

    public void leaveDecided(LeaveRequest l) {
        Employee e = l.getEmployee();
        if (isBlank(e.getEmail())) return;
        boolean ok = l.getStatus() == RequestStatus.APPROVED;
        mail.send(e.getEmail(), e.getFullName(), Notification.Kind.LEAVE_DECISION,
                "Your leave request was " + (ok ? "approved" : "rejected"),
                "Hi " + firstName(e) + ",\n\nYour " + l.getType().name().toLowerCase() + " leave from "
                        + l.getFromDate().format(DAY) + " to " + l.getToDate().format(DAY) + " was "
                        + (ok ? "approved" : "rejected") + " by " + l.getReviewedBy() + "."
                        + (l.getReviewComment() != null ? "\n\nNote: " + l.getReviewComment() : "")
                        + "\n\n— AttendTrack");
    }

    public void correctionDecided(CorrectionRequest c) {
        Employee e = c.getEmployee();
        if (isBlank(e.getEmail())) return;
        boolean ok = c.getStatus() == RequestStatus.APPROVED;
        mail.send(e.getEmail(), e.getFullName(), Notification.Kind.CORRECTION_DECISION,
                "Your attendance correction was " + (ok ? "approved" : "rejected"),
                "Hi " + firstName(e) + ",\n\nYour correction for " + c.getDate().format(DAY) + " ("
                        + c.getRequestedCheckIn() + (c.getRequestedCheckOut() != null ? "–" + c.getRequestedCheckOut() : "")
                        + ") was " + (ok ? "approved" : "rejected") + " by " + c.getReviewedBy() + "."
                        + (c.getReviewComment() != null ? "\n\nNote: " + c.getReviewComment() : "")
                        + "\n\n— AttendTrack");
    }

    private static String firstName(Employee e) {
        String n = e.getFullName() == null ? "" : e.getFullName().trim();
        int sp = n.indexOf(' ');
        return sp > 0 ? n.substring(0, sp) : n;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
