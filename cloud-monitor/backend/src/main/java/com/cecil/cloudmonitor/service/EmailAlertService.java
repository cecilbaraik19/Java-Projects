package com.cecil.cloudmonitor.service;

import com.cecil.cloudmonitor.dto.EmailSettingsDto;
import com.cecil.cloudmonitor.dto.EmailSettingsRequest;
import com.cecil.cloudmonitor.model.Alert;
import com.cecil.cloudmonitor.model.AppSetting;
import com.cecil.cloudmonitor.model.Severity;
import com.cecil.cloudmonitor.repository.AppSettingRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends an email when an alert fires (by default only CRITICAL ones).
 * Emails are sent on a background thread so the 3-second monitoring loop never waits for Gmail.
 */
@Service
public class EmailAlertService {

    private static final Logger log = LoggerFactory.getLogger(EmailAlertService.class);
    private static final String KEY_ENABLED = "email.enabled";
    private static final String KEY_RECIPIENTS = "email.recipients";
    private static final String KEY_MIN_SEVERITY = "email.min-severity";
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm:ss", Locale.ENGLISH).withZone(ZoneId.systemDefault());

    private final JavaMailSender mailSender;
    private final AppSettingRepository settings;
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "email-alerts");
        t.setDaemon(true);
        return t;
    });

    /** Times of emails sent in the last hour, for the rate limit. */
    private final Deque<Instant> sent = new ConcurrentLinkedDeque<>();
    private volatile Instant lastSentAt;
    private volatile String lastError;

    @Value("${spring.mail.username:}")
    private String username;

    @Value("${spring.mail.password:}")
    private String password;

    @Value("${app.mail.max-per-hour:20}")
    private int maxPerHour;

    @Value("${app.public-url:http://localhost:5173}")
    private String publicUrl;

    public EmailAlertService(JavaMailSender mailSender, AppSettingRepository settings) {
        this.mailSender = mailSender;
        this.settings = settings;
    }

    /**
     * Cleans up the Gmail login at startup and logs what was found (never the password itself).
     * Also accepts variables typed with stray spaces (" MAIL_USERNAME") and App passwords copied
     * with spaces ("abcd efgh ijkl mnop"), which are common copy-paste mistakes.
     */
    @PostConstruct
    void checkSetup() {
        if (username == null || username.isBlank()) username = env("MAIL_USERNAME");
        if (password == null || password.isBlank()) password = env("MAIL_PASSWORD");
        username = username == null ? "" : username.trim();
        password = password == null ? "" : password.replaceAll("\\s+", "");

        if (mailSender instanceof org.springframework.mail.javamail.JavaMailSenderImpl impl) {
            impl.setUsername(username);
            impl.setPassword(password);
        }

        if (isConfigured()) {
            log.info("Email alerts: ready, sending from {} (password length {})", username, password.length());
            if (password.length() != 16) {
                log.warn("Email alerts: a Gmail App password has 16 letters, but MAIL_PASSWORD has {}", password.length());
            }
        } else {
            List<String> names = System.getenv().keySet().stream()
                    .filter(k -> k.toUpperCase(Locale.ROOT).contains("MAIL")).sorted().toList();
            log.warn("Email alerts: NOT set up. MAIL_USERNAME {}, MAIL_PASSWORD {}. Mail-related variables this process can see: {}",
                    username.isBlank() ? "missing" : "found", password.isBlank() ? "missing" : "found", names);
        }
    }

    /** Reads an environment variable, ignoring stray spaces around its name. */
    private static String env(String name) {
        String exact = System.getenv(name);
        if (exact != null) return exact;
        return System.getenv().entrySet().stream()
                .filter(e -> e.getKey().trim().equalsIgnoreCase(name))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ settings

    public boolean isConfigured() {
        return username != null && !username.isBlank() && password != null && !password.isBlank();
    }

    public EmailSettingsDto settings() {
        return new EmailSettingsDto(enabled(), recipients(), minSeverity().name(), isConfigured(),
                isConfigured() ? username : null, maxPerHour, sentLastHour(),
                lastSentAt == null ? null : lastSentAt.toEpochMilli(), lastError);
    }

    public EmailSettingsDto update(EmailSettingsRequest req) {
        List<String> clean = req.recipients().stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        if (req.enabled() && clean.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Add at least one recipient before turning emails on");
        }
        settings.save(new AppSetting(KEY_ENABLED, String.valueOf(req.enabled())));
        settings.save(new AppSetting(KEY_RECIPIENTS, String.join(",", clean)));
        settings.save(new AppSetting(KEY_MIN_SEVERITY, req.minSeverity()));
        return settings();
    }

    private boolean enabled() {
        return Boolean.parseBoolean(get(KEY_ENABLED, "false"));
    }

    /** Defaults to the sender's own address, so emails go to yourself until you change it. */
    private List<String> recipients() {
        String raw = get(KEY_RECIPIENTS, isConfigured() ? username : "");
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private Severity minSeverity() {
        try {
            return Severity.valueOf(get(KEY_MIN_SEVERITY, "CRITICAL"));
        } catch (IllegalArgumentException e) {
            return Severity.CRITICAL;
        }
    }

    private String get(String key, String fallback) {
        return settings.findById(key).map(AppSetting::getValue).orElse(fallback);
    }

    // ------------------------------------------------------------------ sending

    /** Called for every new alert. Decides whether to email and, if so, sends in the background. */
    public void onAlert(Alert a) {
        if (!isConfigured() || !enabled()) return;
        if (a.getSeverity() == null || a.getSeverity().ordinal() < minSeverity().ordinal()) return;
        List<String> to = recipients();
        if (to.isEmpty()) return;
        if (sentLastHour() >= maxPerHour) {
            log.warn("Email limit of {}/hour reached, not emailing: {}", maxPerHour, a.getMessage());
            return;
        }
        sent.addLast(Instant.now());
        sender.submit(() -> {
            try {
                send(to, subject(a), alertHtml(a));
                lastSentAt = Instant.now();
                lastError = null;
                log.info("Alert email sent to {}: {}", to, a.getMessage());
            } catch (Exception e) {
                lastError = friendly(e);
                log.warn("Could not send alert email: {}", lastError);
            }
        });
    }

    /** Sends a test email right away and reports any problem to the caller. */
    public EmailSettingsDto sendTest() {
        if (!isConfigured()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Email is not set up. Add MAIL_USERNAME and MAIL_PASSWORD to the run configuration and restart.");
        }
        List<String> to = recipients();
        if (to.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Add at least one recipient first");
        try {
            send(to, "[CloudPulse] Test email", page("#2a78d6", "Test email",
                    "<p style=\"margin:0 0 12px\">Email alerts are working. 🎉</p>"
                            + "<p style=\"margin:0;color:#6e6d68\">From now on CloudPulse will email "
                            + esc(String.join(", ", to)) + " when a " + minSeverity().name().toLowerCase(Locale.ROOT)
                            + " (or worse) alert fires.</p>", null));
            lastSentAt = Instant.now();
            lastError = null;
            sent.addLast(Instant.now());
        } catch (Exception e) {
            lastError = friendly(e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, lastError);
        }
        return settings();
    }

    private void send(List<String> to, String subject, String html) throws Exception {
        MimeMessage msg = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(msg, false, "UTF-8");
        helper.setFrom(username, "CloudPulse Alerts");
        helper.setTo(to.toArray(String[]::new));
        helper.setSubject(subject);
        helper.setText(html, true);
        mailSender.send(msg);
    }

    private int sentLastHour() {
        Instant hourAgo = Instant.now().minus(Duration.ofHours(1));
        while (!sent.isEmpty() && sent.peekFirst().isBefore(hourAgo)) sent.pollFirst();
        return sent.size();
    }

    // ------------------------------------------------------------------ email content

    private static String subject(Alert a) {
        return "[CloudPulse] " + a.getSeverity().name() + ": " + a.getMessage();
    }

    private String alertHtml(Alert a) {
        String color = switch (a.getSeverity()) {
            case CRITICAL -> "#d03b3b";
            case WARNING -> "#b07800";
            default -> "#2a78d6";
        };
        StringBuilder rows = new StringBuilder();
        row(rows, "Resource", a.getResourceName());
        row(rows, "Rule", a.getRuleName());
        if (a.getMetric() != null) {
            row(rows, "Value", String.format(Locale.ENGLISH, "%.1f (limit %.0f)", a.getValue(), a.getThreshold()));
        }
        row(rows, "Time", a.getCreatedAt() == null ? "-" : WHEN.format(a.getCreatedAt()));
        String body = "<p style=\"margin:0 0 16px;font-size:16px;font-weight:600\">" + esc(a.getMessage()) + "</p>"
                + "<table style=\"border-collapse:collapse;width:100%;font-size:14px\">" + rows + "</table>";
        String link = a.getResourceId() == null ? publicUrl + "/alerts" : publicUrl + "/resources/" + a.getResourceId();
        return page(color, a.getSeverity().name() + " alert", body, link);
    }

    private static void row(StringBuilder sb, String label, String value) {
        sb.append("<tr><td style=\"padding:6px 0;color:#6e6d68;width:110px\">").append(esc(label))
                .append("</td><td style=\"padding:6px 0;font-weight:600\">").append(esc(value)).append("</td></tr>");
    }

    /** Simple, email-client-safe HTML layout with a coloured top bar. */
    private static String page(String color, String heading, String body, String link) {
        String button = link == null ? "" :
                "<p style=\"margin:24px 0 0\"><a href=\"" + esc(link) + "\" style=\"background:#2a78d6;color:#ffffff;"
                        + "text-decoration:none;padding:10px 16px;border-radius:8px;font-weight:600;display:inline-block\">"
                        + "Open in CloudPulse</a></p>";
        return "<!doctype html><html><body style=\"margin:0;background:#f4f4f1;font-family:Segoe UI,Arial,sans-serif;color:#0b0b0b\">"
                + "<div style=\"max-width:560px;margin:24px auto;background:#ffffff;border:1px solid #e1e0d9;border-radius:12px;overflow:hidden\">"
                + "<div style=\"height:6px;background:" + color + "\"></div>"
                + "<div style=\"padding:24px\">"
                + "<p style=\"margin:0 0 4px;color:#2a78d6;font-weight:700;font-size:13px\">CloudPulse</p>"
                + "<h1 style=\"margin:0 0 16px;font-size:20px;color:" + color + "\">" + esc(heading) + "</h1>"
                + body + button
                + "</div></div>"
                + "<p style=\"text-align:center;color:#898781;font-size:12px\">You get this because email alerts are on in CloudPulse.</p>"
                + "</body></html>";
    }

    private static String esc(String s) {
        return s == null ? "" : HtmlUtils.htmlEscape(s);
    }

    /** Turns common mail errors into a sentence a person can act on. */
    private static String friendly(Exception e) {
        String m = String.valueOf(e.getMessage());
        String lower = m.toLowerCase(Locale.ROOT);
        if (lower.contains("authentication") || lower.contains("535") || lower.contains("username and password")) {
            return "Gmail rejected the login. Check MAIL_USERNAME and that MAIL_PASSWORD is a 16-character App password (not your normal password).";
        }
        if (lower.contains("connect") || lower.contains("timed out") || lower.contains("unknownhost")) {
            return "Could not reach smtp.gmail.com. Check your internet connection.";
        }
        return m.length() > 300 ? m.substring(0, 300) : m;
    }

    @PreDestroy
    void shutdown() {
        sender.shutdown();
    }
}
