package com.cecil.attendance.service;

import com.cecil.attendance.model.Notification;
import com.cecil.attendance.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Sends plain-text alert emails. When no SMTP server is configured (no spring.mail.host),
 * messages are only stored in the notifications outbox, so the feature can be tried without email.
 * Plain text on purpose: no HTML means nothing in a name or reason can be turned into a phishing link.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final NotificationRepository outbox;
    private final ObjectProvider<JavaMailSender> senderProvider;
    private final String from;
    private final boolean hostSet;

    public MailService(NotificationRepository outbox, ObjectProvider<JavaMailSender> senderProvider,
                       @Value("${attendance.mail.from:AttendTrack <no-reply@attendtrack.local>}") String from,
                       @Value("${spring.mail.host:}") String host) {
        this.outbox = outbox;
        this.senderProvider = senderProvider;
        this.from = from;
        // Docker passes SPRING_MAIL_HOST="" when email is not set up; treat blank as "no SMTP"
        this.hostSet = host != null && !host.isBlank();
    }

    private JavaMailSender sender() {
        return hostSet ? senderProvider.getIfAvailable() : null;
    }

    public boolean isSmtpConfigured() {
        return sender() != null;
    }

    public String from() {
        return from;
    }

    /** Sends (or logs) one email and records it in the outbox. Never throws. */
    public Notification send(String to, String toName, Notification.Kind kind, String subject, String body) {
        // strip line breaks from the subject (header-injection safety)
        String cleanSubject = subject == null ? "" : subject.replaceAll("[\\r\\n]+", " ");
        Notification n = new Notification(to, truncate(toName, 100), kind, truncate(cleanSubject, 200), truncate(body, 4000));
        JavaMailSender sender = sender();
        if (sender == null) {
            n.setStatus(Notification.Status.LOGGED);
        } else {
            try {
                SimpleMailMessage msg = new SimpleMailMessage();
                msg.setFrom(from);
                msg.setTo(to);
                msg.setSubject(n.getSubject());
                msg.setText(n.getBody());
                sender.send(msg);
                n.setStatus(Notification.Status.SENT);
            } catch (RuntimeException e) {
                log.warn("Could not send email to {}: {}", to, e.getMessage());
                n.setStatus(Notification.Status.FAILED);
                n.setError(truncate(e.getMessage(), 300));
            }
        }
        return outbox.save(n);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
