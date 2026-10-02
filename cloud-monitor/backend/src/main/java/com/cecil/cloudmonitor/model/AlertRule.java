package com.cecil.cloudmonitor.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** e.g. "CPU > 85% on any resource -> WARNING". */
@Entity
@Table(name = "alert_rules")
@Getter @Setter @NoArgsConstructor
public class AlertRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Enumerated(EnumType.STRING)
    private MetricType metric;

    /** ">" or "<" */
    private String operator;

    private double threshold;

    /** null = applies to every resource. */
    private Long resourceId;

    @Enumerated(EnumType.STRING)
    private Severity severity;

    private boolean enabled = true;

    public AlertRule(String name, MetricType metric, String operator, double threshold, Severity severity) {
        this.name = name;
        this.metric = metric;
        this.operator = operator;
        this.threshold = threshold;
        this.severity = severity;
    }
}
