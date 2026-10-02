package com.cecil.cloudmonitor.service;

import com.cecil.cloudmonitor.dto.AlertRuleRequest;
import com.cecil.cloudmonitor.model.*;
import com.cecil.cloudmonitor.repository.AlertRepository;
import com.cecil.cloudmonitor.repository.AlertRuleRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Checks alert rules against the latest metrics and records / pushes alerts. */
@Service
public class AlertService {

    /** Don't repeat the same rule+resource alert more often than this. */
    private static final Duration COOLDOWN = Duration.ofMinutes(2);

    private final AlertRepository alerts;
    private final AlertRuleRepository rules;
    private final SimpMessagingTemplate messaging;
    private final EmailAlertService email;
    private final Map<String, Instant> lastFired = new ConcurrentHashMap<>();

    public AlertService(AlertRepository alerts, AlertRuleRepository rules, SimpMessagingTemplate messaging,
                        EmailAlertService email) {
        this.alerts = alerts;
        this.rules = rules;
        this.messaging = messaging;
        this.email = email;
    }

    public void evaluate(List<CloudResource> resources) {
        List<AlertRule> active = rules.findByEnabledTrue();
        Instant now = Instant.now();
        for (AlertRule rule : active) {
            for (CloudResource r : resources) {
                if (r.isStopped()) continue;
                if (rule.getResourceId() != null && !rule.getResourceId().equals(r.getId())) continue;

                double value = valueOf(r, rule.getMetric());
                boolean breached = ">".equals(rule.getOperator())
                        ? value > rule.getThreshold()
                        : value < rule.getThreshold();
                if (!breached) continue;

                String key = rule.getId() + ":" + r.getId();
                Instant last = lastFired.get(key);
                if (last != null && last.plus(COOLDOWN).isAfter(now)) continue;
                lastFired.put(key, now);

                Alert a = new Alert();
                a.setRuleId(rule.getId());
                a.setRuleName(rule.getName());
                a.setResourceId(r.getId());
                a.setResourceName(r.getName());
                a.setMetric(rule.getMetric());
                a.setValue(value);
                a.setThreshold(rule.getThreshold());
                a.setSeverity(rule.getSeverity());
                a.setMessage(String.format("%s %s is %.1f%s (%s %.0f)", r.getName(), label(rule.getMetric()),
                        value, unit(rule.getMetric()), rule.getOperator(), rule.getThreshold()));
                a.setCreatedAt(now);
                publish(alerts.save(a));
            }
        }
    }

    /** Events that aren't metric-based, e.g. "stopped by admin". */
    public void raiseEvent(CloudResource r, Severity severity, String message) {
        Alert a = new Alert();
        a.setResourceId(r.getId());
        a.setResourceName(r.getName());
        a.setRuleName("System event");
        a.setSeverity(severity);
        a.setMessage(message);
        a.setCreatedAt(Instant.now());
        publish(alerts.save(a));
    }

    private void publish(Alert a) {
        messaging.convertAndSend("/topic/alerts", a);
        email.onAlert(a);
    }

    // ---------- queries & actions ----------

    public List<Alert> recent(int limit) {
        return alerts.findAllByOrderByCreatedAtDesc(PageRequest.of(0, Math.min(Math.max(limit, 1), 500)));
    }

    public List<Alert> active() {
        return alerts.findByAcknowledgedFalseOrderByCreatedAtDesc();
    }

    public List<Alert> forResource(Long resourceId, int limit) {
        return alerts.findByResourceIdOrderByCreatedAtDesc(resourceId, PageRequest.of(0, Math.min(limit, 200)));
    }

    public long activeCount() {
        return alerts.countByAcknowledgedFalse();
    }

    public Alert acknowledge(Long id, String username) {
        Alert a = alerts.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        a.setAcknowledged(true);
        a.setAcknowledgedBy(username);
        return alerts.save(a);
    }

    public int acknowledgeAll(String username) {
        List<Alert> list = alerts.findByAcknowledgedFalseOrderByCreatedAtDesc();
        list.forEach(a -> { a.setAcknowledged(true); a.setAcknowledgedBy(username); });
        alerts.saveAll(list);
        return list.size();
    }

    public List<AlertRule> rules() {
        return rules.findAll();
    }

    public AlertRule createRule(AlertRuleRequest req) {
        AlertRule rule = new AlertRule();
        apply(rule, req);
        return rules.save(rule);
    }

    public AlertRule updateRule(Long id, AlertRuleRequest req) {
        AlertRule rule = rules.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        apply(rule, req);
        return rules.save(rule);
    }

    public void deleteRule(Long id) {
        if (!rules.existsById(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        rules.deleteById(id);
    }

    private static void apply(AlertRule rule, AlertRuleRequest req) {
        rule.setName(req.name().trim());
        rule.setMetric(req.metric());
        rule.setOperator(req.operator());
        rule.setThreshold(req.threshold());
        rule.setResourceId(req.resourceId());
        rule.setSeverity(req.severity());
        rule.setEnabled(req.enabled() == null || req.enabled());
    }

    static double valueOf(CloudResource r, MetricType m) {
        return switch (m) {
            case CPU -> r.getCpu();
            case MEMORY -> r.getMemory();
            case DISK -> r.getDisk();
            case NETWORK_IN -> r.getNetworkIn();
            case NETWORK_OUT -> r.getNetworkOut();
        };
    }

    private static String label(MetricType m) {
        return switch (m) {
            case CPU -> "CPU";
            case MEMORY -> "memory";
            case DISK -> "disk";
            case NETWORK_IN -> "network in";
            case NETWORK_OUT -> "network out";
        };
    }

    private static String unit(MetricType m) {
        return (m == MetricType.NETWORK_IN || m == MetricType.NETWORK_OUT) ? " KB/s" : "%";
    }
}
